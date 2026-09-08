package com.hexvane.titan.combat;

import com.hexvane.titan.config.TitanConfig;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Scales titan / Dunewyrm fight HP by how many players are in the encounter radius.
 *
 * <p>Encounter Manager only tracks members for the boss bar and music; this is the combat-side equivalent.
 */
public final class TitanEncounterScale {

    private TitanEncounterScale() {
    }

    /** Counts players in a cylinder around {@code centre}. Always at least 1. */
    public static int countPlayers(@Nonnull final Store<EntityStore> store,
                                   @Nonnull final Vector3d centre,
                                   final double radius) {
        int n = 0;
        for (final var candidate : TargetUtil.getAllEntitiesInCylinder(centre, radius, radius, store)) {
            if (store.getComponent(candidate, Player.getComponentType()) != null) n++;
        }
        return Math.max(1, n);
    }

    /**
     * Health multiplier for {@code playerCount} fighters.
     *
     * <p>One player → 1.0; each extra player adds {@link TitanConfig#getPlayerHealthScalePerExtra()}.
     */
    public static float healthScale(final int playerCount) {
        final int extra = Math.max(0, playerCount - 1);
        return 1f + extra * TitanConfig.get().getPlayerHealthScalePerExtra();
    }

    public static float healthScaleNear(@Nonnull final Store<EntityStore> store,
                                        @Nonnull final Vector3d centre,
                                        final double radius) {
        return healthScale(countPlayers(store, centre, radius));
    }
}
