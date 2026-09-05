package com.hexvane.titan.asset;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.math.vector.Vector3dUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * A bone that sways on a sine wave on top of whatever the clip left it at.
 *
 * <p>For the parts of a creature that are always in motion but never carry meaning: a tail that bobs, a
 * chimney that rocks. Authoring those as clips would mean a {@code .blockyanim} per state and a rig that
 * looks dead in any state that has none, and the motion has to speed up with the body anyway, which a
 * fixed-length clip cannot do.
 *
 * <p>Applied after clip sampling and before the IK pass, so a wobble on a bone inside an IK chain is
 * overwritten rather than fought with.
 */
public final class TitanWobbleDef {

    @Nonnull
    public static final BuilderCodec<TitanWobbleDef> CODEC = BuilderCodec.builder(TitanWobbleDef.class, TitanWobbleDef::new)
        .append(
            new KeyedCodec<>("Bone", Codec.STRING, true),
            (o, v) -> o.bone = v,
            o -> o.bone
        ).add()
        .append(
            new KeyedCodec<>("Axis", Vector3dUtil.CODEC),
            (o, v) -> o.axis.set(v),
            o -> o.axis
        ).add()
        .append(
            new KeyedCodec<>("AmplitudeDeg", Codec.FLOAT),
            (o, v) -> o.amplitudeDegrees = v,
            o -> o.amplitudeDegrees
        ).add()
        .append(
            new KeyedCodec<>("IdleAmplitudeDeg", Codec.FLOAT),
            (o, v) -> o.idleAmplitudeDegrees = v,
            o -> o.idleAmplitudeDegrees
        ).add()
        .append(
            new KeyedCodec<>("Hz", Codec.FLOAT),
            (o, v) -> o.hz = v,
            o -> o.hz
        ).add()
        .append(
            new KeyedCodec<>("SpeedScale", Codec.FLOAT),
            (o, v) -> o.speedScale = v,
            o -> o.speedScale
        ).add()
        .append(
            new KeyedCodec<>("PhaseDeg", Codec.FLOAT),
            (o, v) -> o.phaseDegrees = v,
            o -> o.phaseDegrees
        ).add()
        .build();

    private String bone;
    @Nonnull
    private final Vector3d axis = new Vector3d(0, 0, 1);
    private float amplitudeDegrees = 8f;
    private float idleAmplitudeDegrees = 2f;
    private float hz = 1f;
    private float speedScale = 3f;
    private float phaseDegrees;

    private transient int boneIndex = -1;

    /** Name of the bone this sways. */
    @Nonnull
    public String getBone() {
        return bone;
    }

    /**
     * Axis the bone turns about, in the bone's own space.
     *
     * <p>Local rather than titan-local so the sway follows the bone as the body turns. For a limb that
     * hangs along its own X, {@code (0,0,1)} bobs it up and down and {@code (0,1,0)} swings it side to side.
     */
    @Nonnull
    public Vector3d getAxis() {
        return axis;
    }

    /** Peak swing at or above {@link #getSpeedScale()}, in degrees either side of the clip pose. */
    public float getAmplitudeDegrees() {
        return amplitudeDegrees;
    }

    /**
     * Peak swing while standing still, in degrees.
     *
     * <p>A floor rather than zero, so a stationary creature still reads as breathing rather than as a
     * statue. The two amplitudes are interpolated by speed.
     */
    public float getIdleAmplitudeDegrees() {
        return idleAmplitudeDegrees;
    }

    /** Cycles per second. */
    public float getHz() {
        return hz;
    }

    /** Speed, in blocks per second, at which the swing reaches {@link #getAmplitudeDegrees()}. */
    public float getSpeedScale() {
        return speedScale;
    }

    /** Offset into the cycle, in degrees, so two wobbling bones need not move together. */
    public float getPhaseDegrees() {
        return phaseDegrees;
    }

    /** @return the resolved bone index, or {@code -1} when the name did not match a bone. */
    public int getBoneIndex() {
        return boneIndex;
    }

    void resolve(final int boneIndex) {
        this.boneIndex = boneIndex;
    }
}
