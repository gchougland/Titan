package com.hexvane.titan.spawn;

import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * The block contents of a prefab, flattened for spawning.
 */
public final class PrefabVoxels {

    /**
     * One block of the prefab.
     *
     * @param surface   at least one face is exposed
     * @param standable nothing sits directly above, so this block's top face is one a player could land on
     */
    public record Voxel(int x, int y, int z, @Nonnull String blockKey, boolean surface, boolean standable) {
    }

    @Nonnull
    private final List<Voxel> voxels;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public PrefabVoxels(@Nonnull final List<Voxel> voxels, final int minX, final int minY, final int minZ,
                        final int maxX, final int maxY, final int maxZ) {
        this.voxels = voxels;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    @Nonnull
    public List<Voxel> getVoxels() {
        return voxels;
    }

    public boolean isEmpty() {
        return voxels.isEmpty();
    }

    public int size() {
        return voxels.size();
    }

    /**
     * Default pivot for a bone that does not declare one: the bottom centre of the prefab's bounds, which
     * is where a limb segment naturally hinges.
     */
    @Nonnull
    public Vector3d defaultPivot() {
        return new Vector3d(
            (minX + maxX + 1) * 0.5,
            minY,
            (minZ + maxZ + 1) * 0.5
        );
    }

    /** Centre of the prefab's bounds, used for spreading debris outwards on death. */
    @Nonnull
    public Vector3d center() {
        return new Vector3d(
            (minX + maxX + 1) * 0.5,
            (minY + maxY + 1) * 0.5,
            (minZ + maxZ + 1) * 0.5
        );
    }
}
