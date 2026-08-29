package com.hexvane.titan.spawn;

import com.hexvane.titan.anim.TitanPose;
import com.hexvane.titan.asset.TitanBoneDef;
import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hexvane.titan.asset.TitanSocketDef;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.entity.TitanComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollisionConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Level;

/**
 * Builds a complete titan: an invisible root that owns the skeleton, a voxel entity per prefab block, and
 * the ore weakpoints.
 *
 * <p>Must run on the world thread outside of ticking, because it writes to the store directly. Callers
 * inside a system should wrap it in {@code world.execute(...)}.
 */
public final class TitanSpawner {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Radius of the root's bounding box, in blocks. Only used for spatial queries; nothing renders it. */
    private static final double ROOT_BOX_RADIUS = 1.0;

    private TitanSpawner() {
    }

    /** Outcome of a spawn attempt, for command feedback. */
    public record Result(@Nullable Ref<EntityStore> root, int parts, int weakpoints, @Nullable String error) {
        public boolean ok() {
            return root != null;
        }

        @Nonnull
        static Result failure(@Nonnull final String error) {
            return new Result(null, 0, 0, error);
        }
    }

    /**
     * Spawns {@code variantId} at {@code position} facing {@code yaw} radians, with the default collider set.
     */
    @Nonnull
    public static Result spawn(@Nonnull final Store<EntityStore> store,
                               @Nonnull final String variantId,
                               @Nonnull final Vector3d position,
                               final float yaw) {
        return spawn(store, variantId, position, yaw, ColliderMode.DEFAULT);
    }

