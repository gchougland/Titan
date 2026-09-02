package com.hexvane.titan.combat;

import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.ik.GroundSampler;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Draws the ground markers that say where a titan is about to hit.
 *
 * <p>The engine has no attack indicator of any kind, so these are ordinary particle systems that happen to
 * be a single ring-shaped quad lying face up. The assets are authored at a radius of one block, which is
 * what makes this class small: the scale carried by the spawn packet is the radius in blocks, so a caller
 * asks for the circle it is actually about to damage and the marker is drawn at exactly that size. Nothing
 * in the assets knows what a smash or a pound is.
 *
 * <p>Markers are laid on the terrain rather than at the attack's own height. A smash is aimed at a point
 * that may be a metre off the floor, and a ring floating there — or worse, buried in the hillside — is not
 * a marker of anything.
 */
public final class TitanTelegraph {

    /** How far above the surface a marker floats, in blocks. Enough to beat z-fighting and no more. */
    private static final double GROUND_LIFT = 0.15;
    /** Vertical window the surface is looked for in, around the point being marked. */
    private static final int GROUND_ABOVE = 4;
    private static final int GROUND_BELOW = 10;

    /** Slowest and fastest a windup marker repeats, in seconds. */
    private static final float PULSE_SLOWEST = 0.34f;
    private static final float PULSE_FASTEST = 0.09f;
    /** Most rings a single corridor is allowed to lay down. */
    private static final int MAX_CORRIDOR_RINGS = 12;

    private TitanTelegraph() {
    }

    public static boolean isEnabled() {
        return TitanConfig.get().areTelegraphsEnabled();
    }

    /**
     * How long to wait before the next pulse of a windup marker.
     *
     * <p>Shortens as the windup runs out, so the marker beats faster the closer the attack gets. That is
     * the part that carries the timing: the ring says where, and the rate it flashes at says when.
     *
     * @param remaining seconds of windup left
     */
    public static float pulseInterval(final float remaining) {
        if (remaining <= 0f) return PULSE_FASTEST;
        final float scaled = remaining * 0.4f;
        return Math.min(PULSE_SLOWEST, Math.max(PULSE_FASTEST, scaled));
    }

    /**
     * Lays a ring of the given radius flat on the ground under {@code centre}.
     *
     * @param yaw which way the marker faces. Only matters for markers that are not circular.
     */
    public static void ring(@Nonnull final ComponentAccessor<EntityStore> accessor,
                            @Nullable final ChunkStore chunkStore,
                            @Nullable final String system,
                            @Nonnull final Vector3d centre,
                            final double radius,
                            final float yaw) {

        if (!isEnabled() || system == null || system.isEmpty() || radius <= 0) return;

        // Copied rather than mutated in place: the caller passes its live attack point, and worlds tick on
        // separate threads, so a shared scratch vector here would be both destructive and a race.
        final var at = new Vector3d(centre);
        if (!settleOnGround(chunkStore, at)) return;

        ParticleUtil.spawnParticleEffect(system, at, yaw, 0f, 0f, (float) radius, 0f, accessor);
    }

    /**
     * Marks the corridor a charge is about to come down, as a row of rings running forward from
     * {@code origin}.
     *
     * <p>Each ring is sampled onto the terrain under it rather than laid out on a flat plane, so the line
     * follows a slope and stays readable on the kind of rolling ground a titan actually stands on.
     *
     * @param halfWidth half the width of the corridor, and so the radius of each ring
     */
    public static void corridor(@Nonnull final ComponentAccessor<EntityStore> accessor,
                                @Nullable final ChunkStore chunkStore,
                                @Nullable final String system,
                                @Nonnull final Vector3d origin,
                                final float yaw,
                                final double length,
                                final double halfWidth) {

        if (!isEnabled() || system == null || system.isEmpty() || halfWidth <= 0 || length <= 0) return;

        final double spacing = halfWidth * 1.5;
        final int count = Math.min(MAX_CORRIDOR_RINGS, Math.max(1, (int) Math.round(length / spacing)));

        final double forwardX = -Math.sin(yaw);
        final double forwardZ = -Math.cos(yaw);
        final var at = new Vector3d();

        for (int i = 1; i <= count; i++) {
            final double along = length * i / count;
            at.set(origin.x + forwardX * along, origin.y, origin.z + forwardZ * along);
            if (!settleOnGround(chunkStore, at)) continue;

            ParticleUtil.spawnParticleEffect(system, at, yaw, 0f, 0f, (float) halfWidth, 0f, accessor);
        }
    }

    /**
     * Fires a one-shot effect at a point, at its authored size. For the things that are not markers of a
     * future attack but debris from a present one, such as the ground splitting as a boulder is torn free.
     */
    public static void burst(@Nonnull final ComponentAccessor<EntityStore> accessor,
                             @Nullable final String system,
                             @Nonnull final Vector3d at,
                             final float scale) {

        if (system == null || system.isEmpty()) return;
        ParticleUtil.spawnParticleEffect(system, at, 0f, 0f, 0f, scale, 0f, accessor);
    }

    /**
     * Drops a point onto the surface below it.
     *
     * @return {@code false} when the terrain there is not loaded, in which case nothing should be drawn
     */
    private static boolean settleOnGround(@Nullable final ChunkStore chunkStore, @Nonnull final Vector3d point) {
        if (chunkStore == null) return true;

        final double ground = GroundSampler.sample(chunkStore, point.x, point.y, point.z, GROUND_ABOVE, GROUND_BELOW);
        if (!GroundSampler.isValid(ground)) return false;

        point.y = ground + GROUND_LIFT;
        return true;
    }
}
