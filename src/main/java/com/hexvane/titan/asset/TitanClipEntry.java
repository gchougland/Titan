package com.hexvane.titan.asset;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import javax.annotation.Nonnull;

/**
 * Playback settings for one {@code .blockyanim} file. Field names mirror {@code ModelAsset.Animation} so
 * clip sets read the same way as vanilla animation sets.
 */
public final class TitanClipEntry {

    @Nonnull
    public static final BuilderCodec<TitanClipEntry> CODEC = BuilderCodec.builder(TitanClipEntry.class, TitanClipEntry::new)
        .append(
            new KeyedCodec<>("File", Codec.STRING, true),
            (o, v) -> o.file = v,
            o -> o.file
        ).add()
        .append(
            new KeyedCodec<>("Looping", Codec.BOOLEAN),
            (o, v) -> o.looping = v,
            o -> o.looping
        ).add()
        .append(
            new KeyedCodec<>("Speed", Codec.FLOAT),
            (o, v) -> o.speed = v,
            o -> o.speed
        ).add()
        .append(
            new KeyedCodec<>("BlendingDuration", Codec.FLOAT),
            (o, v) -> o.blendingDuration = v,
            o -> o.blendingDuration
        ).add()
        .build();

    private String file;
    private boolean looping;
    private float speed = 1f;
    private float blendingDuration = 0.25f;

    /** Path under {@code Common/}, e.g. {@code Titan/Talus/Animations/Walk.blockyanim}. */
    @Nonnull
    public String getFile() {
        return file;
    }

    public boolean isLooping() {
        return looping;
    }

    public float getSpeed() {
        return speed;
    }

    /** Cross-fade time in seconds when this clip becomes active. */
    public float getBlendingDuration() {
        return blendingDuration;
    }
}
