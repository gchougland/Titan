package com.hexvane.titan.dunewyrm;

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

    @Nonnull
    private final List<Ref<EntityStore>> voxels = new ArrayList<>();

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

    /** Returns true when the segment's pool is empty. */
    public boolean absorb(final float amount) {
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
