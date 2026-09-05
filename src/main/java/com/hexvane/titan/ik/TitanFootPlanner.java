package com.hexvane.titan.ik;

import com.hexvane.titan.asset.TitanIkChainDef;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Procedural gait: decides where each foot should be planted and arcs it to the next contact point.
 *
 * <p>Feet stay locked to the ground until the body has dragged them past their stride threshold. That
 * avoids the foot sliding a canned walk cycle produces on a creature moving at variable speed. Diagonal
 * pairs share a {@code gaitGroup} and only one group may swing at a time, giving a heavy alternating
 * stomp.
 */
public final class TitanFootPlanner {

    /** How far ahead of the body the next contact point is placed, as a fraction of the stride. */
    private static final double LEAD_FRACTION = 0.55;
    /** Seconds a single step takes at a walk; slow to suit the scale of the creature. */
    private static final float STEP_DURATION = 0.55f;
    /**
     * Shortest a step may be cut to when the body is moving quickly, in seconds.
     *
     * <p>A foot has to be back on the ground before the body has dragged it a full stride, or it never
     * catches up and the leg trails behind for as long as the creature keeps going. With groups alternating
     * strictly, one foot's swing has to fit inside half of that, which at a house's ten blocks a second is a
     * fifth of a second — so the step shortens with speed rather than being a fixed slow stomp. The floor
     * stops it becoming a twitch on something that has been pushed faster than it was ever meant to walk.
     */
    private static final float STEP_MINIMUM = 0.18f;
    /** How much of the time it takes to cover a stride a single swing is allowed to use. */
    private static final double SWING_SHARE = 0.5;
    /** Blocks above the candidate contact point that the ground search starts from. */
    private static final int GROUND_SEARCH_ABOVE = 4;
    /** Blocks below the candidate contact point that the ground search gives up at. */
    private static final int GROUND_SEARCH_BELOW = 12;

    private TitanFootPlanner() {
    }

    /** Reusable temporaries so gait planning allocates nothing per tick. */
    public static final class Scratch {
        @Nonnull
        private final Vector3d rest = new Vector3d();
        @Nonnull
        private final Vector3d lead = new Vector3d();
        @Nonnull
        private final Vector3d direction = new Vector3d();
    }

    /**
     * Advances one foot.
     *
     * @param state          mutable gait state for this limb
     * @param chain          the chain definition, supplying stride, step height and rest offset
     * @param bodyPosition   world position of the body bone
     * @param bodyForward    unit forward vector of the body
     * @param bodyRight      unit right vector of the body
     * @param velocity       world-space movement of the body, used to lead the contact point
     * @param scale          world blocks per model unit
     * @param groupCanStep   whether this foot's gait group is allowed to leave the ground this tick
     * @return {@code true} if this foot started or is in the middle of a step
     */
    public static boolean update(@Nonnull final FootState state,
                                 @Nonnull final TitanIkChainDef chain,
                                 @Nonnull final Vector3dc bodyPosition,
                                 @Nonnull final Vector3dc bodyForward,
                                 @Nonnull final Vector3dc bodyRight,
                                 @Nonnull final Vector3dc velocity,
                                 final double scale,
                                 @Nullable final ChunkStore chunkStore,
                                 final float dt,
                                 final boolean groupCanStep,
                                 @Nonnull final Scratch scratch) {

        final Vector3d rest = restPosition(chain, bodyPosition, bodyForward, bodyRight, scale, scratch.rest);

        if (!state.initialised) {
            snapToGround(rest, chunkStore);
            state.planted.set(rest);
            state.current.set(rest);
            state.stepTarget.set(rest);
            state.initialised = true;
            return false;
        }

        final double stride = chain.getStrideLength() * scale;

        if (state.stepping) {
            state.stepProgress += dt / swingDuration(stride, velocity);
            if (state.stepProgress >= 1f) {
                state.finishStep();
                return false;
            }
            final double arc = Math.sin(Math.PI * state.stepProgress) * chain.getStepHeight() * scale;
            state.current.set(state.stepOrigin).lerp(state.stepTarget, state.stepProgress);
            state.current.y += arc;
            return true;
        }

        state.current.set(state.planted);

        if (!groupCanStep) return false;

        final Vector3d lead = scratch.lead.set(rest).fma(LEAD_FRACTION * stride, safeDirection(velocity, scratch.direction));
        snapToGround(lead, chunkStore);

        final double dx = lead.x - state.planted.x;
        final double dz = lead.z - state.planted.z;
        final double drift = Math.sqrt(dx * dx + dz * dz);
        final double dy = Math.abs(lead.y - state.planted.y);

        if (drift < stride && dy < scale * 1.5) return false;

        state.stepTarget.set(lead);
        state.beginStep();
        return true;
    }

