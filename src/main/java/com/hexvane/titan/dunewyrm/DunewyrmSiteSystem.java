package com.hexvane.titan.dunewyrm;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.prefab.PrefabCopyableComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Turns a worldgen Dunewyrm structure marker into a living snake, and keeps one healthy snake around
 * uncleared sites after unload / corruption.
 */
public final class DunewyrmSiteSystem {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final double SNAKE_RADIUS = 40.0;

    private DunewyrmSiteSystem() {
    }

    @Nonnull
    private static Query<EntityStore> siteQuery() {
        return Archetype.of(
            DunewyrmSiteComponent.getComponentType(),
            TransformComponent.getComponentType());
    }

    public static final class EnsureComponents extends HolderSystem<EntityStore> {

        @Nonnull
        private final Query<EntityStore> query = DunewyrmSiteComponent.getComponentType();

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public void onEntityAdd(@Nonnull final Holder<EntityStore> holder,
                                @Nonnull final AddReason reason,
                                @Nonnull final Store<EntityStore> store) {
            final var archetype = holder.getArchetype();
            assert archetype != null;
            final var networkId = NetworkId.getComponentType();
            if (!archetype.contains(networkId)) {
                holder.addComponent(networkId, new NetworkId(store.getExternalData().takeNextNetworkId()));
            }
            holder.ensureComponent(Intangible.getComponentType());
            holder.ensureComponent(PrefabCopyableComponent.getComponentType());
        }

        @Override
        public void onEntityRemoved(@Nonnull final Holder<EntityStore> holder,
                                    @Nonnull final RemoveReason reason,
                                    @Nonnull final Store<EntityStore> store) {
        }
    }

    public static final class SpawnOnAdd extends RefSystem<EntityStore> {

        @Nonnull
        private final Query<EntityStore> query = siteQuery();

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public void onEntityAdded(@Nonnull final Ref<EntityStore> ref,
                                  @Nonnull final AddReason reason,
                                  @Nonnull final Store<EntityStore> store,
                                  @Nonnull final CommandBuffer<EntityStore> commandBuffer) {

            final var site = store.getComponent(ref, DunewyrmSiteComponent.getComponentType());
            final var transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (site == null || transform == null) return;
            if (site.isCleared() || site.isPending()) return;

            site.setPending(true);
            final Vector3d position = new Vector3d(transform.getPosition());
            final float yaw = transform.getRotation().yaw();
            final var world = store.getExternalData().getWorld();

            world.execute(() -> {
                try {
                    if (!ref.isValid()) return;
                    final var live = store.getComponent(ref, DunewyrmSiteComponent.getComponentType());
                    if (live == null || live.isCleared()) return;
                    if (adoptHealthySnake(store, live, position)) return;

                    despawnBrokenNearby(store, position);
                    spawnForSite(store, live, position, yaw, "site spawn");
                } finally {
                    if (ref.isValid()) {
                        final var live = store.getComponent(ref, DunewyrmSiteComponent.getComponentType());
                        if (live != null) live.setPending(false);
                    }
                }
            });
        }

        @Override
        public void onEntityRemove(@Nonnull final Ref<EntityStore> ref,
                                   @Nonnull final RemoveReason reason,
                                   @Nonnull final Store<EntityStore> store,
                                   @Nonnull final CommandBuffer<EntityStore> commandBuffer) {
        }
    }

    /**
     * After unload the site marker may stay loaded while the NonSerialized snake is gone — or the snake
     * may linger as a half-empty husk. Periodically restore a full-health correct snake when players are
     * nearby, and reap distant snakes so the next visit is clean.
     */
    public static final class Maintain extends EntityTickingSystem<EntityStore> {

        @Nonnull
        private final Query<EntityStore> query = siteQuery();

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

            final var site = archetypeChunk.getComponent(index, DunewyrmSiteComponent.getComponentType());
            final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
            if (site == null || transform == null || site.isCleared() || site.isPending()) return;

            site.setMaintainTimer(site.getMaintainTimer() - dt);
            if (site.getMaintainTimer() > 0f) return;
            site.setMaintainTimer(DunewyrmTuning.SITE_MAINTAIN_INTERVAL);

            final Ref<EntityStore> siteRef = archetypeChunk.getReferenceTo(index);
            final Vector3d position = new Vector3d(transform.getPosition());
            final float yaw = transform.getRotation().yaw();
            final boolean playersNearSite = hasPlayerNearby(store, position, DunewyrmTuning.SITE_KEEP_RADIUS);

            // A site owns its snake by encounter, not by distance: the fight orbits, hunts and charges well
            // past any radius around the marker, and a split leaves two snakes. Looking for "a snake within
            // 40 blocks" spawned a fresh one every time the fight drifted away.
            final List<Ref<EntityStore>> snakes = new ArrayList<>();
            collectSiteSnakes(store, site, position, snakes);

