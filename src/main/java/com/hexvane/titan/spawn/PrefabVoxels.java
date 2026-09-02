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
     * @param rotation  the block's own orientation, as a {@code RotationTuple} index
     * @param surface   at least one face is not hidden behind a solid cube, so this block is visible
     * @param standable nothing sits directly above, so a player could land on this block's top face
     */
    public record Voxel(int x, int y, int z, @Nonnull String blockKey, int rotation, boolean surface,
                        boolean standable) {
    }

    @Nonnull
    private final List<Voxel> voxels;
    private final int surfaceSize;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public PrefabVoxels(@Nonnull final List<Voxel> voxels, final int minX, final int minY, final int minZ,
                        final int maxX, final int maxY, final int maxZ) {
        this.voxels = voxels;
        int surface = 0;
        for (final Voxel voxel : voxels) {
            if (voxel.surface()) surface++;
        }
        this.surfaceSize = surface;
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

    /** @return how many voxels have at least one exposed face; a hollow bone spawns only these. */
    public int surfaceSize() {
        return surfaceSize;
    }

    /**
     * @return default pivot for a bone that does not declare one: the bottom centre of the prefab's bounds,
     *         where a limb segment hinges
     */
    @Nonnull
    public Vector3d defaultPivot() {
        return new Vector3d(
            (minX + maxX + 1) * 0.5,
            minY,
            (minZ + maxZ + 1) * 0.5
        );
    }

    /** @return centre of the prefab's bounds, used to spread debris outwards on death. */
    @Nonnull
    public Vector3d center() {
        return new Vector3d(
            (minX + maxX + 1) * 0.5,
            (minY + maxY + 1) * 0.5,
            (minZ + maxZ + 1) * 0.5
        );
    }
}
