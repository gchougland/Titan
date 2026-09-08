package com.hexvane.titan.dunewyrm;

import com.hexvane.titan.combat.TitanSound;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Hits on Dunewyrm body voxels drain that segment's HP pool. Structural changes run on {@code world.execute}.
 */
public final class DunewyrmSegmentDamageSystem extends DamageEventSystem {

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(
        DunewyrmHitComponent.getComponentType(),
        TransformComponent.getComponentType());

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void handle(final int index,
                       @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull final Store<EntityStore> store,
                       @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull final Damage damage) {

        if (damage.isCancelled()) return;

        final var hit = archetypeChunk.getComponent(index, DunewyrmHitComponent.getComponentType());
        if (hit == null) return;

        final Ref<EntityStore> owner = hit.getOwner();
        if (owner == null || !owner.isValid()) return;

        final DunewyrmComponent worm = commandBuffer.getComponent(owner, DunewyrmComponent.getComponentType());
        if (worm == null || worm.getState() == DunewyrmState.DYING) return;
        if (worm.isPendingStructural()) {
            damage.setCancelled(true);
            return;
        }

        final int segmentIndex = hit.getSegmentIndex();
        if (segmentIndex < 0 || segmentIndex >= worm.getSegments().size()) return;
        final DunewyrmSegment segment = worm.getSegments().get(segmentIndex);
        if (!segment.getRole().hasHealth()) {
            damage.setCancelled(true);
            return;
        }

        damage.setCancelled(true);
        final float amount = damage.getAmount();
        if (amount <= 0f) return;

        final int attackerIndex = damage.getSource() instanceof Damage.EntitySource entitySource
            && entitySource.getRef().isValid()
            ? entitySource.getRef().getIndex()
            : -1;
        final long tick = store.getExternalData().getWorld().getTick();
        if (!segment.acceptHit(attackerIndex, tick)) {
            return;
        }

        final boolean destroyed = segment.absorb(amount);
        final DunewyrmEncounter encounter = DunewyrmEncounter.getOrCreate(worm.getEncounterId());
        encounter.setRemainingBodyHealth(encounter.getRemainingBodyHealth() - amount);

        final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (transform != null) {
            ParticleUtil.spawnParticleEffect(DunewyrmTuning.BREAK_PARTICLE, transform.getPosition(), commandBuffer);
            final var variant = worm.getVariant();
            if (variant != null) {
                TitanSound.play(commandBuffer, variant.getImpactSound(), transform.getPosition());
            }
        }

        if (!destroyed) return;

        worm.setPendingStructural(true);
        final int bodyOrdinal = worm.bodyOrdinal(segmentIndex);
        final Vector3d breakPos = transform != null
            ? new Vector3d(transform.getPosition())
            : new Vector3d(segment.getPosition());
        final var world = store.getExternalData().getWorld();

        world.execute(() -> {
            try {
                if (!owner.isValid()) return;
                final DunewyrmComponent live = store.getComponent(owner, DunewyrmComponent.getComponentType());
                if (live == null) return;
                handleSegmentDestroyed(store, owner, live, bodyOrdinal, breakPos);
            } finally {
                if (owner.isValid()) {
                    final DunewyrmComponent live = store.getComponent(owner, DunewyrmComponent.getComponentType());
                    if (live != null) live.setPendingStructural(false);
                }
            }
        });
    }

    private static void handleSegmentDestroyed(@Nonnull final Store<EntityStore> store,
                                               @Nonnull final Ref<EntityStore> root,
                                               @Nonnull final DunewyrmComponent worm,
                                               final int bodyOrdinal,
                                               @Nonnull final Vector3d breakPos) {

        TitanSound.play(store, DunewyrmTuning.BREAK_SOUND, breakPos);
        ParticleUtil.spawnParticleEffect(DunewyrmTuning.BREAK_PARTICLE, breakPos, store);

        final int bodyCount = worm.bodyCount();
        if (bodyOrdinal < 0 || bodyOrdinal >= bodyCount) return;

        final boolean end = bodyOrdinal == 0 || bodyOrdinal == bodyCount - 1;
        if (end || bodyCount <= 1) {
            shorten(store, root, worm, bodyOrdinal);
        } else {
            split(store, root, worm, bodyOrdinal, breakPos);
        }
    }

