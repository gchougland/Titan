package com.hexvane.titan.ai;

import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.combat.TitanTelegraph;
import com.hexvane.titan.entity.TitanComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Movement, aiming and telegraph helpers shared by the AI system and its attack handlers.
 */
public final class TitanAiSupport {

    /** Windup hand height, as a multiple of hip height. */
    public static final double RAISED_HAND_HEIGHT_FACTOR = 1.1;

    /** How far ahead of the root a braced forearm plants, in blocks. Used by the slam and the plow. */
    public static final double BRACE_HAND_REACH = 5.0;

    /** Sideways spread of the two braced forearms, in blocks. Used by the slam and the plow. */
    public static final double BRACE_HAND_SPREAD = 4.0;

    /** Fraction of a windup still to run when the danger circle starts filling in. */
    private static final float TELEGRAPH_FILL_LEAD = 0.45f;

    /**
     * Fraction of a windup spent aiming. Past it the impact point is fixed and the marker stops moving.
     *
     * <p>Without a commit point the attack would land wherever the target stood on the final tick, and the
     * marker would follow the player rather than warn them. The first part of the windup is the titan
     * choosing a spot, and the rest is the window to leave it.
     */
    private static final float AIM_COMMIT = 0.4f;

    /** Minimum alignment with the goal, as a cosine, before the titan walks rather than turns on the spot. */
    private static final double WALK_FACING_THRESHOLD = 0.7;

    private TitanAiSupport() {
    }

    /** Whether a windup has passed the point where it stops aiming. See {@link #AIM_COMMIT}. */
    public static boolean hasCommitted(@Nonnull final TitanComponent titan, final float windupSeconds) {
        return titan.getStateTime() >= windupSeconds * AIM_COMMIT;
    }

    /** Yaw that points from {@code from} towards {@code to}, in the engine's convention of forward as -Z. */
    public static float angleTo(@Nonnull final Vector3d from, @Nonnull final Vector3d to) {
        return (float) Math.atan2(-(to.x - from.x), -(to.z - from.z));
    }

    /** Distance between two points ignoring height. */
    public static double horizontalDistance(@Nonnull final Vector3d a, @Nonnull final Vector3d b) {
        final double dx = a.x - b.x;
        final double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Rotates the titan towards a point, capped at {@code turnSpeed} radians per second. */
    public static void turnTowards(@Nonnull final TitanComponent titan,
                                   @Nonnull final Vector3d from,
                                   @Nonnull final Vector3d to,
                                   final float turnSpeed,
                                   final float dt) {
        final float desired = angleTo(from, to);
        final float delta = MathUtil.wrapAngle(desired - titan.getYaw());
        final float maxStep = turnSpeed * dt;
        titan.setYaw(MathUtil.wrapAngle(titan.getYaw() + MathUtil.clamp(delta, -maxStep, maxStep)));
    }

    /**
     * Turns towards a point and, once roughly facing it, walks. Holding still until the turn has come round
     * stops the whole body crabbing sideways.
     */
    public static void walkTowards(@Nonnull final TitanAiScratch scratch,
                                   @Nonnull final TitanComponent titan,
                                   @Nonnull final TitanVariantAsset variant,
                                   @Nonnull final Vector3d position,
                                   @Nonnull final Vector3d goal,
                                   final double arrivalRadius,
                                   final float dt) {

        turnTowards(titan, position, goal, variant.getTurnSpeed(), dt);

        final double facing = Math.cos(angleTo(position, goal) - titan.getYaw());
        if (facing < WALK_FACING_THRESHOLD || horizontalDistance(position, goal) <= arrivalRadius) {
            titan.getVelocity().set(0);
            return;
        }

        scratch.direction.set(-Math.sin(titan.getYaw()), 0, -Math.cos(titan.getYaw()));
        titan.getVelocity().set(scratch.direction).mul(variant.getMoveSpeed());
        position.fma(variant.getMoveSpeed() * dt, scratch.direction);
    }

    /**
     * Marks the ground a windup is aimed at, pulsing faster as the attack nears.
     *
     * @param remaining seconds of windup left
     */
    public static void telegraphCircle(@Nonnull final Store<EntityStore> store,
                                       @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                       @Nonnull final TitanComponent titan,
                                       @Nonnull final TitanVariantAsset variant,
                                       @Nonnull final Vector3d centre,
                                       final double radius,
                                       final float remaining,
                                       final float dt) {

        if (!titan.consumePulse(dt, TitanTelegraph.pulseInterval(remaining))) return;

        final var chunkStore = store.getExternalData().getWorld().getChunkStore();
        TitanTelegraph.ring(commandBuffer, chunkStore, variant.getTelegraphRingParticle(), centre, radius, titan.getYaw());

        // The disc only appears at the end, so it reads as the circle closing rather than as decoration.
        if (remaining <= TELEGRAPH_FILL_LEAD) {
            TitanTelegraph.ring(commandBuffer, chunkStore, variant.getTelegraphFillParticle(), centre, radius, titan.getYaw());
        }
    }
}
