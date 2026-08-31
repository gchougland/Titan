package com.hexvane.titan.anim;

import com.hexvane.titan.asset.TitanClipSetAsset;
import com.hexvane.titan.asset.TitanSkeletonAsset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches clips already bound to a skeleton's bone-index layout.
 *
 * <p>Parsing happens once per (skeleton, clip) pair. {@link #invalidate()} drops everything so an asset
 * reload picks up edited {@code .blockyanim} files without a restart.
 */
public final class TitanClipLibrary {

    @Nonnull
    private static final Map<String, TitanClip> CACHE = new ConcurrentHashMap<>();
    /** Sentinel so a failed parse is not retried on every state change. */
    @Nonnull
    private static final TitanClip MISSING = new TitanClip("<missing>", 0.001f, false, false, 1f, 0f, new TitanBoneTrack[0]);

    private TitanClipLibrary() {
    }

    /**
     * Resolves a logical animation name for a skeleton, parsing the underlying file on first use.
     *
     * @return {@code null} when the clip set, entry, or file is missing
     */
    @Nullable
    public static TitanClip get(@Nonnull final TitanSkeletonAsset skeleton, @Nonnull final String animation) {
        final String key = skeleton.getId() + '|' + animation;
        final TitanClip cached = CACHE.get(key);
        if (cached != null) return cached == MISSING ? null : cached;

        final TitanClip loaded = load(skeleton, animation);
        CACHE.put(key, loaded == null ? MISSING : loaded);
        return loaded;
    }

    @Nullable
    private static TitanClip load(@Nonnull final TitanSkeletonAsset skeleton, @Nonnull final String animation) {
        final TitanClipSetAsset clipSet = TitanClipSetAsset.find(skeleton.getClipSet());
        if (clipSet == null) return null;

        final var entry = clipSet.get(animation);
        if (entry == null) return null;

        final var parsed = BlockyAnimParser.load(entry.getFile());
        if (parsed == null) return null;

        final var bones = skeleton.getBones();
        final var names = new String[bones.length];
        for (int i = 0; i < bones.length; i++) {
            names[i] = bones[i].getName();
        }

        return TitanClip.bind(
            animation,
            parsed.duration(),
            parsed.holdLastKeyframe(),
            parsed.tracks(),
            names,
            entry.isLooping(),
            entry.getSpeed(),
            entry.getBlendingDuration(),
            entry.getPositionScale(),
            entry.isFlipFacing()
        );
    }

    /** Drops every cached clip; call after {@code .blockyanim} or clip-set assets reload. */
    public static void invalidate() {
        CACHE.clear();
    }
}
