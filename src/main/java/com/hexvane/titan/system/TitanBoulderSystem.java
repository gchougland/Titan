package com.hexvane.titan.system;

import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.combat.TitanBoulder;
import com.hexvane.titan.combat.TitanSmashAttack;
import com.hexvane.titan.combat.TitanTelegraph;
import com.hexvane.titan.entity.TitanBoulderComponent;
import com.hexvane.titan.entity.TitanBoulderPartComponent;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanPartComponent;
import com.hexvane.titan.entity.TitanWeakpointComponent;
import com.hexvane.titan.ik.GroundSampler;
import com.hexvane.titan.physics.DebrisBurst;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Flies the boulders titans throw, and breaks them when they land.
 *
 * <p>The engine's projectile and physics systems do not fit here: a boulder is a cluster of block entities
 * with no single body to give a velocity to, and it has to come apart into rubble on impact rather than
 * disappear. It instead borrows both halves of the titan rig. The root holds a transform its blocks hang
 * off, and on landing those blocks are handed to the same debris physics that clears a titan's corpse.
 */
public final class TitanBoulderSystem extends EntityTickingSystem<EntityStore> {

    /** Fraction of speed kept each second. Barely any drag: a boulder is not a feather. */
    private static final double AIR_DRAG = 0.92;
    /** Longest a boulder may stay in the air before it gives up, in seconds. */
    private static final float MAX_FLIGHT_SECONDS = 8f;
    /** Longest a single integration step may be, in blocks, so a fast rock cannot pass through a wall. */
    private static final double MAX_STEP = 0.4;
    /** How close a boulder has to pass to something to count as hitting it, as a multiple of its size. */
    private static final double HIT_RADIUS = 1.6;
    /** How long the root outlives the impact, giving its blocks one tick to notice and let go. */
    private static final float SHATTER_LINGER = 0.2f;
    /** How often the ground under a boulder in flight is marked. */
    private static final float TELEGRAPH_INTERVAL = 0.25f;

    @Nonnull
    private final Query<EntityStore> query =
        Archetype.of(TitanBoulderComponent.getComponentType(), TransformComponent.getComponentType());

    @Nonnull
    private final Vector3d step = new Vector3d();

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(final float dt,
                     final int index,
                     @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull final Store<EntityStore> store,
                     @Nonnull final CommandBuffer<EntityStore> commandBuffer) {

        final var boulder = archetypeChunk.getComponent(index, TitanBoulderComponent.getComponentType());
        final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (boulder == null || transform == null) return;

        final Ref<EntityStore> self = archetypeChunk.getReferenceTo(index);

        if (boulder.isShattered()) {
            boulder.addShatterTimer(dt);
            if (boulder.getShatterTimer() >= SHATTER_LINGER) {
                commandBuffer.removeEntity(self, RemoveReason.REMOVE);
            }
            return;
        }

        boulder.addLifetime(dt);
        boulder.tickArming(dt);

        if (boulder.getLifetime() >= MAX_FLIGHT_SECONDS) {
            // Thrown off the edge of the loaded world, most likely. Let it crumble where it is rather than
            // leaving a rock hanging in the sky forever.
            boulder.shatter();
            return;
        }

        final var chunkStore = store.getExternalData().getWorld().getChunkStore();
        final var position = transform.getPosition();
        final var velocity = boulder.getVelocity();

        velocity.y -= TitanBoulder.GRAVITY * dt;
        velocity.mul(Math.pow(AIR_DRAG, dt));

        if (!advance(store, commandBuffer, chunkStore, boulder, self, position, velocity, dt)) return;

        boulder.getOrientation()
            .integrate(dt, boulder.getAngularVelocity().x, boulder.getAngularVelocity().y, boulder.getAngularVelocity().z)
            .normalize();

        if (boulder.consumeTelegraph(dt, TELEGRAPH_INTERVAL)) {
            TitanTelegraph.ring(commandBuffer, chunkStore, telegraphRing(store, boulder),
                boulder.getLanding(), boulder.getRadius(), 0f);
        }
    }