    private static void shorten(@Nonnull final Store<EntityStore> store,
                                @Nonnull final Ref<EntityStore> root,
                                @Nonnull final DunewyrmComponent worm,
                                final int bodyOrdinal) {

        final int segmentIndex = worm.bodyIndex(bodyOrdinal);
        if (segmentIndex < 0) return;

        final DunewyrmSegment removed = worm.getSegments().get(segmentIndex);
        DunewyrmSpawner.releaseSegmentAsDebris(store, removed);
        worm.getSegments().remove(segmentIndex);

        if (worm.bodyCount() == 0) {
            finishSnake(store, root, worm);
            return;
        }

        rebindVoxelIndices(store, worm);
        DunewyrmSpawner.layoutAlongPath(worm, store.getExternalData().getWorld().getChunkStore());
        DunewyrmSpawner.rebuildVisuals(store, root, worm);
        worm.setSegmentsDirty(true);
    }

    private static void split(@Nonnull final Store<EntityStore> store,
                              @Nonnull final Ref<EntityStore> root,
                              @Nonnull final DunewyrmComponent worm,
                              final int bodyOrdinal,
                              @Nonnull final Vector3d breakPos) {

        final List<DunewyrmSegment> frontBodies = new ArrayList<>();
        final List<DunewyrmSegment> rearBodies = new ArrayList<>();
        int seen = 0;
        DunewyrmSegment destroyed = null;
        for (final DunewyrmSegment segment : worm.getSegments()) {
            if (segment.getRole() != DunewyrmSegmentRole.BODY) continue;
            if (seen == bodyOrdinal) {
                destroyed = segment;
            } else if (seen < bodyOrdinal) {
                frontBodies.add(segment.copyMeta());
            } else {
                rearBodies.add(segment.copyMeta());
            }
            seen++;
        }

        if (destroyed != null) {
            DunewyrmSpawner.releaseSegmentAsDebris(store, destroyed);
        }

        final float yaw = worm.getYaw();
        final Vector3d headPos = new Vector3d(worm.getHeadPosition());
        final var encounterId = worm.getEncounterId();
        DunewyrmSpawner.destroyAllVoxels(store, worm);

        final DunewyrmEncounter encounter = DunewyrmEncounter.getOrCreate(encounterId);
        encounter.removeSnake();
        worm.setEncounterReleased(true);
        store.removeEntity(root, RemoveReason.REMOVE);

        // Separate along the break tangent so the two snakes do not stack on the same path.
        final double sideX = -DunewyrmAiSystem.forwardZ(yaw);
        final double sideZ = DunewyrmAiSystem.forwardX(yaw);
        final float sep = DunewyrmTuning.SPLIT_SEPARATION;

        if (!frontBodies.isEmpty()) {
            final Vector3d frontPos = new Vector3d(headPos).add(sideX * sep * 0.5, 0, sideZ * sep * 0.5);
            frontPos.y = headPos.y; // never inherit cobra height
            final DunewyrmSpawner.Result front = DunewyrmSpawner.spawn(
                store, frontPos, yaw, encounterId.hashCode(), false, encounterId, frontBodies);
            beginFlee(store, front, yaw + 0.4f);
        }
        if (!rearBodies.isEmpty()) {
            final Vector3d rearPos = new Vector3d(breakPos.x, headPos.y, breakPos.z);
            final Vector3d rearSample = rearBodies.get(0).getPosition();
            if (rearSample.lengthSquared() > 0.01) {
                rearPos.x = rearSample.x;
                rearPos.z = rearSample.z;
            }
            rearPos.y = headPos.y; // ground with the original head — not the reared segment Y
            rearPos.add(-sideX * sep * 0.5, 0, -sideZ * sep * 0.5);
            final float rearYaw = yaw + (float) Math.PI;
            final DunewyrmSpawner.Result rear = DunewyrmSpawner.spawn(
                store, rearPos, rearYaw, encounterId.hashCode() ^ 0xA5A5A5A5L, false,
                encounterId, rearBodies);
            beginFlee(store, rear, rearYaw);
        }
    }

