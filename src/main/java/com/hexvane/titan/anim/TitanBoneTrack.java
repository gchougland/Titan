package com.hexvane.titan.anim;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Keyframes for one bone of a clip, stored as flat arrays so sampling allocates nothing.
 *
 * <p>Times are in seconds. Both channels are Blockbench deltas on top of the bind pose: positions add to
 * the bind translation in the parent's frame, orientations compose onto the bind rotation in the bone's own.
 */
public final class TitanBoneTrack {

    @Nonnull
    private final float[] positionTimes;
    /** Packed {@code xyz} triples, one per entry of {@link #positionTimes}. */
    @Nonnull
    private final float[] positionValues;
    @Nonnull
    private final float[] rotationTimes;
    /** Packed {@code xyzw} quadruples, one per entry of {@link #rotationTimes}. */
    @Nonnull
    private final float[] rotationValues;

    public TitanBoneTrack(@Nonnull final float[] positionTimes,
                          @Nonnull final float[] positionValues,
                          @Nonnull final float[] rotationTimes,
                          @Nonnull final float[] rotationValues) {
        this.positionTimes = positionTimes;
        this.positionValues = positionValues;
        this.rotationTimes = rotationTimes;
        this.rotationValues = rotationValues;
    }

    /**
     * Re-expresses this track for a rig that is not the one it was authored on.
     *
     * <p>Done once at load rather than per sample, so a retargeted clip costs nothing extra to play.
     *
     * @param positionScale factor applied to every translation key
     * @param flipFacing    turn the animation half a turn about {@code Y}, for a source rig whose
     *                      {@code +X} and {@code +Z} point the other way. Negating those two components
     *                      is what conjugating by a half turn works out to, for vectors and quaternions
     *                      alike.
     * @return {@code this} when there is nothing to do, otherwise a converted copy
     */
    @Nonnull
    public TitanBoneTrack reinterpret(final float positionScale, final boolean flipFacing) {
        if (positionScale == 1f && !flipFacing) return this;

        final float[] positions = positionValues.clone();
        for (int i = 0; i < positions.length; i += 3) {
            positions[i] *= flipFacing ? -positionScale : positionScale;
            positions[i + 1] *= positionScale;
            positions[i + 2] *= flipFacing ? -positionScale : positionScale;
        }

        final float[] rotations = rotationValues.clone();
        if (flipFacing) {
            for (int i = 0; i < rotations.length; i += 4) {
                rotations[i] = -rotations[i];
                rotations[i + 2] = -rotations[i + 2];
            }
        }

        return new TitanBoneTrack(positionTimes, positions, rotationTimes, rotations);
    }

    public boolean hasPosition() {
        return positionTimes.length > 0;
    }

    public boolean hasRotation() {
        return rotationTimes.length > 0;
    }

    /**
     * Writes the linearly interpolated position delta at {@code time} into {@code dest}.
     *
     * @return {@code false} when the track carries no position keys, leaving {@code dest} untouched.
     */
    public boolean samplePosition(final float time, @Nonnull final Vector3d dest) {
        if (positionTimes.length == 0) return false;

        final int i = upperIndex(positionTimes, time);
        if (i == 0) {
            dest.set(positionValues[0], positionValues[1], positionValues[2]);
            return true;
        }

        final int a = (i - 1) * 3;
        final int b = i * 3;
        final float t = fraction(positionTimes, i, time);
        dest.set(
            lerp(positionValues[a], positionValues[b], t),
            lerp(positionValues[a + 1], positionValues[b + 1], t),
            lerp(positionValues[a + 2], positionValues[b + 2], t)
        );
        return true;
    }

    /**
     * Writes the spherically interpolated orientation at {@code time} into {@code dest}.
     *
     * @return {@code false} when the track carries no rotation keys, leaving {@code dest} untouched.
     */
    public boolean sampleRotation(final float time, @Nonnull final Quaterniond dest) {
        if (rotationTimes.length == 0) return false;

        final int i = upperIndex(rotationTimes, time);
        if (i == 0) {
            dest.set(rotationValues[0], rotationValues[1], rotationValues[2], rotationValues[3]).normalize();
            return true;
        }

        final int a = (i - 1) * 4;
        final int b = i * 4;
        final float t = fraction(rotationTimes, i, time);
        dest.set(rotationValues[a], rotationValues[a + 1], rotationValues[a + 2], rotationValues[a + 3]);
        dest.slerp(
            new Quaterniond(rotationValues[b], rotationValues[b + 1], rotationValues[b + 2], rotationValues[b + 3]),
            t
        ).normalize();
        return true;
    }

    /** Index of the first key at or after {@code time}, clamped to the last key. */
    private static int upperIndex(@Nonnull final float[] times, final float time) {
        for (int i = 0; i < times.length; i++) {
            if (times[i] >= time) return i;
        }
        return times.length - 1;
    }

    private static float fraction(@Nonnull final float[] times, final int i, final float time) {
        final float span = times[i] - times[i - 1];
        if (span <= 0f) return 0f;
        final float t = (time - times[i - 1]) / span;
        return t < 0f ? 0f : (t > 1f ? 1f : t);
    }

    private static float lerp(final float a, final float b, final float t) {
        return a + (b - a) * t;
    }
}
