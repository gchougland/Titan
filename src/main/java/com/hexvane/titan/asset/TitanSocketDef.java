package com.hexvane.titan.asset;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.math.vector.Vector3dUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

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
        .build();

    private String bone;
    @Nonnull
    private final Vector3d offset = new Vector3d();

    private transient int boneIndex = -1;

    @Nonnull
    public String getBone() {
        return bone;
    }

    /** Offset from the bone pivot, in model units. */
    @Nonnull
    public Vector3d getOffset() {
        return offset;
    }

    public int getBoneIndex() {
        return boneIndex;
    }

    void resolve(final int boneIndex) {
        this.boneIndex = boneIndex;
    }
}
