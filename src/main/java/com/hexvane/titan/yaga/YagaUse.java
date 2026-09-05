package com.hexvane.titan.yaga;

import com.hexvane.titan.combat.TitanSound;
import com.hexvane.titan.entity.TitanComponent;
import com.hypixel.hytale.builtin.beds.sleep.components.PlayerSleep;
import com.hypixel.hytale.builtin.beds.sleep.components.PlayerSomnolence;
import com.hypixel.hytale.builtin.beds.sleep.systems.player.SleepNotificationSystem;
import com.hypixel.hytale.builtin.beds.sleep.systems.world.CanSleepInWorld;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What each way of using a Baba Yaga house actually does.
 *
 * <p>Split from {@link YagaInteractSystem}, which only works out which of these a click means. They are
 * separate because the same actions are reachable from more than one place — a command can rest a house as
 * well as a click can — and because the ownership rule they share is worth stating once.
 */
public final class YagaUse {

    private YagaUse() {
    }

    /**
     * Tells the house to sit down, or to stand back up.
     *
     * <p>Owner only, like everything except the door. A house is a player's home and their storage; anyone
     * being able to fold it up while they were standing on it would make it useless.
     */
    public static void toggleRest(@Nonnull final Store<EntityStore> store,
                                  @Nonnull final Ref<EntityStore> player,
                                  @Nonnull final Ref<EntityStore> root,
                                  @Nonnull final YagaComponent yaga) {

        if (!claim(store, player, yaga)) return;

        // Refused rather than silently ignored: a house folding its legs in mid-air would land in whatever
        // shape it happened to be in, and the leap is over in a second anyway.
        if (yaga.isLeaping()) {
            tell(store, player, "titan_yaga.yaga.mode.midLeap");
            return;
        }

        final boolean resting = yaga.getMode() == YagaComponent.Mode.RESTING;
        yaga.setMode(resting ? YagaComponent.Mode.FOLLOW : YagaComponent.Mode.RESTING);

        final var titan = store.getComponent(root, TitanComponent.getComponentType());
        final var transform = store.getComponent(root, TransformComponent.getComponentType());
        final var variant = titan == null ? null : titan.getVariant();
        if (variant != null && transform != null) {
            TitanSound.play(store, variant.getCrouchSound(), transform.getPosition());
        }

        tell(store, player, resting ? "titan_yaga.yaga.mode.follow" : "titan_yaga.yaga.mode.resting");
    }

    /**
     * Opens one of the house's own chests.
     *
     * <p>Given no block coordinates at all, which is what makes this work on something that moves: the
     * container is held by the titan and the window is opened straight onto it, rather than onto the chunk
     * the chest would be sitting in.
     */
    public static void openContainer(@Nonnull final Store<EntityStore> store,
                                     @Nonnull final Ref<EntityStore> player,
                                     @Nonnull final YagaComponent yaga,
                                     final int inventoryIndex) {

        if (!claim(store, player, yaga)) return;

        final var container = yaga.inventory(inventoryIndex);
        if (container == null) return;

        final var playerComponent = store.getComponent(player, Player.getComponentType());
        if (playerComponent == null) return;

        playerComponent.getPageManager()
            .setPageWithWindows(player, store, Page.Bench, true, new ContainerWindow(container));
    }

    /**
     * Opens the house's furnace.
     *
     * <p>Owner only, unlike the workbench. A furnace holds the player's ore and their fuel for as long as
     * it takes to smelt them, so it is storage in a way a workbench is not.
     *
     * @param furnaceVoxel the furnace voxel, whose position stands in for the block the window expects
     */
    public static void openFurnace(@Nonnull final Store<EntityStore> store,
                                   @Nonnull final Ref<EntityStore> player,
                                   @Nonnull final YagaComponent yaga,
                                   @Nonnull final Ref<EntityStore> furnaceVoxel) {

        if (!claim(store, player, yaga)) return;

        final YagaFurnace furnace = yaga.getFurnace();
        final var transform = store.getComponent(furnaceVoxel, TransformComponent.getComponentType());
        if (furnace == null || transform == null) return;

        furnace.open(store, player, furnaceVoxel, transform.getPosition());
    }