    /**
     * Moves the boulder along its velocity, stopping at whatever it runs into.
     *
     * <p>Cut into short steps rather than one jump per tick. A boulder crosses more than a block per tick at
     * the speeds these are thrown at, and a single test at the far end of that would sail it through a cliff
     * face and land it in the valley beyond.
     *
     * @return {@code false} if the boulder hit something and has been shattered
     */
    private boolean advance(@Nonnull final Store<EntityStore> store,
                            @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                            @Nonnull final ChunkStore chunkStore,
                            @Nonnull final TitanBoulderComponent boulder,
                            @Nonnull final Ref<EntityStore> self,
                            @Nonnull final Vector3d position,
                            @Nonnull final Vector3d velocity,
                            final float dt) {

        final double distance = velocity.length() * dt;
        final int steps = Math.max(1, (int) Math.ceil(distance / MAX_STEP));
        final double stepTime = dt / (double) steps;

        for (int i = 0; i < steps; i++) {
            step.set(position).fma(stepTime, velocity);

            if (GroundSampler.isSolid(chunkStore, (int) Math.floor(step.x), (int) Math.floor(step.y), (int) Math.floor(step.z))) {
                // Stopped at the last clear point rather than inside the block, so the rubble spawns in the
                // open and the debris physics does not have to dig it back out.
                land(store, commandBuffer, boulder, self, position);
                return false;
            }

            position.set(step);

            if (boulder.isArmed()) {
                final Ref<EntityStore> struck = findVictim(store, boulder, self, position);
                if (struck != null) {
                    land(store, commandBuffer, boulder, self, position);
                    return false;
                }
            }
        }
        return true;
    }

    /** The first thing worth hitting within reach of the boulder, or {@code null}. */
    private Ref<EntityStore> findVictim(@Nonnull final Store<EntityStore> store,
                                        @Nonnull final TitanBoulderComponent boulder,
                                        @Nonnull final Ref<EntityStore> self,
                                        @Nonnull final Vector3d position) {

        final double reach = HIT_RADIUS;
        final Ref<EntityStore> thrower = boulder.getThrower();

        for (final Ref<EntityStore> candidate : TargetUtil.getAllEntitiesInCylinder(position, reach, reach, store)) {
            if (!candidate.isValid() || candidate.getIndex() == self.getIndex()) continue;
            if (thrower != null && candidate.getIndex() == thrower.getIndex()) continue;

            // Everything made of titan is skipped, ours and anyone else's: a rock passing a second titan on
            // its way to a player should go past it, and it must never clip its own thrower's arm.
            if (store.getComponent(candidate, TitanPartComponent.getComponentType()) != null) continue;
            if (store.getComponent(candidate, TitanWeakpointComponent.getComponentType()) != null) continue;
            if (store.getComponent(candidate, TitanComponent.getComponentType()) != null) continue;
            if (store.getComponent(candidate, TitanBoulderPartComponent.getComponentType()) != null) continue;
            if (store.getComponent(candidate, EntityStatMap.getComponentType()) == null) continue;

            return candidate;
        }
        return null;
    }

    private void land(@Nonnull final Store<EntityStore> store,
                      @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                      @Nonnull final TitanBoulderComponent boulder,
                      @Nonnull final Ref<EntityStore> self,
                      @Nonnull final Vector3d at) {

        TitanSmashAttack.execute(store, commandBuffer, boulder.getThrower(), at,
            boulder.getRadius(), boulder.getDamage(), boulder.getKnockback(), TitanSmashAttack.VERTICAL_SHARE,
            boulder.getImpactParticle(), boulder.getImpactSound());

        boulder.shatter();
    }

    /** The thrower's telegraph asset, or nothing if it is no longer around to ask. */
    @Nullable
    private static String telegraphRing(@Nonnull final Store<EntityStore> store,
                                        @Nonnull final TitanBoulderComponent boulder) {
        final Ref<EntityStore> thrower = boulder.getThrower();
        if (thrower == null || !thrower.isValid()) return null;

        final var titan = store.getComponent(thrower, TitanComponent.getComponentType());
        final TitanVariantAsset variant = titan == null ? null : titan.getVariant();
        return variant == null ? null : variant.getTelegraphRingParticle();
    }

