package com.hexvane.titan.dunewyrm;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Player lookups that walk the world's player list instead of the spatial entity index.
 *
 * <p>{@code TargetUtil.getAllEntitiesInCylinder} over a 50–100 block radius returns every entity in range
 * and the snake itself is a couple of thousand of them, so "is a player nearby" was costing a full scan of
 * the Dunewyrm's own body every tick. There are only ever a handful of players.
 */
final class DunewyrmPlayers {

    private DunewyrmPlayers() {
    }

    interface Visitor {
        void accept(@Nonnull Ref<EntityStore> player, @Nonnull Vector3d position);
    }

    /** Visits every valid player in the store's world with their current position. */
    static void forEach(@Nonnull final ComponentAccessor<EntityStore> accessor, @Nonnull final Visitor visitor) {
        for (final PlayerRef playerRef : accessor.getExternalData().getWorld().getPlayerRefs()) {
            final Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) continue;
            final TransformComponent transform = accessor.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) continue;
            visitor.accept(ref, transform.getPosition());
        }
    }

    /** Adds every player within {@code radius} (3D) of {@code centre} to {@code out}. */
    static void collectWithin(@Nonnull final ComponentAccessor<EntityStore> accessor,
                              @Nonnull final Vector3d centre,
                              final double radius,
                              @Nonnull final List<Ref<EntityStore>> out) {
        final double r2 = radius * radius;
        forEach(accessor, (player, position) -> {
            if (position.distanceSquared(centre) <= r2) out.add(player);
        });
    }

    static boolean anyWithin(@Nonnull final ComponentAccessor<EntityStore> accessor,
                             @Nonnull final Vector3d centre,
                             final double radius) {
        final double r2 = radius * radius;
        final boolean[] found = {false};
        forEach(accessor, (player, position) -> {
            if (!found[0] && position.distanceSquared(centre) <= r2) found[0] = true;
        });
        return found[0];
    }

    /** Nearest player within {@code radius} (3D) of {@code centre}, or null. */
    @Nullable
    static Ref<EntityStore> nearest(@Nonnull final ComponentAccessor<EntityStore> accessor,
                                    @Nonnull final Vector3d centre,
                                    final double radius) {
        final double[] best = {radius * radius};
        @SuppressWarnings("unchecked")
        final Ref<EntityStore>[] found = new Ref[1];
        forEach(accessor, (player, position) -> {
            final double d = position.distanceSquared(centre);
            if (d < best[0]) {
                best[0] = d;
                found[0] = player;
            }
        });
        return found[0];
    }
}