    /**
     * Spawns {@code variantId} at {@code position} facing {@code yaw} radians.
     *
     * @param colliderMode which voxels become climbable hard collision
     */
    @Nonnull
    public static Result spawn(@Nonnull final Store<EntityStore> store,
                               @Nonnull final String variantId,
                               @Nonnull final Vector3d position,
                               final float yaw,
                               @Nonnull final ColliderMode colliderMode) {

        final TitanVariantAsset variant = TitanVariantAsset.find(variantId);
        if (variant == null) return Result.failure("unknown variant '" + variantId + '\'');

        final TitanSkeletonAsset skeleton = TitanSkeletonAsset.find(variant.getSkeleton());
        if (skeleton == null) return Result.failure("variant '" + variantId + "' references unknown skeleton '" + variant.getSkeleton() + '\'');
        if (skeleton.getBoneCount() == 0) return Result.failure("skeleton '" + skeleton.getId() + "' has no bones");

        final var titan = new TitanComponent(variant, skeleton);
        titan.setYaw(yaw);

        final var rootHolder = EntityStore.REGISTRY.newHolder();
        rootHolder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(position), new Rotation3f(0, yaw, 0)));
        rootHolder.addComponent(BoundingBox.getComponentType(),
            new BoundingBox(new Box(-ROOT_BOX_RADIUS, 0, -ROOT_BOX_RADIUS, ROOT_BOX_RADIUS, ROOT_BOX_RADIUS * 2, ROOT_BOX_RADIUS)));
        rootHolder.addComponent(TitanComponent.getComponentType(), titan);
        rootHolder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());

        final Ref<EntityStore> root = store.addEntity(rootHolder, AddReason.SPAWN);

        final TitanPose pose = titan.getPose();
        assert pose != null;
        pose.resetToBind(skeleton);
        pose.computeWorld(skeleton, TitanPose.rootMatrix(position, yaw, titan.getScale(), new Matrix4d()));

        final Counts counts = spawnParts(store, root, titan, skeleton, colliderMode);
        final int weakpoints = spawnWeakpoints(store, root, titan, variant, skeleton);
        titan.setWeakpointCount(weakpoints);

        LOGGER.at(Level.INFO).log("Spawned titan '%s' with %d parts (%d climbable, mode %s) and %d weakpoints at %s",
            variantId, counts.parts, counts.colliders, colliderMode, weakpoints, position);
        return new Result(root, counts.parts, weakpoints, null);
    }

    /** Part tally for one spawn. */
    private static final class Counts {
        private int parts;
        private int colliders;
    }

    @Nonnull
    private static Counts spawnParts(@Nonnull final Store<EntityStore> store,
                                     @Nonnull final Ref<EntityStore> root,
                                     @Nonnull final TitanComponent titan,
                                     @Nonnull final TitanSkeletonAsset skeleton,
                                     @Nonnull final ColliderMode colliderMode) {

        final HitboxCollisionConfig colliderConfig = colliderMode == ColliderMode.NONE
            ? null
            : HitboxCollisionConfig.getAssetMap().getAsset(skeleton.getColliderConfig());
        if (colliderConfig == null && colliderMode != ColliderMode.NONE) {
            LOGGER.at(Level.WARNING).log("Titan skeleton '%s' references unknown HitboxCollisionConfig '%s'; parts will not be climbable",
                skeleton.getId(), skeleton.getColliderConfig());
        }

        final TitanPose pose = titan.getPose();
        assert pose != null;

        final float voxelScale = (float) (titan.getScale());
        final var worldPos = new Vector3d();
        final var rotation = new Rotation3f();
        final var counts = new Counts();

        for (final TitanBoneDef bone : skeleton.getBones()) {
            final PrefabVoxels voxels = PrefabVoxelReader.read(bone.getPrefab());
            if (voxels.isEmpty()) continue;

            final Vector3d pivot = bone.getPivot() != null ? new Vector3d(bone.getPivot()) : voxels.defaultPivot();
            final float boneScale = voxelScale * bone.getScale();
            final int stride = decimationStride(voxels.size(), bone.getMaxParts());
            final boolean boneWantsColliders = colliderConfig != null && bone.getColliderStride() > 0;

            pose.getWorldRotation(bone.getIndex(), rotation);

            int index = -1;
            // Counted separately from `index` so the stride thins the candidates evenly. Keying it off the
            // raw voxel index instead would interact with the shape of the prefab and could pick none at all.
            int colliderCandidate = -1;

            for (final PrefabVoxels.Voxel voxel : voxels.getVoxels()) {
                index++;
                if (stride > 1 && index % stride != 0) continue;

                final var local = new Vector3d(
                    (voxel.x() + 0.5 - pivot.x) * bone.getScale(),
                    (voxel.y() + 0.5 - pivot.y) * bone.getScale(),
                    (voxel.z() + 0.5 - pivot.z) * bone.getScale()
                );
                pose.transformLocal(bone.getIndex(), local, worldPos);

                boolean collider = false;
                if (boneWantsColliders && colliderMode.accepts(voxel)) {
                    colliderCandidate++;
                    collider = colliderCandidate % bone.getColliderStride() == 0;
                }

                final var holder = TitanPartBuilder.buildVoxel(
                    store, root, voxel.blockKey(), worldPos, rotation, boneScale, bone.getIndex(), local, collider, colliderConfig);
                store.addEntity(holder, AddReason.SPAWN);
                counts.parts++;
                if (collider) counts.colliders++;
            }
        }
        return counts;
    }

    private static int spawnWeakpoints(@Nonnull final Store<EntityStore> store,
                                       @Nonnull final Ref<EntityStore> root,
                                       @Nonnull final TitanComponent titan,
                                       @Nonnull final TitanVariantAsset variant,
                                       @Nonnull final TitanSkeletonAsset skeleton) {

        final String modelId = variant.getWeakpointModel();
        final TitanSocketDef[] sockets = skeleton.getWeakpointSockets();
        if (modelId == null || sockets.length == 0) {
            LOGGER.at(Level.WARNING).log("Titan variant '%s' has no weakpoint model or the skeleton declares no sockets; it will be unkillable",
                variant.getId());
            return 0;
        }

        final TitanPose pose = titan.getPose();
        assert pose != null;

        final var worldPos = new Vector3d();
        int spawned = 0;

        for (int i = 0; i < variant.getWeakpointCount(); i++) {
            final TitanSocketDef socket = sockets[i % sockets.length];
            final int bone = socket.getBoneIndex();
            if (bone < 0) continue;

            final var local = new Vector3d(socket.getOffset());
            pose.transformLocal(bone, local, worldPos);

            final var holder = TitanPartBuilder.buildWeakpoint(
                store, root, modelId, variant.getWeakpointScale(), variant.getWeakpointHealth(), worldPos, bone, local);
            if (holder == null) {
                LOGGER.at(Level.WARNING).log("Titan variant '%s' references unknown ModelAsset '%s'", variant.getId(), modelId);
                break;
            }

            titan.getWeakpoints().add(store.addEntity(holder, AddReason.SPAWN));
            spawned++;
        }
        return spawned;
    }

    /**
     * Keeps every n-th voxel when a bone declares a part budget, so a dense prefab does not blow the
     * per-titan entity count.
     */
    private static int decimationStride(final int count, final int maxParts) {
        if (maxParts <= 0 || count <= maxParts) return 1;
        return (int) Math.ceil((double) count / maxParts);
    }
}
