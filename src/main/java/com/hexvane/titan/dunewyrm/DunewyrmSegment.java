package com.hexvane.titan.dunewyrm;

import com.hexvane.titan.combat.TitanPoolHitGate;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * One logical piece of a Dunewyrm: a transform plus optional body HP and voxel refs.
 *
 * <p>Not an ECS component — stored on {@link DunewyrmComponent} so the chain can grow and shrink without
 * reshuffling entity archetypes.
 */
public final class DunewyrmSegment {

    @Nonnull
    private DunewyrmSegmentRole role = DunewyrmSegmentRole.BODY;
    @Nonnull
    private String prefabKey = "";
    private boolean mirrored;
    private float health;
    private float maxHealth;

    @Nonnull
    private final Vector3d position = new Vector3d();
    private float yaw;
    private float pitch;
    private double viewerDistance;
    private boolean syncThisTick = true;
    private double drapeSampleX = Double.NaN;
    private double drapeSampleZ = Double.NaN;
    private double drapeSampleY = Double.NaN;
    private double smoothGroundY = Double.NaN;

    @Nonnull
    private final List<Ref<EntityStore>> voxels = new ArrayList<>();
    @Nonnull
    private final TitanPoolHitGate hitGate = new TitanPoolHitGate();

    public DunewyrmSegment() {
    }

    public DunewyrmSegment(@Nonnull final DunewyrmSegmentRole role,
                           @Nonnull final String prefabKey,
                           final boolean mirrored,
                           final float health) {
        this.role = role;
        this.prefabKey = prefabKey;
        this.mirrored = mirrored;
        this.health = health;
        this.maxHealth = health;
    }

    @Nonnull
    public DunewyrmSegmentRole getRole() {
        return role;
    }

    public void setRole(@Nonnull final DunewyrmSegmentRole role) {
        this.role = role;
    }

    @Nonnull
    public String getPrefabKey() {
        return prefabKey;
    }

    public void setPrefabKey(@Nonnull final String prefabKey) {
        this.prefabKey = prefabKey;
    }

    public boolean isMirrored() {
        return mirrored;
    }

    public void setMirrored(final boolean mirrored) {
        this.mirrored = mirrored;
    }

    public float getHealth() {
        return health;
    }

    public float getMaxHealth() {
        return maxHealth;
    }

    public void setHealth(final float health) {
        this.health = health;
    }

    public void setMaxHealth(final float maxHealth) {
        this.maxHealth = maxHealth;
    }

    /**
     * Returns true when this is the first voxel of a swing to land on this segment this tick.
     *
     * <p>Wide melee hits many surface voxels at once; without this gate each one would drain the shared
     * pool independently and a single swing could delete a segment.
     */
    public boolean acceptHit(final int attackerIndex, final long tick) {
        return hitGate.accept(attackerIndex, tick);
    }

    /** Returns true when the segment's pool is empty. */
    public synchronized boolean absorb(final float amount) {
        if (!role.hasHealth()) return false;
        health = Math.max(0f, health - amount);
        return health <= 0f;
    }

    @Nonnull
    public Vector3d getPosition() {
        return position;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(final float yaw) {
        this.yaw = yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(final float pitch) {
        this.pitch = pitch;
    }

    /** Distance to the nearest player, refreshed by the AI tick; lets far segments sync less often. */
    public double getViewerDistance() {
        return viewerDistance;
    }

    public void setViewerDistance(final double viewerDistance) {
        this.viewerDistance = viewerDistance;
    }

    /** Whether this segment's voxels pose this tick (false only for distant segments on their off ticks). */
    public boolean isSyncThisTick() {
        return syncThisTick;
    }

    public void setSyncThisTick(final boolean syncThisTick) {
        this.syncThisTick = syncThisTick;
    }

    /** Cached ground sample for draping — only re-read once the segment has moved off the sampled column. */
    public double getDrapeSampleX() {
        return drapeSampleX;
    }

    public double getDrapeSampleZ() {
        return drapeSampleZ;
    }

    public double getDrapeSampleY() {
        return drapeSampleY;
    }

    public void setDrapeSample(final double x, final double z, final double y) {
        this.drapeSampleX = x;
        this.drapeSampleZ = z;
        this.drapeSampleY = y;
    }

    /** Ground height the segment is currently resting at, eased toward the sampled terrain (NaN = unset). */
    public double getSmoothGroundY() {
        return smoothGroundY;
    }

    public void setSmoothGroundY(final double smoothGroundY) {
        this.smoothGroundY = smoothGroundY;
    }

    @Nonnull
    public List<Ref<EntityStore>> getVoxels() {
        return voxels;
    }

    public void clearVoxels() {
        voxels.clear();
    }

    @Nonnull
    public DunewyrmSegment copyMeta() {
        final var copy = new DunewyrmSegment(role, prefabKey, mirrored, maxHealth);
        copy.health = health;
        copy.position.set(position);
        copy.yaw = yaw;
        copy.pitch = pitch;
        return copy;
    }
}
