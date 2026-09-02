package com.hexvane.titan.spawn;

import com.hexvane.titan.asset.TitanBoneDef;
import com.hexvane.titan.spawn.PrefabVoxels.Voxel;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Which of a bone's voxels get hard collision.
 *
 * <p>Every collider is an entity the client resolves the player against each frame while it moves, so this
 * is the main dial for how expensive a titan is to stand next to. Settable per spawn so a misbehaving
 * client can be bisected without a rebuild.
 */
public enum ColliderMode {

    /** No hard collision at all; the titan is scenery the player walks through. Diagnostic. */
    NONE,

    /** Each bone picks its own face set, per {@link TitanBoneDef#isColliderAllFaces()}. */
    AUTO,

    /**
     * Force top-faces-only on every bone. Diagnostic: it is all a bone that stays horizontal needs, so a
     * limb that turns unclimbable under it must be rotating.
     */
    TOP,

    /** Force every exposed face on every bone. Solid, and the most expensive. Diagnostic. */
    ALL;

    /** Default for spawns that do not ask for anything else. */
    @Nonnull
    public static final ColliderMode DEFAULT = AUTO;

    public boolean accepts(@Nonnull final Voxel voxel, @Nonnull final TitanBoneDef bone) {
        return switch (this) {
            case NONE -> false;
            case AUTO -> bone.isColliderAllFaces() ? voxel.surface() : voxel.standable();
            case TOP -> voxel.standable();
            case ALL -> voxel.surface();
        };
    }

    /** Parses a command argument. Returns {@code null} for anything unrecognised. */
    @Nullable
    public static ColliderMode parse(@Nullable final String value) {
        if (value == null) return null;
        for (final ColliderMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) return mode;
        }
        return null;
    }

    @Nonnull
    public String argument() {
        return name().toLowerCase(Locale.ROOT);
    }
}
