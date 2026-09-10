package com.hexvane.titan.spawn;

import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.ArrayList;
import java.util.TreeMap;

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

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    /** Group equal-width horizontal layers so a solid core follows a stepped limb's taper. */
    public List<PrefabVoxels> horizontalSlices() {
        var layers = new TreeMap<Integer, List<Voxel>>();
        for (var voxel : voxels) layers.computeIfAbsent(voxel.y(), ignored -> new ArrayList<>()).add(voxel);
        var result = new ArrayList<PrefabVoxels>();
        var band = new ArrayList<Voxel>();
        int x0 = 0, x1 = 0, z0 = 0, z1 = 0, y0 = 0, y1 = 0;
        for (var entry : layers.entrySet()) {
            int nextX0 = Integer.MAX_VALUE, nextX1 = Integer.MIN_VALUE;
            int nextZ0 = Integer.MAX_VALUE, nextZ1 = Integer.MIN_VALUE;
            for (var voxel : entry.getValue()) {
                nextX0 = Math.min(nextX0, voxel.x()); nextX1 = Math.max(nextX1, voxel.x());
                nextZ0 = Math.min(nextZ0, voxel.z()); nextZ1 = Math.max(nextZ1, voxel.z());
            }
            if (!band.isEmpty() && (entry.getKey() != y1 + 1 || nextX0 != x0 || nextX1 != x1
                || nextZ0 != z0 || nextZ1 != z1)) {
                result.add(new PrefabVoxels(band, x0, y0, z0, x1, y1, z1));
                band = new ArrayList<>();
            }
            if (band.isEmpty()) {
                x0 = nextX0; x1 = nextX1; z0 = nextZ0; z1 = nextZ1; y0 = entry.getKey();
            }
            y1 = entry.getKey(); band.addAll(entry.getValue());
        }
        if (!band.isEmpty()) result.add(new PrefabVoxels(band, x0, y0, z0, x1, y1, z1));
        return result;
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
