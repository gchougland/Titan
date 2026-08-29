package com.hexvane.titan.asset;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.math.vector.Vector3dUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A single bone of a {@link TitanSkeletonAsset}.
 *
 * <p>All offsets are expressed in <em>model units</em>: one model unit equals one prefab block before the
 * titan's world scale is applied. The runtime multiplies the whole rig by
 * {@link TitanSkeletonAsset#getUnitScale()} times the variant's body scale, so a skeleton stays authorable
 * against the raw prefab dimensions.
 */
public final class TitanBoneDef {

    @Nonnull
    public static final BuilderCodec<TitanBoneDef> CODEC = BuilderCodec.builder(TitanBoneDef.class, TitanBoneDef::new)
        .append(
            new KeyedCodec<>("Name", Codec.STRING, true),
            (o, v) -> o.name = v,
            o -> o.name
        ).add()
        .append(
            new KeyedCodec<>("Parent", Codec.STRING),
            (o, v) -> o.parent = v,
            o -> o.parent
        ).add()
        .append(
            new KeyedCodec<>("Offset", Vector3dUtil.CODEC),
            (o, v) -> o.offset.set(v),
            o -> o.offset
        ).add()
        .append(
            new KeyedCodec<>("Rotation", Vector3dUtil.CODEC),
            (o, v) -> o.bindRotationDegrees.set(v),
            o -> o.bindRotationDegrees
        ).add()
        .append(
            new KeyedCodec<>("Prefab", Codec.STRING),
            (o, v) -> o.prefab = v,
            o -> o.prefab
        ).add()
        .append(
            new KeyedCodec<>("Pivot", Vector3dUtil.CODEC),
            (o, v) -> o.pivot = new Vector3d(v),
            o -> o.pivot
        ).add()
        .append(
            new KeyedCodec<>("Scale", Codec.FLOAT),
            (o, v) -> o.scale = v,
            o -> o.scale
        ).add()
        .append(
            new KeyedCodec<>("ColliderStride", Codec.INTEGER),
            (o, v) -> o.colliderStride = v,
            o -> o.colliderStride
        ).add()
        .append(
            new KeyedCodec<>("MaxParts", Codec.INTEGER),
            (o, v) -> o.maxParts = v,
            o -> o.maxParts
        ).add()
        .append(
            new KeyedCodec<>("Detachable", Codec.BOOLEAN),
            (o, v) -> o.detachable = v,
            o -> o.detachable
        ).add()
        .build();

    private String name;
    @Nullable
    private String parent;
    @Nonnull
    private final Vector3d offset = new Vector3d();
    @Nonnull
    private final Vector3d bindRotationDegrees = new Vector3d();
    @Nullable
    private String prefab;
    @Nullable
    private Vector3d pivot;
    private float scale = 1f;
    private int colliderStride;
    private int maxParts;
    private boolean detachable = true;

    /** Resolved at load time by {@link TitanSkeletonAsset}. */
    private transient int index = -1;
    private transient int parentIndex = -1;

    @Nonnull
    public String getName() {
        return name;
    }

    @Nullable
    public String getParent() {
        return parent;
    }

    /** Bind-pose translation from the parent bone's pivot, in model units. */
    @Nonnull
    public Vector3d getOffset() {
        return offset;
    }

    /** Bind-pose rotation as XYZ Euler degrees, applied on top of the parent orientation. */
    @Nonnull
    public Vector3d getBindRotationDegrees() {
        return bindRotationDegrees;
    }

    /** Prefab key relative to {@code Server/Prefabs}, or {@code null} for a bone with no geometry. */
    @Nullable
    public String getPrefab() {
        return prefab;
    }

    /**
     * Point inside the prefab (in prefab block coordinates) that the bone rotates around. When absent the
     * builder uses the bottom centre of the prefab's bounds.
     */
    @Nullable
    public Vector3d getPivot() {
        return pivot;
    }

    public float getScale() {
        return scale;
    }

    /** Every n-th surface voxel becomes a hard-collision climbable block. {@code 0} disables collision. */
    public int getColliderStride() {
        return colliderStride;
    }

    /** Upper bound on spawned voxel entities for this bone; {@code 0} means unlimited. */
    public int getMaxParts() {
        return maxParts;
    }

    /** Whether this bone's voxels tumble away during the death ragdoll. */
    public boolean isDetachable() {
        return detachable;
    }

    public int getIndex() {
        return index;
    }

    public int getParentIndex() {
        return parentIndex;
    }

    void resolve(final int index, final int parentIndex) {
        this.index = index;
        this.parentIndex = parentIndex;
    }
}
