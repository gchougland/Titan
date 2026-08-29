package com.hexvane.titan.command;

import com.hexvane.titan.entity.TitanComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Lookups shared by the {@code /titan} subcommands. */
final class TitanCommandUtil {

    private TitanCommandUtil() {
    }

    /** The titan root nearest to {@code origin} within {@code radius}, or {@code null}. */
    @Nullable
    static Ref<EntityStore> findNearest(@Nonnull final Store<EntityStore> store,
                                        @Nonnull final Vector3d origin,
                                        final double radius) {
        Ref<EntityStore> best = null;
        double bestDistance = Double.MAX_VALUE;

        for (final Ref<EntityStore> candidate : snapshotNearby(store, origin, radius)) {
            if (!candidate.isValid()) continue;
            if (store.getComponent(candidate, TitanComponent.getComponentType()) == null) continue;

            final var transform = store.getComponent(candidate, TransformComponent.getComponentType());
            if (transform == null) continue;

            final double distance = transform.getPosition().distanceSquared(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Spatial queries hand back a shared thread-local list, so anything that keeps iterating while doing
     * other lookups needs its own copy.
     */
    @Nonnull
    static List<Ref<EntityStore>> snapshotNearby(@Nonnull final Store<EntityStore> store,
                                                 @Nonnull final Vector3d origin,
                                                 final double radius) {
        return new ArrayList<>(TargetUtil.getAllEntitiesInSphere(origin, radius, store));
    }
}