            if (!snakes.isEmpty()) {
                boolean anyHealthy = false;
                boolean playersNearSnake = false;
                for (final Ref<EntityStore> snake : snakes) {
                    final DunewyrmComponent worm = store.getComponent(snake, DunewyrmComponent.getComponentType());
                    final var snakeTransform = store.getComponent(snake, TransformComponent.getComponentType());
                    if (worm == null) continue;
                    if (!isVisuallyBroken(worm)) anyHealthy = true;
                    if (snakeTransform != null
                        && hasPlayerNearby(store, snakeTransform.getPosition(), DunewyrmTuning.SITE_KEEP_RADIUS)) {
                        playersNearSnake = true;
                    }
                }

                if (anyHealthy) {
                    // Fight is alive and well. Only reap it if everyone has left, so the next visit is clean.
                    if (!playersNearSite && !playersNearSnake) {
                        site.setPending(true);
                        store.getExternalData().getWorld().execute(() -> {
                            try {
                                for (final Ref<EntityStore> snake : snakes) despawnSnake(store, snake);
                            } finally {
                                clearPending(siteRef, store);
                            }
                        });
                    }
                    return;
                }

                // Every snake of this encounter is a husk (unload / corruption): rebuild one clean snake.
                final boolean rebuild = playersNearSite || playersNearSnake;
                site.setPending(true);
                store.getExternalData().getWorld().execute(() -> {
                    try {
                        if (!siteRef.isValid()) return;
                        final var live = store.getComponent(siteRef, DunewyrmSiteComponent.getComponentType());
                        if (live == null || live.isCleared()) return;
                        for (final Ref<EntityStore> snake : snakes) despawnSnake(store, snake);
                        if (rebuild) spawnForSite(store, live, position, yaw, "site rebuild");
                    } finally {
                        clearPending(siteRef, store);
                    }
                });
                return;
            }

            // No snake of ours is loaded. If the fight already ended, the site is done; otherwise the snake
            // was unloaded mid-fight (or never built) and comes back at full health once someone is near.
            final UUID encounterId = site.getEncounterId();
            if (encounterId != null && DunewyrmEncounter.getOrCreate(encounterId).isLootDropped()) {
                site.setCleared(true);
                return;
            }
            if (!playersNearSite) return;

