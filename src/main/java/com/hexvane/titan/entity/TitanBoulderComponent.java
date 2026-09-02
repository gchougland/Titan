package com.hexvane.titan.entity;

import com.hexvane.titan.TitanRegistry;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The invisible centre of a boulder in flight, with the voxels that make it up hung off it the same way a
 * titan's body is hung off its root.
 *
 * <p>It carries its own copy of what it will do on landing rather than a reference back to the variant that
 * threw it. A boulder outlives the moment it was thrown by a couple of seconds, and in that time the titan
 * can die, unload, or have its assets swapped out by a live reload; a rock already in the air should still
 * land the way it was thrown.
 *
 * <p>Runtime-only. A boulder mid-flight is not a thing worth writing to disk.
 */
public final class TitanBoulderComponent implements Component<EntityStore> {

    public static ComponentType<EntityStore, TitanBoulderComponent> getComponentType() {
        return TitanRegistry.getBoulderComponentType();
    }

    /** Who threw it, so the blast does not come back on the thrower or its own body. */
    @Nullable
    private Ref<EntityStore> thrower;

    @Nonnull
    private final Vector3d velocity = new Vector3d();
    @Nonnull
    private final Vector3d angularVelocity = new Vector3d();
    /** Current tumble, applied to every voxel's offset so the whole rock turns as one. */
    @Nonnull
    private final Quaterniond orientation = new Quaterniond();
    /**
     * Where the throw was aimed, kept so the ground can go on being marked for the whole flight.
     *
     * <p>The windup's marker says a rock is coming and roughly where. This is what keeps it there while the
     * rock is actually in the air, which is most of the time a player has to react in.
     */
    @Nonnull
    private final Vector3d landing = new Vector3d();
    /** Seconds until the landing marker is drawn again. */
    private float telegraphTimer;

    private float damage;
    private float radius;
    private float knockback;
    @Nullable
    private String impactParticle;
    @Nullable
    private String impactSound;

    private float lifetime;
    /**
     * How long before the boulder is allowed to hit anything.
     *
     * <p>It leaves from the titan's own fist, which is inside the titan, and the first thing a spatial query
     * from there finds is the arm it was thrown by. Filtering out the thrower's parts handles that, but not
     * a second titan standing shoulder to shoulder with the first, so the rock is simply intangible for the
     * first fraction of a second of its flight.
     */
    private float armTimer;

    /** Set the moment it lands. The voxels watch for this and let go on their next tick. */
    private boolean shattered;
    private float shatterTimer;

    public TitanBoulderComponent() {
    }

    public TitanBoulderComponent(@Nonnull final Ref<EntityStore> thrower,
                                 @Nonnull final Vector3d velocity,
                                 @Nonnull final Vector3d angularVelocity,
                                 final float damage,
                                 final float radius,
                                 final float knockback,
                                 @Nullable final String impactParticle,
                                 @Nullable final String impactSound,
                                 final float armTimer) {
        this.thrower = thrower;
        this.velocity.set(velocity);
        this.angularVelocity.set(angularVelocity);
        this.damage = damage;
        this.radius = radius;
        this.knockback = knockback;
        this.impactParticle = impactParticle;
        this.impactSound = impactSound;
        this.armTimer = armTimer;
    }

    @Nullable
    public Ref<EntityStore> getThrower() {
        return thrower;
    }

    @Nonnull
    public Vector3d getVelocity() {
        return velocity;
    }

    @Nonnull
    public Vector3d getAngularVelocity() {
        return angularVelocity;
    }

    @Nonnull
    public Quaterniond getOrientation() {
        return orientation;
    }

    public float getDamage() {
        return damage;
    }

    public float getRadius() {
        return radius;
    }

    public float getKnockback() {
        return knockback;
    }

    @Nullable
    public String getImpactParticle() {
        return impactParticle;
    }

    @Nullable
    public String getImpactSound() {
        return impactSound;
    }

    public float getLifetime() {
        return lifetime;
    }

    public void addLifetime(final float dt) {
        lifetime += dt;
    }

    /** Whether the boulder may hit things yet. Counts down through the first moments of the throw. */
    public boolean isArmed() {
        return armTimer <= 0f;
    }

    public void tickArming(final float dt) {
        if (armTimer > 0f) armTimer = Math.max(0f, armTimer - dt);
    }

    @Nonnull
    public Vector3d getLanding() {
        return landing;
    }

    /** @return whether the landing marker is due to be drawn again this tick */
    public boolean consumeTelegraph(final float dt, final float interval) {
        telegraphTimer -= dt;
        if (telegraphTimer > 0f) return false;
        telegraphTimer = interval;
        return true;
    }

    public boolean isShattered() {
        return shattered;
    }

    /**
     * Marks the boulder as landed. The velocity is deliberately left alone: the voxels read it on their next
     * tick to work out which way the rubble should be thrown, so the rock's own momentum carries into the
     * debris and it comes apart along the direction it was travelling.
     */
    public void shatter() {
        shattered = true;
    }

    /** Seconds since it landed, so the voxels get a tick to notice before the centre is taken away. */
    public float getShatterTimer() {
        return shatterTimer;
    }

    public void addShatterTimer(final float dt) {
        shatterTimer += dt;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        final var copy = new TitanBoulderComponent();
        copy.thrower = thrower;
        copy.velocity.set(velocity);
        copy.angularVelocity.set(angularVelocity);
        copy.orientation.set(orientation);
        copy.landing.set(landing);
        copy.telegraphTimer = telegraphTimer;
        copy.damage = damage;
        copy.radius = radius;
        copy.knockback = knockback;
        copy.impactParticle = impactParticle;
        copy.impactSound = impactSound;
        copy.lifetime = lifetime;
        copy.armTimer = armTimer;
        copy.shattered = shattered;
        copy.shatterTimer = shatterTimer;
        return copy;
    }
}
