package com.hexvane.titan.asset;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * The rig for a block titan: a bone tree, the prefab geometry hanging off each bone, the IK chains that
 * override clip rotations, and the sockets weakpoints attach to.
 */
public final class TitanSkeletonAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, TitanSkeletonAsset>> {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    @Nonnull
    public static final DefaultAssetMap<String, TitanSkeletonAsset> ASSET_MAP = new DefaultAssetMap<>();

    @Nonnull
    public static final AssetBuilderCodec<String, TitanSkeletonAsset> CODEC = AssetBuilderCodec.builder(
        TitanSkeletonAsset.class,
        TitanSkeletonAsset::new,
        Codec.STRING,
        (a, id) -> a.id = id,
        a -> a.id,
        (a, data) -> a.data = data,
        a -> a.data
    )
        .append(
            new KeyedCodec<>("Bones", new ArrayCodec<>(TitanBoneDef.CODEC, TitanBoneDef[]::new), true),
            (a, v) -> a.bones = v,
            a -> a.bones
        ).add()
        .append(
            new KeyedCodec<>("IkChains", new ArrayCodec<>(TitanIkChainDef.CODEC, TitanIkChainDef[]::new)),
            (a, v) -> a.ikChains = v,
            a -> a.ikChains
        ).add()
        .append(
            new KeyedCodec<>("WeakpointSockets", new ArrayCodec<>(TitanSocketDef.CODEC, TitanSocketDef[]::new)),
            (a, v) -> a.weakpointSockets = v,
            a -> a.weakpointSockets
        ).add()
        .append(
            new KeyedCodec<>("ProceduralWobble", new ArrayCodec<>(TitanWobbleDef.CODEC, TitanWobbleDef[]::new)),
            (a, v) -> a.proceduralWobble = v,
            a -> a.proceduralWobble
        ).add()
        .append(
            new KeyedCodec<>("ClipSet", Codec.STRING),
            (a, v) -> a.clipSet = v,
            a -> a.clipSet
        ).add()
        .append(
            new KeyedCodec<>("BodyBone", Codec.STRING),
            (a, v) -> a.bodyBone = v,
            a -> a.bodyBone
        ).add()
        .append(
            new KeyedCodec<>("UnitScale", Codec.FLOAT),
            (a, v) -> a.unitScale = v,
            a -> a.unitScale
        ).add()
        .append(
            new KeyedCodec<>("HipHeight", Codec.FLOAT),
            (a, v) -> a.hipHeight = v,
            a -> a.hipHeight
        ).add()
        .append(
            new KeyedCodec<>("AnimationPositionScale", Codec.FLOAT),
            (a, v) -> a.animationPositionScale = v,
            a -> a.animationPositionScale
        ).add()
        .append(
            new KeyedCodec<>("ColliderConfig", Codec.STRING),
            (a, v) -> a.colliderConfig = v,
            a -> a.colliderConfig
        ).add()
        .afterDecode(TitanSkeletonAsset::resolve)
        .build();

    private String id;
    private AssetExtraInfo.Data data;

    private TitanBoneDef[] bones = new TitanBoneDef[0];
    private TitanIkChainDef[] ikChains = new TitanIkChainDef[0];
    private TitanSocketDef[] weakpointSockets = new TitanSocketDef[0];
    private TitanWobbleDef[] proceduralWobble = new TitanWobbleDef[0];
    @Nullable
    private String clipSet;
    @Nullable
    private String bodyBone;
    private float unitScale = 1f;
    private float hipHeight = 6f;
    private float animationPositionScale = 1f;
    @Nonnull
    private String colliderConfig = "Titan_Platform";

    private transient Map<String, Integer> boneIndexByName = Map.of();
    private transient int bodyBoneIndex;
    private transient int[] drawOrder = new int[0];

