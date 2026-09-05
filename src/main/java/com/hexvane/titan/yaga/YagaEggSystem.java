package com.hexvane.titan.yaga;

import com.hexvane.titan.combat.TitanSound;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanShellComponent;
import com.hexvane.titan.entity.TitanState;
import com.hexvane.titan.spawn.TitanSiteMemory;
import com.hexvane.titan.system.TitanAnimationSystem;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Watches an unhatched Baba Yaga egg and opens it when its shell has been broken through.
 *
 * <p>None of the breaking is here. The shell is one target however many blocks it is made of, and
 * {@link com.hexvane.titan.system.TitanShellDamageSystem} drains its pool as a player smacks it, so all
 * this has to do is notice the pool run out. What it adds is the ending: the shell comes apart, a hatchling
 * is built in its place, and the nest it was sitting in is left behind as real blocks.
 *
 * <p>Stands in for {@link com.hexvane.titan.system.TitanAiSystem}, which skips pets outright. An egg needs
 * almost nothing that system does — it never moves and never fights — but it does still need the state
 * clock advanced and the weakpoint audit run, and those are the two things every titan needs whatever it is.
 */
public final class YagaEggSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Seconds the root outlives its shell.
     *
     * <p>Long enough for every remaining shell block to have seen one sync tick and come loose, so the egg
     * is seen to break apart rather than blinking out.
     */
    private static final float BREAK_LINGER_SECONDS = 2f;

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(
        TitanComponent.getComponentType(),
        TitanShellComponent.getComponentType(),
        TransformComponent.getComponentType());

    /** Before the pose is built, so a shell that broke this tick is already coming apart in it. */
    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies =
        Set.of(new SystemDependency<>(Order.BEFORE, TitanAnimationSystem.class));

    /** Where a hatch is written down, so the nest does not produce a second egg. */
    @Nullable
    private final ResourceType<EntityStore, TitanSiteMemory> siteMemoryType;

    public YagaEggSystem(@Nullable final ResourceType<EntityStore, TitanSiteMemory> siteMemoryType) {
        this.siteMemoryType = siteMemoryType;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void tick(final float dt,
                     final int index,
                     @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull final Store<EntityStore> store,
                     @Nonnull final CommandBuffer<EntityStore> commandBuffer) {

        final var titan = archetypeChunk.getComponent(index, TitanComponent.getComponentType());
        final var shell = archetypeChunk.getComponent(index, TitanShellComponent.getComponentType());
        final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (titan == null || shell == null || transform == null) return;
        if (YagaComponent.Stage.of(titan.getVariantId()) != YagaComponent.Stage.EGG) return;

        final Ref<EntityStore> self = archetypeChunk.getReferenceTo(index);
        titan.addStateTime(dt);

        // Whoever hit it last is whoever it will belong to. The combat AI would normally drain this and
        // pick a fight; nothing else reads it on a passive titan, so an egg can use the same field to
        // remember which player has been working on it.
        final Ref<EntityStore> attacker = titan.consumePendingAttacker();
        if (attacker != null) titan.setTarget(attacker);

        if (titan.getState() == TitanState.DYING) {
            titan.addDeathTimer(dt);
            if (titan.getDeathTimer() >= BREAK_LINGER_SECONDS) commandBuffer.removeEntity(self, RemoveReason.REMOVE);
            return;
        }

        if (shell.isBroken()) {
            // DYING is what makes the shell come loose: the part sync system stops holding a dying titan's
            // blocks in place and the ragdoll takes them over, so the egg is seen to break apart in one go
            // rather than having been chipped away block by block. The root stays for a couple of seconds
            // so that has time to happen.
            titan.setState(TitanState.DYING);
            final var variant = titan.getVariant();
            if (variant != null) {
                TitanSound.play(commandBuffer, variant.getDeathSound(), transform.getPosition());
            }
            hatch(store, titan, transform);
            return;
        }

        // Only ever LOST here. Every hit on the shell is charged to the pool above and none of its blocks
        // are credited as broken, so the audit's own verdict on a shell titan can never be DESTROYED.
        if (titan.auditWeakpoints(store, dt) == TitanComponent.WeakpointStatus.LOST) {
            // Shell blocks went missing without anyone breaking them, which means the ground under the egg
            // has stopped ticking. An unload, not a hatch: the egg comes back on the next visit.
            commandBuffer.removeEntity(self, RemoveReason.REMOVE);
        }
    }

    /**
     * Builds the hatchling and retires the nest.
     *
     * <p>Deferred to the world thread: spawning a titan adds a few hundred entities and attaches components
     * to them, neither of which a ticking system may do. The egg carries on breaking apart in the meantime,
     * which is what the two are meant to look like anyway.
     */
    private void hatch(@Nonnull final Store<EntityStore> store,
                       @Nonnull final TitanComponent titan,
                       @Nonnull final TransformComponent transform) {

        final long cell = titan.getSiteCell();
        final Ref<EntityStore> breaker = titan.getTarget();
        final UUID owner = ownerUuid(store, breaker);
        final var position = new Vector3d(transform.getPosition());
        final float yaw = titan.getYaw();
        final var world = store.getExternalData().getWorld();

        world.execute(() -> {
            // Before the spawn, and unconditionally: an egg that failed to hatch has still been broken, and
            // putting another one back in the same shattered nest is worse than leaving the nest empty.
            if (siteMemoryType != null && cell != TitanComponent.NO_SITE) {
                store.getResource(siteMemoryType).markCleared(cell, TitanSiteMemory.FOREVER);
            }
            YagaEggSiteSystem.markHatchedNear(store, position);

            final YagaSpawn.Result result =
                YagaSpawn.spawn(store, YagaComponent.Stage.BABY, position, yaw, owner);

            if (!result.ok()) {
                LOGGER.at(Level.WARNING).log("A Baba Yaga egg at %s hatched into nothing: %s", position, result.error());
                tell(store, breaker, "titan_yaga.yaga.egg.hatchFailed");
                return;
            }

            if (owner == null) {
                // Only reachable from /titan spawn or a hatch whose breaker logged out mid-swing. The house
                // is left ownerless rather than given away, and a command can adopt it.
                LOGGER.at(Level.INFO).log("A Baba Yaga egg at %s hatched with no owner to claim it", position);
                return;
            }

            // The wand comes with the house, because the two are useless apart: a house nobody can point
            // anywhere follows its owner and does nothing else, and a wand with no house to wave at does
            // nothing at all. Given rather than dropped so it cannot be missed in the wreckage of the shell.
            if (breaker != null && breaker.isValid()) {
                Player.giveItem(new ItemStack(YagaWand.ITEM, 1), breaker, store);
            }

            tell(store, breaker, "titan_yaga.yaga.egg.hatched");
        });
    }

    /** The account UUID behind a player entity, or {@code null} if it is not a player or has gone. */
    @Nullable
    private static UUID ownerUuid(@Nonnull final Store<EntityStore> store, @Nullable final Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) return null;
        final var player = store.getComponent(ref, PlayerRef.getComponentType());
        return player == null ? null : player.getUuid();
    }

    private static void tell(@Nonnull final Store<EntityStore> store,
                             @Nullable final Ref<EntityStore> ref,
                             @Nonnull final String key) {

        if (ref == null || !ref.isValid()) return;
        final var player = store.getComponent(ref, PlayerRef.getComponentType());
        if (player != null) player.sendMessage(Message.translation(key));
    }
}
