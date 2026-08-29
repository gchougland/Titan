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
import org.joml.Quaterniond;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
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

    /**
     * How far apart two ore nodes must sit, as a fraction of a node's own width. Well under 1 on purpose:
     * the body is only a few nodes wide, so demanding no contact at all would reject most of the socket
     * list and push the picker into its unspaced fallback. Letting neighbours graze keeps the spread.
     */
    private static final double SOCKET_SPACING = 0.6;

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

        final Counts counts = spawnParts(store, root, titan, variant, skeleton, colliderMode);
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
                                     @Nonnull final TitanVariantAsset variant,
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
            final PrefabVoxels voxels = PrefabVoxelReader.read(bone.getPrefab(), variant.getRockType());
            if (voxels.isEmpty()) continue;

            final Vector3d pivot = bone.getPivot() != null ? new Vector3d(bone.getPivot()) : voxels.defaultPivot();
            final float boneScale = voxelScale * bone.getScale();
            final double mirror = bone.isMirrorX() ? -1.0 : 1.0;
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

                // Reflecting after the pivot subtraction mirrors about the bone's axis rather than the
                // prefab's origin, so a mirrored limb still hangs off its joint in the same place.
                final var local = new Vector3d(
                    (voxel.x() + 0.5 - pivot.x) * bone.getScale() * mirror,
                    (voxel.y() + 0.5 - pivot.y) * bone.getScale(),
                    (voxel.z() + 0.5 - pivot.z) * bone.getScale()
                );
                pose.transformLocal(bone.getIndex(), local, worldPos);

                boolean collider = false;
                if (boneWantsColliders && colliderMode.accepts(voxel, bone)) {
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

        // Socket offsets are model units, so the model's world-block measurements are converted before use.
        final Box nodeBox = TitanPartBuilder.weakpointBox(modelId, variant.getWeakpointScale());
        // The ore is modelled growing up from an origin at its base, so its visual centre sits this far
        // along its own axis. Without taking it back out every node hangs off the surface it is bolted to.
        final double centreCorrection = nodeBox == null
            ? 0
            : (nodeBox.getMin().y + nodeBox.getMax().y) * 0.5 / titan.getScale();
        final double nodeWidth = nodeBox == null ? 0 : nodeBox.getMaximumThickness() / titan.getScale();
        final double sink = centreCorrection + variant.getWeakpointEmbed();

        final var worldPos = new Vector3d();
        final var worldRotation = new Rotation3f();
        final var local = new Vector3d();
        final var outward = new Vector3d();
        final var localRotation = new Quaterniond();
        int spawned = 0;

        for (final int socketIndex : chooseSockets(variant, sockets, nodeWidth * SOCKET_SPACING)) {
            final TitanSocketDef socket = sockets[socketIndex];
            final int bone = socket.getBoneIndex();
            if (bone < 0) continue;

            // Sockets are authored on the body surface, so the direction from the bone's pivot out to one
            // is the surface normal there. Aiming the ore's growth axis along it makes a node on the chest
            // jut forwards and one on the flank jut sideways, and backing the node down that same axis
            // beds it into the rock whichever face it landed on.
            local.set(socket.getOffset());
            outward.set(local);
            if (outward.lengthSquared() > 1.0e-6) {
                outward.normalize();
                local.fma(-sink, outward);
                localRotation.identity().rotationTo(0, 1, 0, outward.x, outward.y, outward.z);
            } else {
                local.y -= sink;
                localRotation.identity();
            }

            pose.transformLocal(bone, local, worldPos);
            pose.getWorldRotation(bone, localRotation, worldRotation);

            final var holder = TitanPartBuilder.buildWeakpoint(
                store, root, modelId, variant.getWeakpointScale(), variant.getWeakpointHealth(),
                worldPos, worldRotation, bone, new Vector3d(local), localRotation);
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
     * Rolls how many ore nodes this titan gets and which sockets they land on.
     *
     * <p>Sockets are authored all over the body — back, top, front and flanks — so shuffling them and taking
     * a handful means two titans of the same variant are climbed and attacked differently. Picks closer
     * together than {@code minSeparation} model units are skipped on the first pass so the ore clusters do
     * not grow through each other; a second pass without that rule guarantees the quota is still met on a
     * skeleton whose sockets are all crowded together.
     */
    @Nonnull
    private static int[] chooseSockets(@Nonnull final TitanVariantAsset variant,
                                       @Nonnull final TitanSocketDef[] sockets,
                                       final double minSeparation) {

        final var random = ThreadLocalRandom.current();

        final int min = Math.max(1, variant.getWeakpointCountMin());
        final int max = Math.max(min, variant.getWeakpointCountMax());
        final int wanted = Math.min(sockets.length, random.nextInt(min, max + 1));

        final int[] order = new int[sockets.length];
        for (int i = 0; i < sockets.length; i++) order[i] = i;
        for (int i = sockets.length - 1; i > 0; i--) {
            final int j = random.nextInt(i + 1);
            final int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }

        final double minSq = minSeparation * minSeparation;
        final int[] chosen = new int[wanted];
        int count = 0;

        for (int pass = 0; pass < 2 && count < wanted; pass++) {
            for (final int candidate : order) {
                if (count == wanted) break;
                if (contains(chosen, count, candidate)) continue;
                if (pass == 0 && !isClearOf(sockets, chosen, count, candidate, minSq)) continue;
                chosen[count++] = candidate;
            }
        }
        return Arrays.copyOf(chosen, count);
    }

    private static boolean contains(@Nonnull final int[] values, final int count, final int value) {
        for (int i = 0; i < count; i++) {
            if (values[i] == value) return true;
        }
        return false;
    }

    private static boolean isClearOf(@Nonnull final TitanSocketDef[] sockets,
                                     @Nonnull final int[] chosen,
                                     final int count,
                                     final int candidate,
                                     final double minSq) {

        final var offset = sockets[candidate].getOffset();
        for (int i = 0; i < count; i++) {
            if (sockets[chosen[i]].getOffset().distanceSquared(offset) < minSq) return false;
        }
        return true;
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