    private static void beginFlee(@Nonnull final Store<EntityStore> store,
                                  @Nonnull final DunewyrmSpawner.Result result,
                                  final float fleeYaw) {
        if (!result.ok() || result.root() == null) return;
        final DunewyrmComponent live = store.getComponent(result.root(), DunewyrmComponent.getComponentType());
        if (live == null) return;
        live.clearCombatPose();
        final double groundY = live.getHeadPosition().y;
        for (final DunewyrmSegment segment : live.getSegments()) {
            segment.getPosition().y = groundY;
            segment.setPitch(0f);
        }
        live.setYaw(fleeYaw);
        live.setChargeYaw(fleeYaw);
        live.setFleeTimer(DunewyrmTuning.SPLIT_FLEE_DURATION);
        live.setState(DunewyrmState.FLAIL);
        live.addOrbitAngle((float) (Math.PI * 0.75));
        DunewyrmSpawner.layoutAlongPath(live, store.getExternalData().getWorld().getChunkStore());
    }

    private static void rebindVoxelIndices(@Nonnull final Store<EntityStore> store,
                                           @Nonnull final DunewyrmComponent worm) {
        for (int i = 0; i < worm.getSegments().size(); i++) {
            for (final Ref<EntityStore> voxel : worm.getSegments().get(i).getVoxels()) {
                if (voxel == null || !voxel.isValid()) continue;
                final var hit = store.getComponent(voxel, DunewyrmHitComponent.getComponentType());
                final var part = store.getComponent(voxel, DunewyrmPartComponent.getComponentType());
                if (hit != null) hit.setSegmentIndex(i);
                if (part != null) part.setSegmentIndex(i);
            }
        }
    }

    /** Force-kills one snake instance (used by {@code /titan kill}). */
    public static void forceKill(@Nonnull final Store<EntityStore> store,
                                 @Nonnull final Ref<EntityStore> root,
                                 @Nonnull final DunewyrmComponent worm) {
        final DunewyrmEncounter encounter = DunewyrmEncounter.getOrCreate(worm.getEncounterId());
        encounter.setRemainingBodyHealth(encounter.getRemainingBodyHealth() - worm.bodyHealth());
        if (encounter.getLivingSnakes() <= 1) {
            encounter.setRemainingBodyHealth(0f);
        }
        finishSnake(store, root, worm);
    }

    private static void finishSnake(@Nonnull final Store<EntityStore> store,
                                    @Nonnull final Ref<EntityStore> root,
                                    @Nonnull final DunewyrmComponent worm) {
        final Vector3d pos = new Vector3d(worm.getHeadPosition());
        // Detach voxels as debris before DYING — part sync deletes Dunewyrm parts on a dying owner.
        DunewyrmSpawner.releaseAllAsDebris(store, worm);
        worm.setState(DunewyrmState.DYING);
        worm.setEncounterReleased(true);
        DunewyrmHealthSyncSystem.dismiss(store, root, worm);
        final DunewyrmEncounter encounter = DunewyrmEncounter.getOrCreate(worm.getEncounterId());
        encounter.removeSnake();

        if (encounter.getRemainingBodyHealth() <= 0.5f && encounter.getLivingSnakes() <= 0
            && !encounter.isLootDropped()) {
            encounter.setLootDropped(true);
            final var variant = worm.getVariant();
            if (variant != null) {
                DunewyrmLoot.drop(store, variant, pos);
                TitanSound.play(store, variant.getDeathSound(), pos);
            }
            DunewyrmSiteSystem.markClearedForEncounter(store, worm.getEncounterId());
            DunewyrmSiteSystem.markClearedNear(store, pos);
            DunewyrmEncounter.remove(worm.getEncounterId());
        }

        if (root.isValid()) {
            store.removeEntity(root, RemoveReason.REMOVE);
        }
    }
}
