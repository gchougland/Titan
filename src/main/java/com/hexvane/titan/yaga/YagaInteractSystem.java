package com.hexvane.titan.yaga;

import com.hexvane.titan.combat.TitanSound;
import com.hexvane.titan.entity.TitanFixtureComponent;
import com.hexvane.titan.entity.TitanPartComponent;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.event.events.ecs.UseEntityEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollisionConfig;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Answers a player clicking on a Baba Yaga house.
 *
 * <p>A titan's blocks are ordinary entities, so the engine will happily let a player point at one and use
 * it. What it cannot do is say what using it means: the interaction assets have no way to express "open
 * this titan's second chest", and there is one asset shared by every usable block on every titan. So the
 * asset chain is a no-op and the answer is worked out here instead, from the components on the block that
 * was clicked and on the titan it belongs to.
 *
 * <p>Two kinds of click arrive. A block carrying a {@link TitanFixtureComponent} is a named piece of
 * furniture and does its own thing; a block without one belongs to a bone the player addresses as a whole,
 * and for a house that means telling it to sit down or stand up.
 *
 * <p>The event is dispatched on the <em>player</em>, not on what they clicked — see
 * {@code UseEntityInteraction} — so the query is empty and the target comes off the event.
 */
public final class YagaInteractSystem extends EntityEventSystem<EntityStore, UseEntityEvent.Pre> {

    private static final String WITCHES_BREW_ID = "Titan_Witches_Brew";
    private static final String WITCHES_BREW_SFX = "SFX_Arcane_Workbench_Craft";