            site.setPending(true);
            store.getExternalData().getWorld().execute(() -> {
                try {
                    if (!siteRef.isValid()) return;
                    final var live = store.getComponent(siteRef, DunewyrmSiteComponent.getComponentType());
                    if (live == null || live.isCleared()) return;
                    if (adoptHealthySnake(store, live, position)) return;
                    despawnBrokenNearby(store, position);
                    spawnForSite(store, live, position, yaw, "site maintain");
                } finally {
                    clearPending(siteRef, store);
                }
            });
        }
    }

    /** Spawns the site's snake and remembers which encounter it belongs to. */
    private static void spawnForSite(@Nonnull final Store<EntityStore> store,
                                     @Nonnull final DunewyrmSiteComponent site,
                                     @Nonnull final Vector3d position,
                                     final float yaw,
                                     @Nonnull final String what) {
        final DunewyrmSpawner.Result result =
            DunewyrmSpawner.spawn(store, position, yaw, siteSeed(position), false);
        if (!result.ok() || result.root() == null) {
            LOGGER.at(Level.WARNING).log("Dunewyrm %s at %s failed: %s", what, position, result.error());
            return;
        }
        final DunewyrmComponent worm = store.getComponent(result.root(), DunewyrmComponent.getComponentType());
        site.setEncounterId(worm != null ? worm.getEncounterId() : null);
    }

    /**
     * If a healthy snake already lives at this site (a command spawn, or a site whose marker was reloaded
     * while its snake stayed), take ownership of it instead of spawning a second one.
     */
    private static boolean adoptHealthySnake(@Nonnull final Store<EntityStore> store,
                                             @Nonnull final DunewyrmSiteComponent site,
                                             @Nonnull final Vector3d position) {
        final List<Ref<EntityStore>> snakes = new ArrayList<>();
        collectSiteSnakes(store, site, position, snakes);
        for (final Ref<EntityStore> snake : snakes) {
            final DunewyrmComponent worm = store.getComponent(snake, DunewyrmComponent.getComponentType());
            if (worm == null || isVisuallyBroken(worm)) continue;
            site.setEncounterId(worm.getEncounterId());
            return true;
        }
        return false;
    }

    /**
     * Every live snake belonging to this site: all snakes of its encounter wherever they are, or, for a
     * site that has not spawned yet, any snake still parked near the marker.
     */
    private static void collectSiteSnakes(@Nonnull final Store<EntityStore> store,
                                          @Nonnull final DunewyrmSiteComponent site,
                                          @Nonnull final Vector3d position,
                                          @Nonnull final List<Ref<EntityStore>> out) {
        final UUID encounterId = site.getEncounterId();
        store.forEachChunk(
            Archetype.of(DunewyrmComponent.getComponentType(), TransformComponent.getComponentType()),
            (chunk, ignored) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    final var worm = chunk.getComponent(i, DunewyrmComponent.getComponentType());
                    final var transform = chunk.getComponent(i, TransformComponent.getComponentType());
                    if (worm == null || transform == null || worm.getState() == DunewyrmState.DYING) continue;
                    final boolean ours;
                    if (encounterId != null) {
                        ours = encounterId.equals(worm.getEncounterId());
                    } else {
                        // Marker reloaded (or never spawned): claim a snake parked here, or one that was
                        // spawned here and has since wandered off after the player.
                        final double r2 = SNAKE_RADIUS * SNAKE_RADIUS;
                        ours = transform.getPosition().distanceSquared(position) <= r2
                            || worm.getHome().distanceSquared(position) <= r2;
                    }
                    if (ours) out.add(chunk.getReferenceTo(i));
                }
            });
    }

    /** Marks every site that owns this encounter as cleared, wherever the last snake happened to die. */
    public static void markClearedForEncounter(@Nonnull final Store<EntityStore> store,
                                               @Nonnull final UUID encounterId) {
        store.forEachChunk(
            Archetype.of(DunewyrmSiteComponent.getComponentType()),
            (chunk, ignored) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    final var site = chunk.getComponent(i, DunewyrmSiteComponent.getComponentType());
                    if (site != null && encounterId.equals(site.getEncounterId())) site.setCleared(true);
                }
            });
    }

    public static void markClearedNear(@Nonnull final Store<EntityStore> store,
                                       @Nonnull final Vector3d position) {
        store.forEachChunk(
            Archetype.of(DunewyrmSiteComponent.getComponentType(), TransformComponent.getComponentType()),
            (chunk, ignored) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    final var site = chunk.getComponent(i, DunewyrmSiteComponent.getComponentType());
                    final var transform = chunk.getComponent(i, TransformComponent.getComponentType());
                    if (site == null || transform == null || site.isCleared()) continue;
                    if (transform.getPosition().distanceSquared(position) <= SNAKE_RADIUS * SNAKE_RADIUS) {
                        site.setCleared(true);
                    }
                }
            });
    }

    private static void clearPending(@Nonnull final Ref<EntityStore> siteRef,
                                     @Nonnull final Store<EntityStore> store) {
        if (!siteRef.isValid()) return;
        final var live = store.getComponent(siteRef, DunewyrmSiteComponent.getComponentType());
        if (live != null) live.setPending(false);
    }

    private static void despawnSnake(@Nonnull final Store<EntityStore> store,
                                     @Nonnull final Ref<EntityStore> root) {
        if (!root.isValid()) return;
        final DunewyrmComponent worm = store.getComponent(root, DunewyrmComponent.getComponentType());
        if (worm != null) {
            DunewyrmSpawner.destroyAllVoxels(store, worm);
        }
        if (root.isValid()) {
            store.removeEntity(root, RemoveReason.REMOVE);
        }
    }

    private static void despawnBrokenNearby(@Nonnull final Store<EntityStore> store,
                                            @Nonnull final Vector3d position) {
        store.forEachChunk(
            Archetype.of(DunewyrmComponent.getComponentType(), TransformComponent.getComponentType()),
            (chunk, ignored) -> {
                for (int i = chunk.size() - 1; i >= 0; i--) {
                    final var worm = chunk.getComponent(i, DunewyrmComponent.getComponentType());
                    final var transform = chunk.getComponent(i, TransformComponent.getComponentType());
                    if (worm == null || transform == null) continue;
                    if (transform.getPosition().distanceSquared(position) > SNAKE_RADIUS * SNAKE_RADIUS) {
                        continue;
                    }
                    if (!isVisuallyBroken(worm)) continue;
                    final Ref<EntityStore> root = chunk.getReferenceTo(i);
                    despawnSnake(store, root);
                }
            });
    }

    /** True when half or more of the structural segments have lost their voxels (unload / corruption). */
    static boolean isVisuallyBroken(@Nonnull final DunewyrmComponent worm) {
        int checked = 0;
        int empty = 0;
        for (final DunewyrmSegment segment : worm.getSegments()) {
            final DunewyrmSegmentRole role = segment.getRole();
            if (role == DunewyrmSegmentRole.JAW || role == DunewyrmSegmentRole.TONGUE) continue;
            checked++;
            int valid = 0;
            for (final Ref<EntityStore> voxel : segment.getVoxels()) {
                if (voxel != null && voxel.isValid()) valid++;
            }
            if (valid < 4) empty++;
        }
        return checked > 0 && empty >= Math.max(2, (checked + 1) / 2);
    }

    private static boolean hasPlayerNearby(@Nonnull final Store<EntityStore> store,
                                           @Nonnull final Vector3d position,
                                           final double radius) {
        return DunewyrmPlayers.anyWithin(store, position, radius);
    }

    private static long siteSeed(@Nonnull final Vector3d position) {
        final long x = Double.doubleToLongBits(Math.floor(position.x));
        final long z = Double.doubleToLongBits(Math.floor(position.z));
        return x * 73428767L ^ z * 9127391L ^ 0xD01E5A7EL;
    }
}
