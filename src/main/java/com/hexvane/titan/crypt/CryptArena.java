package com.hexvane.titan.crypt;

import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/** Rigid authored room frame: +right is source +Z, +forward is source +X. Units are blocks. */
public final class CryptArena {
    private final Vector3d center;
    private final float yaw;
    private final double sin;
    private final double cos;

    public CryptArena(@Nonnull Vector3d center, float yaw) {
        this.center = new Vector3d(center);
        this.yaw = yaw;
        sin = Math.sin(yaw);
        cos = Math.cos(yaw);
    }

    public CryptArena(@Nonnull Vector3d center) { this(center, 0f); }
    public Vector3d center() { return new Vector3d(center); }
    public float yaw() { return yaw; }

    /** y=0 is the walkable main floor; pit bottom is y=-15. */
    public Vector3d point(double right, double up, double forward) {
        return new Vector3d(center.x + forward * cos + right * sin,
            center.y + up, center.z - forward * sin + right * cos);
    }

    /** Inverse of point(), returned in (right, up, forward) order. */
    public Vector3d local(@Nonnull Vector3d world) {
        double dx = world.x - center.x, dz = world.z - center.z;
        return new Vector3d(dx * sin + dz * cos, world.y - center.y, dx * cos - dz * sin);
    }

    /**
     * Covers the entire authored room, pit and wall alcoves with four blocks of horizontal leeway.
     * Source room cell edges span x=-66..8, z=-41..38, y=0..47; the surface entrance is at y=75.
     * The small landing margin prevents corner/alcove movement from being mistaken for a wipe.
     */
    public boolean contains(@Nonnull Vector3d world) {
        Vector3d p = local(world);
        return p.x >= -42.5 && p.x <= 44.5 && p.y >= -22 && p.y <= 32
            && p.z >= -19.5 && p.z <= 62.5;
    }

    public Vector3d sourcePoint(double x, double y, double z) {
        return point(z + 2.5, y - 18, x + 50.5);
    }

    public Vector3d coffin() { return sourcePoint(-21.5, 20.8, -2); }
    /** Center of the two-block coffin, above its one-block-high Coffin hitbox. */
    public Vector3d rewardPoint() { return sourcePoint(-21.5, 20 + 1 + .2, -2); }
    public Vector3i coffinBlock() {
        Vector3d p = sourcePoint(-21.5, 20.5, -1.5);
        return new Vector3i((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
    }
    public Vector3d podium() { return point(0, 2, 29); }
    /** Sideways head landing between the pit lip and the central coffin podium. */
    public Vector3d stunnedHead() { return point(0, 1.7, 24); }
    public Vector3d pitBottom() { return point(0, -15, 0); }
    public Vector3d camera() { return point(0, 18, 49); }
    public Vector3d cameraTarget() { return point(0, 9, 3); }
    public Vector3d entrance() { return sourcePoint(64.5, 75, -1.5); }
    /** World paste origin for the normalized SkeletonDungeon_Site prefab. */
    public Vector3d prefabOrigin() { return sourcePoint(64, 74, -2); }

    public List<Vector3d> grabs() {
        return List.of(sourcePoint(-39.5, 18, -15.5), sourcePoint(-39.5, 18, 11.5));
    }

    public List<Vector3d> flames() {
        List<Vector3d> result = new ArrayList<>(28);
        for (int x : new int[]{-37, -32, -26, -20, -14, -8, -2}) {
            for (int z : new int[]{-29, -12, 7, 24}) result.add(sourcePoint(x + .5, 18, z + .5));
        }
        return List.copyOf(result);
    }

    public List<Vector3d> minions() {
        return List.of(sourcePoint(-26.5, 20, -31.5), sourcePoint(-23.5, 19, -32.5),
            sourcePoint(-23.5, 21, 22.5), sourcePoint(-21.5, 21, 24.5),
            sourcePoint(-5.5, 20, -17.5), sourcePoint(-2.5, 20, -20.5),
            sourcePoint(-2.5, 20, 29.5));
    }
}
