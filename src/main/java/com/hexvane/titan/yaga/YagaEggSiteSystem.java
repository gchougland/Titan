package com.hexvane.titan.yaga;

import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.spawn.ColliderMode;
import com.hexvane.titan.spawn.TitanSpawner;
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
 * Turns a worldgen nest marker into a Baba Yaga egg, once.
 *
 * <p>The nest structure pastes nest blocks and this marker. The egg itself is a multi-entity titan and
 * cannot live in the prefab, so this system builds it when the marker appears and rebuilds it after chunk
 * unload until the nest has been hatched. Spawn runs on {@code world.execute}, never as a store write from
 * a tick.
 */
public final class YagaEggSiteSystem {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** How far an egg may sit from its marker and still count as occupying the nest. */
    private static final double EGG_RADIUS = 6.0;

    private YagaEggSiteSystem() {
    }

    @Nonnull
    private static Query<EntityStore> siteQuery() {
        return Archetype.of(
            YagaEggSiteComponent.getComponentType(),
            TransformComponent.getComponentType());
    }

    /** Ensures worldgen markers are network-safe and invisible. */
    public static final class EnsureComponents extends HolderSystem<EntityStore> {

        @Nonnull
        private final Query<EntityStore> query = YagaEggSiteComponent.getComponentType();

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

    /** Schedules egg assembly when a nest marker enters the world. */
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

            final var site = store.getComponent(ref, YagaEggSiteComponent.getComponentType());
            final var transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (site == null || transform == null) return;
            if (site.isHatched() || site.isPending()) return;

            site.setPending(true);
            final Vector3d position = new Vector3d(transform.getPosition());
            final float yaw = transform.getRotation().yaw();
            final String variant = site.getVariant();
            final var world = store.getExternalData().getWorld();

            world.execute(() -> {
                try {
                    if (!ref.isValid()) return;
                    final var live = store.getComponent(ref, YagaEggSiteComponent.getComponentType());
                    if (live == null || live.isHatched()) return;
                    if (hasEggNearby(store, position)) return;

                    final long seed = nestSeed(position);
                    final TitanSpawner.Result result = TitanSpawner.spawn(
                        store, variant, position, yaw, ColliderMode.DEFAULT, seed, false);
                    if (!result.ok()) {
                        LOGGER.at(Level.WARNING).log(
                            "Yaga nest at %s could not spawn '%s': %s", position, variant, result.error());
                    }
                } finally {
                    if (ref.isValid()) {
                        final var live = store.getComponent(ref, YagaEggSiteComponent.getComponentType());
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
     * Marks the nest nearest to {@code position} as hatched so it never asks for another egg.
     *
     * <p>Must run on the world thread outside ticking (same as the spawn that follows a hatch).
     */
    public static void markHatchedNear(@Nonnull final Store<EntityStore> store,
                                       @Nonnull final Vector3d position) {

        final double radiusSq = EGG_RADIUS * EGG_RADIUS;
        final Object[] best = new Object[1];
        final double[] bestDist = {radiusSq};

        store.forEachChunk(siteQuery(), (chunk, commandBuffer) -> {
            for (int index = 0; index < chunk.size(); index++) {
                final var site = chunk.getComponent(index, YagaEggSiteComponent.getComponentType());
                final var transform = chunk.getComponent(index, TransformComponent.getComponentType());
                if (site == null || transform == null || site.isHatched()) continue;

                final double dist = transform.getPosition().distanceSquared(position);
                if (dist < bestDist[0]) {
                    bestDist[0] = dist;
                    best[0] = chunk.getReferenceTo(index);
                }
            }
        });

        if (best[0] == null) return;
        @SuppressWarnings("unchecked")
        final Ref<EntityStore> ref = (Ref<EntityStore>) best[0];
        if (!ref.isValid()) return;
        final var site = store.getComponent(ref, YagaEggSiteComponent.getComponentType());
        if (site != null) site.setHatched(true);
    }

    private static long nestSeed(@Nonnull final Vector3d position) {
        final long x = Double.doubleToLongBits(Math.floor(position.x));
        final long z = Double.doubleToLongBits(Math.floor(position.z));
        return x * 31L + z;
    }

    private static boolean hasEggNearby(@Nonnull final Store<EntityStore> store,
                                        @Nonnull final Vector3d position) {

        final double radiusSq = EGG_RADIUS * EGG_RADIUS;
        final boolean[] found = {false};

        store.forEachChunk(
            Archetype.of(TitanComponent.getComponentType(), TransformComponent.getComponentType()),
            (chunk, commandBuffer) -> {
                if (found[0]) return;
                for (int index = 0; index < chunk.size(); index++) {
                    final var titan = chunk.getComponent(index, TitanComponent.getComponentType());
                    final var transform = chunk.getComponent(index, TransformComponent.getComponentType());
                    if (titan == null || transform == null) continue;
                    if (YagaComponent.Stage.of(titan.getVariantId()) != YagaComponent.Stage.EGG) continue;
                    if (transform.getPosition().distanceSquared(position) <= radiusSq) {
                        found[0] = true;
                        return;
                    }
                }
            });

        return found[0];
    }
}