    /**
     * Links names to indices and pre-computes a parents-before-children evaluation order so the animation
     * system can build world matrices in a single pass.
     */
    private void resolve() {
        final var byName = new HashMap<String, Integer>(bones.length * 2);
        for (int i = 0; i < bones.length; i++) {
            byName.put(bones[i].getName(), i);
        }
        boneIndexByName = byName;

        for (int i = 0; i < bones.length; i++) {
            final var bone = bones[i];
            final var parentName = bone.getParent();
            int parentIndex = -1;
            if (parentName != null && !parentName.isEmpty()) {
                final Integer resolved = byName.get(parentName);
                if (resolved == null) {
                    LOGGER.at(Level.WARNING).log("Titan skeleton '%s' bone '%s' references unknown parent '%s'", id, bone.getName(), parentName);
                } else {
                    parentIndex = resolved;
                }
            }
            bone.resolve(i, parentIndex);
        }

        for (final var chain : ikChains) {
            final var names = chain.getBones();
            final int[] indices = new int[names.length];
            boolean valid = true;
            for (int i = 0; i < names.length; i++) {
                final Integer resolved = byName.get(names[i]);
                if (resolved == null) {
                    LOGGER.at(Level.WARNING).log("Titan skeleton '%s' IK chain '%s' references unknown bone '%s'", id, chain.getName(), names[i]);
                    valid = false;
                    break;
                }
                indices[i] = resolved;
            }
            chain.resolve(valid ? indices : null);
        }

        for (final var socket : weakpointSockets) {
            final Integer resolved = byName.get(socket.getBone());
            if (resolved == null) {
                LOGGER.at(Level.WARNING).log("Titan skeleton '%s' weakpoint socket references unknown bone '%s'", id, socket.getBone());
            }
            socket.resolve(resolved == null ? -1 : resolved);
        }

        for (final var wobble : proceduralWobble) {
            final Integer resolved = byName.get(wobble.getBone());
            if (resolved == null) {
                LOGGER.at(Level.WARNING).log("Titan skeleton '%s' procedural wobble references unknown bone '%s'", id, wobble.getBone());
            }
            wobble.resolve(resolved == null ? -1 : resolved);
        }

        bodyBoneIndex = bodyBone == null ? 0 : byName.getOrDefault(bodyBone, 0);
        drawOrder = buildEvaluationOrder();
    }

    /**
     * Topologically orders bones so every parent precedes its children. A cycle would otherwise stall the
     * pass, so anything left unemitted is appended in declaration order.
     */
    @Nonnull
    private int[] buildEvaluationOrder() {
        final int[] order = new int[bones.length];
        final boolean[] emitted = new boolean[bones.length];
        int written = 0;

        boolean progressed = true;
        while (written < bones.length && progressed) {
            progressed = false;
            for (int i = 0; i < bones.length; i++) {
                if (emitted[i]) continue;
                final int parent = bones[i].getParentIndex();
                if (parent >= 0 && !emitted[parent]) continue;
                emitted[i] = true;
                order[written++] = i;
                progressed = true;
            }
        }

        if (written < bones.length) {
            LOGGER.at(Level.WARNING).log("Titan skeleton '%s' has a cyclic bone hierarchy; remaining bones evaluated in declaration order", id);
            for (int i = 0; i < bones.length && written < bones.length; i++) {
                if (!emitted[i]) order[written++] = i;
            }
        }
        return order;
    }

    @Nonnull
    @Override
    public String getId() {
        return id;
    }

    /** Bones in declaration order; the index into this array identifies a bone everywhere else. */
    @Nonnull
    public TitanBoneDef[] getBones() {
        return bones;
    }

    /** @return the number of bones in the rig. */
    public int getBoneCount() {
        return bones.length;
    }

    /** Limbs solved with IK instead of raw clip rotations. */
    @Nonnull
    public TitanIkChainDef[] getIkChains() {
        return ikChains;
    }

    /** Attachment points that ore weakpoints are spawned onto. */
    @Nonnull
    public TitanSocketDef[] getWeakpointSockets() {
        return weakpointSockets;
    }

    /** Bones that sway on a sine wave over the clip pose. Empty for rigs that do not need it. */
    @Nonnull
    public TitanWobbleDef[] getProceduralWobble() {
        return proceduralWobble;
    }

    /** {@code TitanClipSetAsset} id supplying this rig's animations, or {@code null} for none. */
    @Nullable
    public String getClipSet() {
        return clipSet;
    }

    /** Index of the bone that carries the torso; the gait planner drives its height and lean. */
    public int getBodyBoneIndex() {
        return bodyBoneIndex;
    }

    /** World size, in blocks, of one model unit before the variant's body scale is applied. */
    public float getUnitScale() {
        return unitScale;
    }

    /** Resting height of the body bone above the feet, in model units. */
    public float getHipHeight() {
        return hipHeight;
    }

    /** Multiplier applied to {@code .blockyanim} position deltas, which are authored in Blockbench units. */
    public float getAnimationPositionScale() {
        return animationPositionScale;
    }

    /** {@code HitboxCollisionConfig} id used by climbable voxels. */
    @Nonnull
    public String getColliderConfig() {
        return colliderConfig;
    }

    /** Parents-before-children bone order for the world-matrix pass. */
    @Nonnull
    public int[] getEvaluationOrder() {
        return drawOrder;
    }

    /** @return the index of the named bone, or {@code -1} if the rig has no such bone. */
    public int indexOfBone(@Nonnull final String name) {
        return boneIndexByName.getOrDefault(name, -1);
    }

    /** @return the skeleton with this id, or {@code null} if it is not loaded. */
    @Nullable
    public static TitanSkeletonAsset find(@Nullable final String id) {
        return id == null ? null : ASSET_MAP.getAsset(id);
    }
}
