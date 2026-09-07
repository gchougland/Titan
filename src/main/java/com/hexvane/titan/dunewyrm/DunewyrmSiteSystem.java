package com.hexvane.titan.dunewyrm;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.prefab.PrefabCopyableComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * Turns a worldgen Dunewyrm structure marker into a living snake, once.
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
                    if (hasSnakeNearby(store, position)) return;

                    final long seed = siteSeed(position);
                    final DunewyrmSpawner.Result result =
                        DunewyrmSpawner.spawn(store, position, yaw, seed, false);
                    if (!result.ok()) {
                        LOGGER.at(Level.WARNING).log(
                            "Dunewyrm site at %s could not spawn: %s", position, result.error());
                    }
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

    private static boolean hasSnakeNearby(@Nonnull final Store<EntityStore> store,
                                          @Nonnull final Vector3d position) {
        final boolean[] found = {false};
        store.forEachChunk(
            Archetype.of(DunewyrmComponent.getComponentType(), TransformComponent.getComponentType()),
            (chunk, ignored) -> {
                if (found[0]) return;
                for (int i = 0; i < chunk.size(); i++) {
                    final var transform = chunk.getComponent(i, TransformComponent.getComponentType());
                    if (transform == null) continue;
                    if (transform.getPosition().distanceSquared(position) <= SNAKE_RADIUS * SNAKE_RADIUS) {
                        found[0] = true;
                        return;
                    }
                }
            });
        return found[0];
    }

    private static long siteSeed(@Nonnull final Vector3d position) {
        final long x = Double.doubleToLongBits(Math.floor(position.x));
        final long z = Double.doubleToLongBits(Math.floor(position.z));
        return x * 73428767L ^ z * 9127391L ^ 0xD01E5A7EL;
    }
}