    public YagaInteractSystem() {
        super(UseEntityEvent.Pre.class);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(final int index,
                       @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull final Store<EntityStore> store,
                       @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull final UseEntityEvent.Pre event) {

        if (event.isCancelled() || event.getInteractionType() != InteractionType.Use) return;

        final Ref<EntityStore> target = event.getTargetEntity();
        if (!target.isValid()) return;

        // Not a titan's block at all. Every other interactable entity in the world reaches this too, so
        // this is the gate that keeps the mod out of everything else's business.
        final var part = store.getComponent(target, TitanPartComponent.getComponentType());
        if (part == null) return;

        final Ref<EntityStore> root = part.getOwner();
        if (root == null || !root.isValid()) return;

        final var yaga = store.getComponent(root, YagaComponent.getComponentType());
        if (yaga == null) return;

        final Ref<EntityStore> player = archetypeChunk.getReferenceTo(index);

        // Held Witches Brew upgrades a Baby Yaga. Cancel first so rest/chest/door do not also run.
        final ItemStack held = event.getContext().getHeldItem();
        if (held != null && WITCHES_BREW_ID.equals(held.getItemId())) {
            event.setCancelled(true);
            tryBrew(store, player, root, yaga, event.getContext());
            return;
        }

        final var fixture = store.getComponent(target, TitanFixtureComponent.getComponentType());

        if (fixture == null) {
            YagaUse.toggleRest(store, player, root, yaga);
            return;
        }

        switch (fixture.getKind()) {
            case DOOR -> swing(store, commandBuffer, target, fixture);
            case CHEST -> YagaUse.openContainer(store, player, yaga, fixture.getInventoryIndex());
            case FURNACE -> YagaUse.openFurnace(store, player, yaga, target);
            case BED -> YagaUse.sleep(store, commandBuffer, player, yaga);
            case WORKBENCH -> YagaUse.openBench(store, player, yaga, target);
        }
    }

    /**
     * Pours Witches Brew into a Baby Yaga: grow it, then spend one bottle.
     *
     * <p>Owner and stage are checked here; the swap itself runs on {@code world.execute} because
     * {@link YagaUpgrade} removes and rebuilds entities. The bottle is only taken after a successful
     * grow so a failed swap does not eat the item.
     */
    private static void tryBrew(@Nonnull final Store<EntityStore> store,
                                @Nonnull final Ref<EntityStore> player,
                                @Nonnull final Ref<EntityStore> root,
                                @Nonnull final YagaComponent yaga,
                                @Nonnull final InteractionContext context) {

        if (!YagaUse.claim(store, player, yaga)) return;

        if (yaga.getStage() != YagaComponent.Stage.BABY) {
            YagaUse.tell(store, player, "titan_yaga.yaga.brew.notBaby");
            return;
        }

        final ItemContainer container = context.getHeldItemContainer();
        final byte slot = context.getHeldItemSlot();
        final ItemStack brew = context.getHeldItem();
        if (container == null || ItemStack.isEmpty(brew) || !WITCHES_BREW_ID.equals(brew.getItemId())) return;

        final var world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (!root.isValid() || !player.isValid()) return;

            final var live = store.getComponent(root, YagaComponent.getComponentType());
            if (live == null || live.getStage() != YagaComponent.Stage.BABY) {
                YagaUse.tell(store, player, "titan_yaga.yaga.brew.notBaby");
                return;
            }

            final var transform = store.getComponent(root, TransformComponent.getComponentType());
            final Vector3d position = transform == null ? null : new Vector3d(transform.getPosition());

            final YagaUpgrade.Result result = YagaUpgrade.apply(store, root);
            final var playerRef = store.getComponent(player, PlayerRef.getComponentType());
            if (!result.ok()) {
                if (playerRef != null) {
                    playerRef.sendMessage(Message.translation("titan_yaga.yaga.brew.failed")
                        .param("error", String.valueOf(result.error())));
                }
                return;
            }

            // Spend the bottle only after the house has grown.
            final ItemStack stillHeld = container.getItemStack(slot);
            if (!ItemStack.isEmpty(stillHeld) && WITCHES_BREW_ID.equals(stillHeld.getItemId())) {
                final var transaction = container.removeItemStackFromSlot(slot, stillHeld, 1);
                if (transaction.succeeded()) {
                    context.setHeldItem(container.getItemStack(slot));
                }
            }

            if (position != null) {
                TitanSound.play(store, WITCHES_BREW_SFX, position);
            }

            if (playerRef == null) return;
            if (result.dropped() > 0) {
                playerRef.sendMessage(Message.translation("titan_yaga.yaga.brew.spilled")
                    .param("dropped", result.dropped()));
                return;
            }
            playerRef.sendMessage(Message.translation("titan_yaga.yaga.brew.success")
                .param("moved", result.moved())
                .param("total", result.total()));
        });
    }

    /**
     * Opens or closes a door by putting the block's other state on the voxel.
     *
     * <p>The same thing a door in the world does. A door is one block type with named states hanging off
     * it, and the open ones hold the leaf at ninety degrees in their own model with a hitbox to match, so
     * the difference between shut and open is which block the cell holds. A block entity carries a block
     * key like any block does, and changing it replicates on its own.
     *
     * <p>An earlier version of this turned the voxel a quarter turn instead, on the theory that the open
     * states were only an animation the client played. They are not: the animation is how the leaf gets
     * there, and the state is where it stays. Turning the voxel spun the whole cell about its middle,
     * which is a door pirouetting in its frame rather than opening.
     *
     * <p>The collider comes off with it. A voxel's collision is a whole cell whatever shape the block in it
     * is, so a door that had only changed block would still be a solid block in the doorway; taking the
     * collider off outright is how the rig says a voxel has no collision, and it is put back when the door
     * shuts.
     *
     * <p>Not locked to the owner. A door is the one fixture that is only about getting in and out, and a
     * house whose door only its owner could open would be no use to anyone the owner invited aboard.
     */
    private static void swing(@Nonnull final Store<EntityStore> store,
                              @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                              @Nonnull final Ref<EntityStore> target,
                              @Nonnull final TitanFixtureComponent fixture) {

        if (!fixture.canSwing()) return;

        final var blockEntity = store.getComponent(target, BlockEntity.getComponentType());
        if (blockEntity == null) return;

        final boolean open = fixture.toggleOpen();
        final String block = fixture.blockFor(open);
        if (block != null) blockEntity.setBlockTypeKey(block, target, commandBuffer);

        if (open) hangOpen(store, commandBuffer, target, fixture);
        else shut(store, commandBuffer, target, fixture);

        // Restated along with the swing, because "open" and "close" are different instructions and a door
        // still offering to open itself would read as broken.
        final var interactions = store.getComponent(target, Interactions.getComponentType());
        if (interactions != null) interactions.setInteractionHint(fixture.getHint());

        final var transform = store.getComponent(target, TransformComponent.getComponentType());
        if (transform != null) {
            TitanSound.play(commandBuffer, doorSound(blockEntity.getBlockTypeKey()), transform.getPosition());
        }
    }

    /** Takes the door's collider off, noting which one it was so shutting it can put the same one back. */
    private static void hangOpen(@Nonnull final Store<EntityStore> store,
                                 @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                 @Nonnull final Ref<EntityStore> target,
                                 @Nonnull final TitanFixtureComponent fixture) {

        final var collision = store.getComponent(target, HitboxCollision.getComponentType());
        if (collision == null) return;

        fixture.setColliderConfigId(collision.getHitboxCollisionConfigId());
        commandBuffer.tryRemoveComponent(target, HitboxCollision.getComponentType());
    }

    /**
     * Puts the door's collider back.
     *
     * <p>Nothing happens if the door never had one, which is the case for a house whose walls are not
     * climbable: an open door on one of those was already passable and shutting it changes only the look.
     */
    private static void shut(@Nonnull final Store<EntityStore> store,
                             @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                             @Nonnull final Ref<EntityStore> target,
                             @Nonnull final TitanFixtureComponent fixture) {

        final String configId = fixture.getColliderConfigId();
        if (configId == null) return;

        final var config = HitboxCollisionConfig.getAssetMap().getAsset(configId);
        if (config == null) return;

        commandBuffer.putComponent(target, HitboxCollision.getComponentType(), new HitboxCollision(config));
    }

    /**
     * The door's own sound for the swing it just made, taken off the block type.
     *
     * <p>Read from the block rather than named in the variant, so a hut with a wooden door and a house with
     * a stone one each sound like themselves without either having to say so. The swing is a small movement
     * on a thin panel and easy to miss, which is most of why it is worth the lookup.
     *
     * @param blockKey the state the door has just been put into, each of which names the sound of arriving
     *                 in it — the open ones a latch coming undone, the shut ones a door meeting its frame
     */
    @Nullable
    private static String doorSound(@Nullable final String blockKey) {
        if (blockKey == null) return null;

        final var blockType = BlockType.getAssetMap().getAsset(blockKey);
        if (blockType == null) return null;

        return blockType.getInteractionSoundEventId();
    }
}
