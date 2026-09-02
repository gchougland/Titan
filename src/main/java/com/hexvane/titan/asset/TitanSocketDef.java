package com.hexvane.titan.asset;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.math.vector.Vector3dUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * An attachment point on a bone. Ore weakpoints are spawned onto sockets and followed every tick.
 */
public final class TitanSocketDef {

    @Nonnull
    public static final BuilderCodec<TitanSocketDef> CODEC = BuilderCodec.builder(TitanSocketDef.class, TitanSocketDef::new)
        .append(
            new KeyedCodec<>("Bone", Codec.STRING, true),
            (o, v) -> o.bone = v,
            o -> o.bone
        ).add()
        .append(
            new KeyedCodec<>("Offset", Vector3dUtil.CODEC),
            (o, v) -> o.offset.set(v),
            o -> o.offset
        ).add()
        .append(
            new KeyedCodec<>("Normal", Vector3dUtil.CODEC),
            (o, v) -> o.normal = new Vector3d(v),
            o -> o.normal
        ).add()
        .build();

    private String bone;
    @Nonnull
    private final Vector3d offset = new Vector3d();
    @Nullable
    private Vector3d normal;

    private transient int boneIndex = -1;

    /** Name of the bone this socket rides on. */
    @Nonnull
    public String getBone() {
        return bone;
    }

    /** Offset from the bone pivot, in model units. */
    @Nonnull
    public Vector3d getOffset() {
        return offset;
    }

    /**
     * Direction the growth on this socket points, in the bone's local space.
     *
     * <p>Only needed on a bone whose pivot sits outside the shape it carries. For a body slab pivoting at
     * its own centre the offset direction is already the surface normal; for a limb pivoting at the joint
     * it hangs from it is not, and every socket down the limb would otherwise point along the limb.
     *
     * @return {@code null} to derive the normal from the offset direction
     */
    @Nullable
    public Vector3d getNormal() {
        return normal;
    }

    /** @return the resolved bone index, or {@code -1} when the bone name did not match. */
    public int getBoneIndex() {
        return boneIndex;
    }

    void resolve(final int boneIndex) {
        this.boneIndex = boneIndex;
    }
}
