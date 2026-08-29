package com.hexvane.titan.asset;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.hypixel.hytale.math.vector.Vector3dUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Declares a limb that the runtime solves with inverse kinematics instead of playing raw clip rotations.
 *
 * <p>{@code EnumCodec} spells constants in CamelCase, so JSON writes {@code "TwoBone"}, {@code "Fabrik"},
 * {@code "Foot"} and {@code "Hand"}.
 */
public final class TitanIkChainDef {

    public enum Kind {
        /** Analytic two-bone solve (upper, lower, effector). Used for arms and legs. */
        TWO_BONE,
        /** Iterative FABRIK solve over {@code Bones}. Used for anything longer than two segments. */
        FABRIK
    }

    public enum Role {
        /** Planted by the gait planner and driven towards ground contact points. */
        FOOT,
        /** Driven by attack targets; idles back to the clip pose. */
        HAND
    }

    @Nonnull
    public static final BuilderCodec<TitanIkChainDef> CODEC = BuilderCodec.builder(TitanIkChainDef.class, TitanIkChainDef::new)
        .append(
            new KeyedCodec<>("Name", Codec.STRING, true),
            (o, v) -> o.name = v,
            o -> o.name
        ).add()
        .append(
            new KeyedCodec<>("Kind", new EnumCodec<>(Kind.class)),
            (o, v) -> o.kind = v,
            o -> o.kind
        ).add()
        .append(
            new KeyedCodec<>("Role", new EnumCodec<>(Role.class)),
            (o, v) -> o.role = v,
            o -> o.role
        ).add()
        .append(
            new KeyedCodec<>("Bones", Codec.STRING_ARRAY, true),
            (o, v) -> o.bones = v,
            o -> o.bones
        ).add()
        .append(
            new KeyedCodec<>("PoleDirection", Vector3dUtil.CODEC),
            (o, v) -> o.poleDirection.set(v),
            o -> o.poleDirection
        ).add()
        .append(
            new KeyedCodec<>("Side", Codec.FLOAT),
            (o, v) -> o.side = v,
            o -> o.side
        ).add()
        .append(
            new KeyedCodec<>("StrideLength", Codec.FLOAT),
            (o, v) -> o.strideLength = v,
            o -> o.strideLength
        ).add()
        .append(
            new KeyedCodec<>("StepHeight", Codec.FLOAT),
            (o, v) -> o.stepHeight = v,
            o -> o.stepHeight
        ).add()
        .append(
            new KeyedCodec<>("GaitPhase", Codec.FLOAT),
            (o, v) -> o.gaitPhase = v,
            o -> o.gaitPhase
        ).add()
        .append(
            new KeyedCodec<>("RestOffset", Vector3dUtil.CODEC),
            (o, v) -> o.restOffset.set(v),
            o -> o.restOffset
        ).add()
        .build();

    private String name;
    @Nonnull
    private Kind kind = Kind.TWO_BONE;
    @Nonnull
    private Role role = Role.FOOT;
    private String[] bones = new String[0];
    @Nonnull
    private final Vector3d poleDirection = new Vector3d(0, 0, -1);
    private float side = 1f;
    private float strideLength = 3f;
    private float stepHeight = 1.5f;
    private float gaitPhase;
    @Nonnull
    private final Vector3d restOffset = new Vector3d();

    private transient int[] boneIndices = new int[0];

    @Nonnull
    public String getName() {
        return name;
    }

    @Nonnull
    public Kind getKind() {
        return kind;
    }

    @Nonnull
    public Role getRole() {
        return role;
    }

    @Nonnull
    public String[] getBones() {
        return bones;
    }

    /** Bending plane hint, in titan-local space. */
    @Nonnull
    public Vector3d getPoleDirection() {
        return poleDirection;
    }

    /** {@code +1} for the right side of the body, {@code -1} for the left. */
    public float getSide() {
        return side;
    }

    public float getStrideLength() {
        return strideLength;
    }

    public float getStepHeight() {
        return stepHeight;
    }

    /** Offset into the gait cycle in the range {@code [0,1)}; diagonal pairs share a phase. */
    public float getGaitPhase() {
        return gaitPhase;
    }

    /** Where the effector rests relative to the body, in model units. */
    @Nonnull
    public Vector3d getRestOffset() {
        return restOffset;
    }

    @Nonnull
    public int[] getBoneIndices() {
        return boneIndices;
    }

    /** Index of the bone the chain hangs from, i.e. the parent of the first chain bone. */
    public int getRootBoneIndex() {
        return boneIndices.length == 0 ? -1 : boneIndices[0];
    }

    public int getEndBoneIndex() {
        return boneIndices.length == 0 ? -1 : boneIndices[boneIndices.length - 1];
    }

    void resolve(@Nullable final int[] indices) {
        this.boneIndices = indices == null ? new int[0] : indices;
    }
}
