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
        .append(
            new KeyedCodec<>("PositionScale", Codec.FLOAT),
            (o, v) -> o.positionScale = v,
            o -> o.positionScale
        ).add()
        .append(
            new KeyedCodec<>("FlipFacing", Codec.BOOLEAN),
            (o, v) -> o.flipFacing = v,
            o -> o.flipFacing
        ).add()
        .build();

    private String file;
    private boolean looping;
    private float speed = 1f;
    private float blendingDuration = 0.25f;
    private float positionScale = 1f;
    private boolean flipFacing;

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

    /**
     * Multiplies the clip's translation keys, for a clip authored against a rig of a different size.
     *
     * <p>Rotations need no such treatment, which is why an animation borrowed from another creature is
     * mostly usable as-is: an elbow bend is an elbow bend at any scale. Only the translations are in the
     * source rig's units, so lifting the player's dance onto a titan needs them divided down by the ratio
     * of the two rigs' heights.
     */
    public float getPositionScale() {
        return positionScale;
    }

    /**
     * Whether the clip was authored on a rig facing the opposite way, and needs turning around.
     *
     * <p>The vanilla player rig puts {@code +X} on the left and {@code +Z} forwards; titans use the
     * opposite of both, which is the same rig yawed by half a turn. Without this a borrowed animation
     * plays as its own mirror image, leaning backwards where it should lean forwards.
     */
    public boolean isFlipFacing() {
        return flipFacing;
    }
}