    /**
     * Carries a boulder's blocks along with it, and lets them go when it lands.
     *
     * <p>Separate from the flight so it can be ordered after it: a block reads its boulder's position in the
     * same tick the boulder moved, rather than trailing it by one and smearing the rock out behind itself.
     */
    public static final class Parts extends EntityTickingSystem<EntityStore> {

        /** Outward speed given to rubble, in blocks per second per block from the boulder's centre. */
        private static final double BURST_SPREAD = 4.0;
        private static final double BURST_LIFT = 3.5;
        private static final double BURST_SPIN = 6.0;
        /** How much of the boulder's own momentum carries into its rubble. */
        private static final double MOMENTUM_SHARE = 0.25;

        private static final float DEBRIS_LIFETIME_MIN = 2.5f;
        private static final float DEBRIS_LIFETIME_MAX = 6f;

        @Nonnull
        private final Query<EntityStore> query =
            Archetype.of(TitanBoulderPartComponent.getComponentType(), TransformComponent.getComponentType());
        @Nonnull
        private final Set<Dependency<EntityStore>> dependencies =
            Set.of(new SystemDependency<>(Order.AFTER, TitanBoulderSystem.class));

        @Nonnull
        private final Vector3d offset = new Vector3d();
        @Nonnull
        private final Vector3d euler = new Vector3d();
        @Nonnull
        private final Vector3d burst = new Vector3d();
        @Nonnull
        private final Vector3d spin = new Vector3d();

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

            final var part = archetypeChunk.getComponent(index, TitanBoulderPartComponent.getComponentType());
            final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
            if (part == null || transform == null) return;

            // Already rubble, and owned by the ragdoll system from here on.
            if (part.isReleased()) return;

            final Ref<EntityStore> boulderRef = part.getBoulder();
            final var boulder = boulderRef != null && boulderRef.isValid()
                ? store.getComponent(boulderRef, TitanBoulderComponent.getComponentType())
                : null;
            final var boulderTransform = boulderRef != null && boulderRef.isValid()
                ? store.getComponent(boulderRef, TransformComponent.getComponentType())
                : null;

            if (boulder == null || boulderTransform == null) {
                commandBuffer.removeEntity(archetypeChunk.getReferenceTo(index), RemoveReason.REMOVE);
                return;
            }

            if (boulder.isShattered()) {
                release(commandBuffer, archetypeChunk.getReferenceTo(index), part, boulder);
                return;
            }

            offset.set(part.getLocalOffset());
            boulder.getOrientation().transform(offset);
            transform.getPosition().set(boulderTransform.getPosition()).add(offset);

            boulder.getOrientation().getEulerAnglesYXZ(euler);
            transform.getRotation().set((float) euler.x, (float) euler.y, (float) euler.z);
        }

        /**
         * Hands one block over to the debris physics.
         *
         * <p>The part marker is added already detached, which costs nothing: the sync system skips detached
         * parts before it looks for the titan they belong to, so a rock chip that never had one passes
         * straight through and the ragdoll system tumbles it like any other rubble.
         */
        private void release(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                             @Nonnull final Ref<EntityStore> self,
                             @Nonnull final TitanBoulderPartComponent part,
                             @Nonnull final TitanBoulderComponent boulder) {

            final var random = ThreadLocalRandom.current();

            // Every chip scatters at the same speed, then inherits a share of the rock's own flight.
            DebrisBurst.solve(part.getLocalOffset(), BURST_SPREAD, BURST_LIFT, burst);
            burst.fma(MOMENTUM_SHARE, boulder.getVelocity());
            DebrisBurst.spin(random, BURST_SPIN, spin);

            final var rubble = commandBuffer.ensureAndGetComponent(self, TitanPartComponent.getComponentType());
            rubble.detach(burst, spin, random.nextFloat(DEBRIS_LIFETIME_MIN, DEBRIS_LIFETIME_MAX));
            part.setReleased(true);
        }
    }
}