    /**
     * Puts the owner to bed.
     *
     * <p>All this does is tell the engine they are nodding off, which is the whole of what a bed does on
     * the server: the stock beds plugin then holds out for every other player to do the same and winds the
     * clock forward to morning. The player is not mounted on the bed as they would be on a real one, since
     * the engine's mount for that is a block position and this bed walks about.
     *
     * <p>No respawn point, unlike a real bed. A respawn point is a block position kept on the chunk, and
     * there is no version of it that follows something that moves; setting one where the house happened to
     * be standing would strand the player in an empty field the next time they died.
     */
    public static void sleep(@Nonnull final Store<EntityStore> store,
                             @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                             @Nonnull final Ref<EntityStore> player,
                             @Nonnull final YagaComponent yaga) {

        if (!claim(store, player, yaga)) return;

        final var world = store.getExternalData().getWorld();

        // Checked so the player is told why nothing happened. Without it, clicking the bed in daylight
        // would put them into a sleep the engine silently refuses to act on.
        if (CanSleepInWorld.check(world).isNegative()) {
            tell(store, player, "titan_yaga.yaga.bed.notNight");
            return;
        }

        commandBuffer.putComponent(player, PlayerSomnolence.getComponentType(),
            PlayerSleep.NoddingOff.createComponent());
        commandBuffer.run(s -> SleepNotificationSystem.maybeDoNotification(s, false));
    }

    /**
     * Opens the house's workbench.
     *
     * <p>Not locked to the owner. A workbench takes nothing and keeps nothing — it crafts out of the
     * player's own pockets — so there is nothing for a stranger using it to take.
     *
     * @param bench the workbench voxel, whose position stands in for the block the window expects
     */
    public static void openBench(@Nonnull final Store<EntityStore> store,
                                 @Nonnull final Ref<EntityStore> player,
                                 @Nonnull final YagaComponent yaga,
                                 @Nonnull final Ref<EntityStore> bench) {

        final var blockEntity = store.getComponent(bench, BlockEntity.getComponentType());
        final var transform = store.getComponent(bench, TransformComponent.getComponentType());
        if (blockEntity == null || transform == null) return;

        final BlockType blockType = BlockType.getAssetMap().getAsset(blockEntity.getBlockTypeKey());
        if (blockType == null || blockType.getBench() == null) return;

        final var playerComponent = store.getComponent(player, Player.getComponentType());
        if (playerComponent == null) return;

        final var position = transform.getPosition();
        final var window = new YagaBenchWindow(bench,
            (int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z), blockType);

        playerComponent.getPageManager().setPageWithWindows(player, store, Page.Bench, true, window);
    }

    /**
     * Whether this player is allowed to do things to this house, telling them if not.
     *
     * <p>An ownerless house — one spawned by a command, or hatched by somebody who logged out mid-swing —
     * is refused rather than adopted. Taking ownership by touching it would let the next passer-by claim
     * one somebody else had already filled.
     */
    public static boolean claim(@Nonnull final Store<EntityStore> store,
                                @Nonnull final Ref<EntityStore> player,
                                @Nonnull final YagaComponent yaga) {

        final var playerRef = store.getComponent(player, PlayerRef.getComponentType());
        if (playerRef == null) return false;

        if (yaga.isOwner(playerRef.getUuid())) return true;

        playerRef.sendMessage(Message.translation("titan_yaga.yaga.notYours"));
        return false;
    }

    /** Sends a player one line of chat, if they are still there to hear it. */
    public static void tell(@Nonnull final Store<EntityStore> store,
                            @Nullable final Ref<EntityStore> player,
                            @Nonnull final String key) {

        if (player == null || !player.isValid()) return;
        final var playerRef = store.getComponent(player, PlayerRef.getComponentType());
        if (playerRef != null) playerRef.sendMessage(Message.translation(key));
    }
}
