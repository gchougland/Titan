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
            new KeyedCodec<>("MirrorX", Codec.BOOLEAN),
            (o, v) -> o.mirrorX = v,
            o -> o.mirrorX
        ).add()
        .append(
            new KeyedCodec<>("ColliderStride", Codec.INTEGER),
            (o, v) -> o.colliderStride = v,
            o -> o.colliderStride
        ).add()
        .append(
            new KeyedCodec<>("ColliderAllFaces", Codec.BOOLEAN),
            (o, v) -> o.colliderAllFaces = v,
            o -> o.colliderAllFaces
        ).add()
        .append(
            new KeyedCodec<>("MaxParts", Codec.INTEGER),
            (o, v) -> o.maxParts = v,
            o -> o.maxParts
        ).add()
        .append(
            new KeyedCodec<>("Hollow", Codec.BOOLEAN),
            (o, v) -> o.hollow = v,
            o -> o.hollow
        ).add()
        .append(
            new KeyedCodec<>("SliceMinY", Codec.INTEGER),
            (o, v) -> o.sliceMinY = v,
            o -> o.sliceMinY
        ).add()
        .append(
            new KeyedCodec<>("SliceMaxY", Codec.INTEGER),
            (o, v) -> o.sliceMaxY = v,
            o -> o.sliceMaxY
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
    private boolean mirrorX;
    private int colliderStride;
    private boolean colliderAllFaces;
    private int maxParts;
    private boolean hollow;
    private int sliceMinY = Integer.MIN_VALUE;
    private int sliceMaxY = Integer.MAX_VALUE;
    private boolean detachable = true;

    /** Resolved at load time by {@link TitanSkeletonAsset}. */
    private transient int index = -1;
    private transient int parentIndex = -1;

    /** Unique bone name; animation tracks and IK chains refer to bones by this. */
    @Nonnull
    public String getName() {
        return name;
    }

    /** Name of the parent bone, or {@code null} for a root. */
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

    /** Extra uniform scale on this bone's geometry, on top of the rig's own scale. */
    public float getScale() {
        return scale;
    }

    /**
     * Reflects the prefab's voxels across the bone's own X axis.
     *
     * <p>Lets a limb pair share one prefab. Only the right hand is authored; the left reuses it mirrored,
     * where reusing it unchanged would give two right hands.
     */
    public boolean isMirrorX() {
        return mirrorX;
    }

    /** Every n-th eligible voxel becomes a hard-collision climbable block. {@code 0} disables collision. */
    public int getColliderStride() {
        return colliderStride;
    }

    /**
     * Whether every exposed voxel is collider-eligible, rather than only those with an exposed top face.
     *
     * <p>Top-faces-only is much cheaper and suits a bone that stays roughly level. It fails on a bone that
     * swings, because the top face is measured in the prefab's own space: on an arm held out at an angle
     * the only eligible voxels are the few capping the segment at its joint, leaving the walkable length
     * of the limb without collision.
     */
    public boolean isColliderAllFaces() {
        return colliderAllFaces;
    }

    /** Upper bound on spawned voxel entities for this bone; {@code 0} means unlimited. */
    public int getMaxParts() {
        return maxParts;
    }

    /**
     * Drops every voxel that is completely walled in by its neighbours, leaving only the shell.
     *
     * <p>A block with all six faces buried is never drawn and never touched, so this is free on solid
     * geometry. The saving scales with bulk: a limb segment a couple of blocks thick is nearly all shell
     * already, while a body the size of a small island is mostly filling, and paying an entity for each
     * interior voxel would put a titan that size out of reach. The one visible difference is the death
     * ragdoll, which crumbles into a hollow shell.
     */
    public boolean isHollow() {
        return hollow;
    }

    /**
     * Lowest prefab layer this bone takes, inclusive, in the prefab's own block coordinates.
     *
     * <p>Slicing lets one authored prefab serve several bones: a leg modelled as a single pillar becomes a
     * thigh, a calf and a foot by cutting it at two heights, giving it a knee to bend without splitting
     * the asset by hand. Each slice is skinned independently, so the cut faces count as exposed and
     * survive {@link #isHollow()}.
     */
    public int getSliceMinY() {
        return sliceMinY;
    }

    /** Highest prefab layer this bone takes, inclusive. See {@link #getSliceMinY()}. */
    public int getSliceMaxY() {
        return sliceMaxY;
    }

    /** Whether this bone's voxels tumble away during the death ragdoll. */
    public boolean isDetachable() {
        return detachable;
    }

    /** @return this bone's index in the skeleton's bone array. */
    public int getIndex() {
        return index;
    }

    /** @return the parent's bone index, or {@code -1} for a root or an unresolved parent. */
    public int getParentIndex() {
        return parentIndex;
    }

    void resolve(final int index, final int parentIndex) {
        this.index = index;
        this.parentIndex = parentIndex;
    }
}