    /**
     * Where this limb would stand if the body were at rest, projected onto the ground.
     *
     * <p>{@code RestOffset} is read in the same titan-local space as a bone's {@code Offset}, so a foot's
     * rest spot is authored by copying the numbers off the leg it belongs to.
     */
    @Nonnull
    public static Vector3d restPosition(@Nonnull final TitanIkChainDef chain,
                                        @Nonnull final Vector3dc bodyPosition,
                                        @Nonnull final Vector3dc bodyForward,
                                        @Nonnull final Vector3dc bodyRight,
                                        final double scale,
                                        @Nonnull final Vector3d dest) {
        final Vector3dc offset = chain.getRestOffset();
        dest.set(bodyPosition);
        // Negated because bodyForward is -Z: without it a front leg's rest spot lands behind the titan and
        // the legs on each side reach across one another.
        dest.fma(-offset.z() * scale, bodyForward);
        dest.fma(offset.x() * scale, bodyRight);
        dest.y += offset.y() * scale;
        return dest;
    }

    /**
     * Height the body should ride at, so it clears uneven terrain instead of clipping into it.
     *
     * @return the highest planted foot height, or {@code NaN} when nothing is planted yet
     */
    public static double supportHeight(@Nonnull final FootState[] feet) {
        double highest = Double.NaN;
        for (final FootState foot : feet) {
            if (foot == null || !foot.initialised) continue;
            if (Double.isNaN(highest) || foot.planted.y > highest) highest = foot.planted.y;
        }
        return highest;
    }

    /**
     * How long this foot's swing should take at the speed the body is going.
     *
     * <p>Standing still it is the full slow stomp, since there is nothing to keep up with and a heavy
     * creature putting a foot down deliberately is the whole look. Moving, it is however long the body
     * takes to cover a stride, shared out between the two groups that have to take turns.
     */
    private static float swingDuration(final double stride, @Nonnull final Vector3dc velocity) {
        final double speed = Math.sqrt(velocity.x() * velocity.x() + velocity.z() * velocity.z());
        if (speed < IkMath.EPSILON) return STEP_DURATION;

        final double duration = SWING_SHARE * stride / speed;
        return (float) Math.clamp(duration, STEP_MINIMUM, STEP_DURATION);
    }

    private static void snapToGround(@Nonnull final Vector3d point, @Nullable final ChunkStore chunkStore) {
        if (chunkStore == null) return;
        final double ground = GroundSampler.sample(chunkStore, point.x, point.y, point.z, GROUND_SEARCH_ABOVE, GROUND_SEARCH_BELOW);
        if (GroundSampler.isValid(ground)) point.y = ground;
    }

    @Nonnull
    private static Vector3d safeDirection(@Nonnull final Vector3dc velocity, @Nonnull final Vector3d dest) {
        dest.set(velocity.x(), 0, velocity.z());
        final double len = dest.length();
        return len < IkMath.EPSILON ? dest.set(0, 0, 0) : dest.div(len);
    }
}
