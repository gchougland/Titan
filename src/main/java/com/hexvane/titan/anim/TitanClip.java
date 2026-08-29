package com.hexvane.titan.anim;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;

/**
 * A parsed {@code .blockyanim} bound to playback settings from a {@code TitanClipEntry}.
 *
 * <p>Bone tracks are pre-resolved against a skeleton into an index-addressed array, so sampling never
 * touches a hash map.
 */
public final class TitanClip {

    @Nonnull
    private final String name;
    private final float duration;
    private final boolean looping;
    private final boolean holdLastKeyframe;
    private final float speed;
    private final float blendingDuration;
    /** Indexed by bone index; entries are {@code null} for bones the clip does not animate. */
    @Nonnull
    private final TitanBoneTrack[] tracks;

    public TitanClip(@Nonnull final String name,
                     final float duration,
                     final boolean looping,
                     final boolean holdLastKeyframe,
                     final float speed,
                     final float blendingDuration,
                     @Nonnull final TitanBoneTrack[] tracks) {
        this.name = name;
        this.duration = duration <= 0f ? 0.001f : duration;
        this.looping = looping;
        this.holdLastKeyframe = holdLastKeyframe;
        this.speed = speed <= 0f ? 1f : speed;
        this.blendingDuration = Math.max(0f, blendingDuration);
        this.tracks = tracks;
    }

    /**
     * Rebinds a parsed animation onto a different bone-index layout.
     */
    @Nonnull
    public static TitanClip bind(@Nonnull final String name,
                                 final float duration,
                                 final boolean holdLastKeyframe,
                                 @Nonnull final Map<String, TitanBoneTrack> byBoneName,
                                 @Nonnull final String[] boneNames,
                                 final boolean looping,
                                 final float speed,
                                 final float blendingDuration) {
        final var tracks = new TitanBoneTrack[boneNames.length];
        for (int i = 0; i < boneNames.length; i++) {
            tracks[i] = byBoneName.get(boneNames[i]);
        }
        return new TitanClip(name, duration, looping, holdLastKeyframe, speed, blendingDuration, tracks);
    }

    @Nonnull
    public String getName() {
        return name;
    }

    /** Length in seconds at unit speed. */
    public float getDuration() {
        return duration;
    }

    public boolean isLooping() {
        return looping;
    }

    public float getSpeed() {
        return speed;
    }

    public float getBlendingDuration() {
        return blendingDuration;
    }

    public int getBoneCount() {
        return tracks.length;
    }

    @Nullable
    public TitanBoneTrack getTrack(final int boneIndex) {
        return boneIndex < 0 || boneIndex >= tracks.length ? null : tracks[boneIndex];
    }

    /**
     * Maps an elapsed playback time onto a sample time inside the clip.
     */
    public float resolveTime(final float elapsed) {
        final float scaled = elapsed * speed;
        if (looping) {
            final float wrapped = scaled % duration;
            return wrapped < 0f ? wrapped + duration : wrapped;
        }
        if (scaled >= duration) return holdLastKeyframe ? duration : duration;
        return scaled < 0f ? 0f : scaled;
    }

    /** Whether a non-looping clip has run past its last keyframe. */
    public boolean isFinished(final float elapsed) {
        return !looping && elapsed * speed >= duration;
    }
}
