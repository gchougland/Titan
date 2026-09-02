package com.hexvane.titan.anim;

import com.hexvane.titan.asset.TitanSkeletonAsset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Plays one clip at a time and cross-fades on change.
 *
 * <p>Holds a second {@link TitanPose} for the outgoing clip so a transition costs one extra sample rather
 * than an allocation.
 */
public final class TitanAnimator {

    @Nullable
    private TitanClip current;
    private float currentTime;

    @Nullable
    private TitanClip previous;
    private float previousTime;

    private float blendElapsed;
    private float blendDuration;

    @Nonnull
    private final TitanPose blendScratch;

    public TitanAnimator(final int boneCount) {
        this.blendScratch = new TitanPose(boneCount);
    }

    /** @return the clip being played, or {@code null} when nothing is loaded. */
    @Nullable
    public TitanClip getCurrent() {
        return current;
    }

    /** @return the active clip's name, or {@code <none>} when nothing is loaded. */
    @Nonnull
    public String getCurrentName() {
        return current == null ? "<none>" : current.getName();
    }

    /** @return seconds elapsed since the active clip started, before its speed is applied. */
    public float getCurrentTime() {
        return currentTime;
    }

    /** Normalised progress through the active clip, in {@code [0,1]}. */
    public float getCurrentProgress() {
        if (current == null) return 0f;
        final float p = currentTime * current.getSpeed() / current.getDuration();
        return p < 0f ? 0f : (p > 1f ? 1f : p);
    }

    /** @return {@code true} when no clip is loaded, or a non-looping clip has run out. */
    public boolean isFinished() {
        return current == null || current.isFinished(currentTime);
    }

    /**
     * Switches to {@code clip}, cross-fading from whatever is playing. Re-playing the active clip is a
     * no-op unless {@code restart} is set.
     */
    public void play(@Nullable final TitanClip clip, final boolean restart) {
        if (clip == current && !restart) return;

        if (current != null && clip != null && clip.getBlendingDuration() > 0f) {
            previous = current;
            previousTime = currentTime;
            blendDuration = clip.getBlendingDuration();
            blendElapsed = 0f;
        } else {
            previous = null;
            blendDuration = 0f;
            blendElapsed = 0f;
        }

        current = clip;
        currentTime = 0f;
    }

    /** Moves playback and any running cross-fade on by {@code dt} seconds. */
    public void advance(final float dt) {
        currentTime += dt;
        if (previous != null) {
            previousTime += dt;
            blendElapsed += dt;
            if (blendElapsed >= blendDuration) {
                previous = null;
            }
        }
    }

    /**
     * Writes the current animation state into {@code dest} as local bone transforms. Falls back to the
     * bind pose when no clip is loaded, which is also the result of a missing {@code .blockyanim}.
     */
    public void sampleInto(@Nonnull final TitanSkeletonAsset skeleton, @Nonnull final TitanPose dest) {
        if (current == null) {
            dest.resetToBind(skeleton);
            return;
        }

        dest.sample(skeleton, current, current.resolveTime(currentTime));

        if (previous != null && blendDuration > 0f) {
            blendScratch.sample(skeleton, previous, previous.resolveTime(previousTime));
            // Blend runs backwards: start fully on the outgoing pose and fall off to the incoming one.
            final float remaining = 1f - (blendElapsed / blendDuration);
            dest.blendFrom(blendScratch, remaining);
        }
    }
}
