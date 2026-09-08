package com.hexvane.titan.dunewyrm;

import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.spawn.BlockRotations;
import com.hexvane.titan.system.TitanPartSyncSystem;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollisionConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Poses Dunewyrm voxels from their segment transforms on the owning snake root.
 */
public final class DunewyrmPartSyncSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(
        DunewyrmPartComponent.getComponentType(),
        TransformComponent.getComponentType());

    private static final class Scratch {
        @Nonnull
        private final Vector3d worldPosition = new Vector3d();
        @Nonnull
        private final Rotation3f rotation = new Rotation3f();
        @Nonnull
        private final Quaterniond quaternion = new Quaterniond();
        @Nonnull
        private final Vector3d euler = new Vector3d();
    }

    @Nonnull
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    /** Same switch as the walking titans; everything below goes through the command buffer / thread-local scratch. */
    @Override
    public boolean isParallel(final int archetypeChunkSize, final int taskCount) {
        return TitanConfig.get().isParallelPartSync() && useParallel(archetypeChunkSize, taskCount);
    }

    @Override
    public void tick(final float dt,
                     final int index,
                     @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull final Store<EntityStore> store,
                     @Nonnull final CommandBuffer<EntityStore> commandBuffer) {

        final var part = archetypeChunk.getComponent(index, DunewyrmPartComponent.getComponentType());
        final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (part == null || transform == null) return;

        if (part.consumeSpawnScalePass(dt,
            TitanPartSyncSystem.SPAWN_SCALE_PASS_DELAY,
            TitanPartSyncSystem.SPAWN_SCALE_PASS_STAGGER)) {
            final var scaleComponent = archetypeChunk.getComponent(index, EntityScaleComponent.getComponentType());
            if (scaleComponent != null) scaleComponent.setScale(part.getScale());
        }

        final Ref<EntityStore> owner = part.getOwner();
        final DunewyrmComponent worm = owner != null && owner.isValid()
            ? commandBuffer.getComponent(owner, DunewyrmComponent.getComponentType())
            : null;

        if (worm == null || worm.getState() == DunewyrmState.DYING) {
            commandBuffer.removeEntity(archetypeChunk.getReferenceTo(index), RemoveReason.REMOVE);
            return;
        }

        if (part.consumeScaleRefresh(dt, DunewyrmPartComponent.SCALE_REFRESH_SECONDS)) {
            final var scaleComponent = archetypeChunk.getComponent(index, EntityScaleComponent.getComponentType());
            if (scaleComponent != null) scaleComponent.setScale(part.getScale());
        }

        final int segmentIndex = part.getSegmentIndex();
        if (segmentIndex < 0 || segmentIndex >= worm.getSegments().size()) return;
        final DunewyrmSegment segment = worm.getSegments().get(segmentIndex);

        // Segments nobody is near only pose on some ticks. Decided per segment (by the AI tick) rather than
        // per voxel, so a whole segment moves together: per-voxel staggering tore the body into jitter.
        if (!segment.isSyncThisTick()) return;
        if (!part.consumeSyncSlot(dt, TitanConfig.get().getPartSyncInterval())) return;

        final Ref<EntityStore> self = archetypeChunk.getReferenceTo(index);
        // Climbable voxels drag anchored players with them. While the snake dives, strip collision so
        // riders are not yanked underground (and flung when they hit solid ground). On the sync slot rather
        // than every tick: a tenth of a second of lag on the swap is invisible.
        final boolean buried = worm.getState() == DunewyrmState.TUNNEL || worm.getTunnelDepth() > 0.25f;
        syncClimbable(part, self, commandBuffer, buried);

        final Scratch scratch = SCRATCH.get();
        final float yaw = segment.getYaw();
        final float pitch = segment.getPitch();
        final double cosY = Math.cos(yaw);
        final double sinY = Math.sin(yaw);
        final double cosP = Math.cos(pitch);
        final double sinP = Math.sin(pitch);
        final Vector3d local = part.getLocalOffset();
        // Yaw around Y, then pitch around local X so reared segments tilt with the cobra curve.
        final double lx = local.x;
        final double ly = local.y * cosP - local.z * sinP;
        final double lz = local.y * sinP + local.z * cosP;
        final double wx = lx * cosY + lz * sinY;
        final double wz = -lx * sinY + lz * cosY;
        scratch.worldPosition.set(
            segment.getPosition().x + wx,
            segment.getPosition().y + ly,
            segment.getPosition().z + wz);
        scratch.rotation.set(pitch, yaw, 0);
        BlockRotations.compose(scratch.rotation, part.getBlockRotation(), scratch.quaternion, scratch.euler);

        if (!Double.isFinite(scratch.worldPosition.x)
            || !Double.isFinite(scratch.worldPosition.y)
            || !Double.isFinite(scratch.worldPosition.z)) {
            return;
        }

        if (!part.hasDriftedFrom(scratch.worldPosition, scratch.rotation,
            TitanConfig.get().getPartSyncEpsilon(), 0.01)) {
            return;
        }

        // No per-voxel vertical step cap here: only every other voxel is climbable, so capping just those
        // split each segment into two interleaved halves whenever it crossed a bump. Rider safety comes from
        // the segment's own ground easing (DunewyrmSpawner.easeGround) moving the whole platform gently,
        // plus the fall guard for landings.
        transform.getPosition().set(scratch.worldPosition);
        transform.getRotation().set(scratch.rotation);
    }

    private static void syncClimbable(@Nonnull final DunewyrmPartComponent part,
                                      @Nonnull final Ref<EntityStore> self,
                                      @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                      final boolean buried) {
        if (!part.isClimbable()) return;

        final var collision = commandBuffer.getComponent(self, HitboxCollision.getComponentType());
        if (buried) {
            if (collision != null) {
                commandBuffer.tryRemoveComponent(self, HitboxCollision.getComponentType());
            }
            return;
        }
        if (collision != null) return;

        final HitboxCollisionConfig config =
            HitboxCollisionConfig.getAssetMap().getAsset(DunewyrmTuning.COLLIDER_CONFIG);
        if (config != null) {
            commandBuffer.putComponent(self, HitboxCollision.getComponentType(), new HitboxCollision(config));
        }
    }
}
