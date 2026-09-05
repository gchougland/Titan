package com.hexvane.titan.spawn;

import com.hexvane.titan.anim.TitanPose;
import com.hexvane.titan.asset.TitanBoneDef;
import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hexvane.titan.asset.TitanSocketDef;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanShellComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollisionConfig;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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
     * How far apart two ore nodes must sit, as a fraction of a node's own width. Well under 1: the body is
     * only a few nodes wide, so demanding no contact at all would reject most of the socket list and push
     * the picker into its unspaced fallback. Letting neighbours graze keeps the spread.
     */
    private static final double SOCKET_SPACING = 0.6;

    private TitanSpawner() {
    }

    /**
     * Outcome of a spawn attempt, for command feedback.
     *
     * @param inventoryCapacities slot count per container the spawned fixtures asked for, indexed as the
     *                            fixtures name their containers, with {@code 0} for an index no fixture
     *                            claimed. Reported rather than inferred from the variant because the count
     *                            of containers depends on how many copies of a fixture the geometry
     *                            actually holds, which only the build knows.
     */
    public record Result(@Nullable Ref<EntityStore> root,
                         int parts,
                         int weakpoints,
                         @Nonnull int[] inventoryCapacities,
                         @Nullable String error) {

        public boolean ok() {
            return root != null;
        }

        @Nonnull
        static Result failure(@Nonnull final String error) {
            return new Result(null, 0, 0, NO_INVENTORIES, error);
        }
    }

    @Nonnull
    private static final int[] NO_INVENTORIES = new int[0];

    /**
     * Spawns {@code variantId} at {@code position} facing {@code yaw} radians, with the default collider set
     * and a fresh roll for its ore nodes.
     */
    @Nonnull
    public static Result spawn(@Nonnull final Store<EntityStore> store,
                               @Nonnull final String variantId,
                               @Nonnull final Vector3d position,
                               final float yaw) {
        return spawn(store, variantId, position, yaw, ColliderMode.DEFAULT, ThreadLocalRandom.current().nextLong(), true);
    }

    /** Spawns with the given collider set and a fresh roll for its ore nodes. */
    @Nonnull
    public static Result spawn(@Nonnull final Store<EntityStore> store,
                               @Nonnull final String variantId,
                               @Nonnull final Vector3d position,
                               final float yaw,
                               @Nonnull final ColliderMode colliderMode) {
        return spawn(store, variantId, position, yaw, colliderMode, ThreadLocalRandom.current().nextLong(), true);
    }

    /**
     * Spawns {@code variantId} at {@code position} facing {@code yaw} radians.
     *
     * @param colliderMode which voxels become climbable hard collision
     * @param seed         drives how many ore nodes the titan gets and where they sit. A value derived from
     *                     the titan's place in the world rebuilds it identically every time, so a naturally
     *                     sited titan survives being unloaded and reloaded without being saved. Pass a
     *                     random one for a throwaway spawn.
     */
    @Nonnull
    public static Result spawn(@Nonnull final Store<EntityStore> store,
                               @Nonnull final String variantId,
                               @Nonnull final Vector3d position,
                               final float yaw,
                               @Nonnull final ColliderMode colliderMode,
                               final long seed) {
        return spawn(store, variantId, position, yaw, colliderMode, seed, true);
    }

    /**
     * Spawns {@code variantId} at {@code position} facing {@code yaw} radians.
     *
     * @param paintGround whether to stamp the variant's {@code GroundPrefab} into the world. Structure-placed
     *                    nests already painted the scenery, so site-spawned eggs pass {@code false}.
     */
    @Nonnull
    public static Result spawn(@Nonnull final Store<EntityStore> store,
                               @Nonnull final String variantId,
                               @Nonnull final Vector3d position,
                               final float yaw,
                               @Nonnull final ColliderMode colliderMode,
                               final long seed,
                               final boolean paintGround) {

        final TitanVariantAsset variant = TitanVariantAsset.find(variantId);
        if (variant == null) return Result.failure("unknown variant '" + variantId + '\'');
        if (!TitanConfig.get().isVariantEnabled(variant.getId())) {
            return Result.failure("variant '" + variant.getId() + "' is turned off under DisabledVariants in config.json");
        }

        final TitanSkeletonAsset skeleton = TitanSkeletonAsset.find(variant.getSkeleton());
        if (skeleton == null) return Result.failure("variant '" + variantId + "' references unknown skeleton '" + variant.getSkeleton() + '\'');
        if (skeleton.getBoneCount() == 0) return Result.failure("skeleton '" + skeleton.getId() + "' has no bones");

        final var titan = new TitanComponent(variant, skeleton);
        titan.setYaw(yaw);
        titan.getHome().set(position);

        final var rootHolder = EntityStore.REGISTRY.newHolder();
        rootHolder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(position), new Rotation3f(0, yaw, 0)));
        rootHolder.addComponent(BoundingBox.getComponentType(),
            new BoundingBox(new Box(-ROOT_BOX_RADIUS, 0, -ROOT_BOX_RADIUS, ROOT_BOX_RADIUS, ROOT_BOX_RADIUS * 2, ROOT_BOX_RADIUS)));
        rootHolder.addComponent(TitanComponent.getComponentType(), titan);
        rootHolder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());

        // The root renders nothing, but the client draws the boss bar from the tracked entity's own Health,
        // so the invisible root holds the pooled total of every ore node. A stat map also makes an entity a
        // legal attack target, and the root's box sits between the legs where a stray swing would find it,
        // so TitanRootDamageSystem refuses damage to it. The Invulnerable marker is not used for that: it
        // is replicated, and the client answers it by drawing the boss bar in its white indestructible style.
        rootHolder.addComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
        rootHolder.ensureComponent(EntityModule.get().getVisibleComponentType());
        rootHolder.ensureAndGetComponent(EntityStatMap.getComponentType()).update();

        final Ref<EntityStore> root = store.addEntity(rootHolder, AddReason.SPAWN);

        // Null means a ref system threw the entity straight back out, which is what the engine does to an
        // entity spawned where the world is not loaded. Checked rather than trusted: the root is handed to
        // everything below as the owner of eight hundred blocks, and without this the first thing to ask the
        // store about it takes the tick's task queue down with it.
        if (root == null) {
            return Result.failure("the world would not accept an entity at " + position
                + "; the chunk is probably not loaded");
        }

        // After the root and before the body, so the titan is assembled on top of its own scenery rather
        // than appearing and then having the ground redrawn under it — and so a spawn the world refuses
        // does not leave its scenery painted into the ground with nothing standing on it.
        if (paintGround) {
            TitanGroundPrefab.paint(store.getExternalData().getWorld(), store, variant.getGroundPrefab(), position);
        }

        final TitanPose pose = titan.getPose();
        assert pose != null;
        pose.resetToBind(skeleton);
        pose.computeWorld(skeleton, TitanPose.rootMatrix(position, yaw, titan.getScale(), new Matrix4d()));

        final float healthMultiplier = TitanConfig.get().getWeakpointHealthMultiplier();
        final float nodeHealth = variant.getWeakpointHealth() * healthMultiplier;
        final float shellHealth = variant.getShellHealth() * healthMultiplier;

        final Counts counts = spawnParts(store, root, titan, variant, skeleton, colliderMode, shellHealth, position);
        final int weakpoints = counts.shells
            + spawnWeakpoints(store, root, titan, variant, skeleton, new Random(seed), nodeHealth);
        titan.setWeakpointCount(weakpoints, variant.getWeakpointsToKill());
        titan.setNodeHealth(nodeHealth);

        if (counts.shells > 0) {
            // One pool for every voxel of the shell, which is what makes it a single target. Nothing about
            // the ore-node tally above applies to it: those blocks are never credited as broken, and the
            // root is left with the stat map's own default health because a shell titan has no bar to fill.
            store.putComponent(root, TitanShellComponent.getComponentType(), new TitanShellComponent(shellHealth));
            if (shellHealth <= 0f) {
                LOGGER.at(Level.WARNING).log("Titan variant '%s' has shell bones but no ShellHealth; its shell will break on the first hit",
                    variantId);
            }
        } else {
            // The bar reads full while every node is untouched and empties as they break. Its length is what
            // a kill costs rather than what the titan carries, so a variant with spare nodes still empties it.
            final var rootStats = store.getComponent(root, EntityStatMap.getComponentType());
            if (rootStats != null && weakpoints > 0) {
                TitanPartBuilder.applyHealth(rootStats, titan.getTotalHealth());
            }
        }

        LOGGER.at(Level.INFO).log("Spawned titan '%s' with %d parts (%d climbable, mode %s, %d fixtures) and %d weakpoints (%d to kill) at %s",
            variantId, counts.parts, counts.colliders, colliderMode, counts.fixtures, weakpoints,
            titan.getWeakpointsToKill(), position);

        if (!TitanTrio.attach(store, root)) {
            LOGGER.at(Level.WARNING).log(
                "Titan '%s' spawned without Encounter/brain trio; fighting with legacy AI only", variantId);
        }

        return new Result(root, counts.parts, weakpoints, counts.inventoryCapacities(), null);
    }

    /** Part tally for one spawn. */
    private static final class Counts {
        private int parts;
        private int colliders;
        /** Shell voxels entered as weakpoints, for a titan whose own geometry is the target. */
        private int shells;
        private int fixtures;
        /** Slot count per container index, sparse and only as long as the highest index claimed. */
        @Nonnull
        private final Int2IntMap capacities = new Int2IntOpenHashMap();

        private void claim(final int inventoryIndex, final int capacity) {
            if (inventoryIndex < 0 || capacity <= 0) return;
            capacities.put(inventoryIndex, capacity);
        }

        @Nonnull
        private int[] inventoryCapacities() {
            if (capacities.isEmpty()) return NO_INVENTORIES;

            int highest = -1;
            for (final int index : capacities.keySet()) highest = Math.max(highest, index);

            final var out = new int[highest + 1];
            for (final var entry : capacities.int2IntEntrySet()) out[entry.getIntKey()] = entry.getIntValue();
            return out;
        }
    }

    @Nonnull
    private static Counts spawnParts(@Nonnull final Store<EntityStore> store,
                                     @Nonnull final Ref<EntityStore> root,
                                     @Nonnull final TitanComponent titan,
                                     @Nonnull final TitanVariantAsset variant,
                                     @Nonnull final TitanSkeletonAsset skeleton,
                                     @Nonnull final ColliderMode colliderMode,
                                     final float shellHealth,
                                     @Nonnull final Vector3d rootPosition) {

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

        // Zero for anything that simply appears, which is every combat titan and the egg.
        final float fxRadius = variant.getSpawnFxRadius();

        // Numbered across the whole titan rather than per bone, so two chests declared at the same index
        // become containers 0 and 1 in a stable order however the geometry is split up.
        final var fixtureRuns = new HashMap<String, Integer>();

        for (final TitanBoneDef bone : skeleton.getBones()) {
            final PrefabVoxels voxels = PrefabVoxelReader.read(
                bone.getPrefab(), variant.getRockType(), bone.getSliceMinY(), bone.getSliceMaxY(),
                bone.getPrefabRotation());
            if (voxels.isEmpty()) continue;

            final boolean hollow = bone.isHollow();
            final Vector3d pivot = bone.getPivot() != null ? new Vector3d(bone.getPivot()) : voxels.defaultPivot();
            final float boneScale = voxelScale * bone.getScale();
            final double mirror = bone.isMirrorX() ? -1.0 : 1.0;
            final int stride = decimationStride(hollow ? voxels.surfaceSize() : voxels.size(), bone.getMaxParts());
            final boolean boneWantsColliders = colliderConfig != null && bone.isCollider() && bone.getColliderStride() > 0;

            pose.getWorldRotation(bone.getIndex(), rotation);

            // Collected and handed over in one go. Adding an entity walks every holder and ref system that
            // could care about it and then drains a command buffer; paying that per voxel is most of the
            // hitch when a titan appears, and the bulk call does the walk once for the whole bone. Sized to
            // the voxel count, which is the most this loop can produce.
            @SuppressWarnings("unchecked")
            final Holder<EntityStore>[] holders = new Holder[voxels.size()];
            int holderCount = 0;

            int index = -1;
            // Counted separately from `index` so the collider stride thins the candidates evenly. Keying it
            // off the raw voxel index would interact with the prefab's shape and could pick none at all.
            int colliderCandidate = -1;

            for (final PrefabVoxels.Voxel voxel : voxels.getVoxels()) {
                final var fixture = variant.findFixture(voxel.blockKey());

                // A fixture is the point of the block being there, so it survives both the hollow cull and
                // the part budget. Culling a chest for sitting inside the walls would leave a house whose
                // furniture cannot be reached.
                if (fixture == null) {
                    // Ahead of the stride count so a part budget thins the shell evenly instead of being
                    // spent on filling that is never spawned.
                    if (hollow && !voxel.surface()) continue;

                    index++;
                    if (stride > 1 && index % stride != 0) continue;
                }

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

                final Holder<EntityStore> holder = TitanPartBuilder.buildVoxel(
                    store, root, voxel.blockKey(), worldPos, rotation, voxel.rotation(), boneScale,
                    bone.getIndex(), local, collider, colliderConfig);

                final boolean usable;
                if (fixture != null) {
                    final int run = fixtureRuns.merge(fixture.getBlock(), 1, Integer::sum) - 1;
                    final int inventoryIndex = fixture.getInventoryIndex() + run;
                    usable = TitanPartBuilder.attachFixture(holder, root, fixture, inventoryIndex, boneScale);
                    if (usable) {
                        counts.fixtures++;
                        counts.claim(inventoryIndex, fixture.getCapacity());
                    }
                } else if (bone.isUsable()) {
                    // The bone's own blocks answer for the creature as a whole; see TitanBoneDef.isUsable.
                    usable = TitanPartBuilder.makeUsable(holder, bone.getUseHint());
                } else {
                    usable = true;
                }

                if (!usable) {
                    LOGGER.at(Level.WARNING).atMostEvery(1, TimeUnit.MINUTES).log(
                        "Titan RootInteraction '%s' is not loaded; '%s' will not be usable",
                        TitanPartBuilder.FIXTURE_INTERACTION, variant.getId());
                }

                if (fxRadius > 0) {
                    TitanPartBuilder.attachSpawnFx(holder, worldPos, rootPosition, fxRadius,
                        variant.getSpawnFxDuration(), variant.getSpawnFxStagger());
                }

                counts.parts++;
                if (collider) counts.colliders++;

                // A shell voxel is added on its own rather than with the bulk of the bone, because the
                // titan tracks its weakpoints by reference and the bulk call does not promise which
                // reference came from which holder. Shell bones are small — a shell is a surface, and the
                // whole egg is under a hundred blocks — so the per-entity cost does not matter here.
                if (bone.isShell()) {
                    TitanPartBuilder.makeShell(holder, root, bone.getIndex(), new Vector3d(local), shellHealth);
                    final Ref<EntityStore> ref = store.addEntity(holder, AddReason.SPAWN);
                    if (ref != null) {
                        titan.getWeakpoints().add(ref);
                        counts.shells++;
                    }
                    continue;
                }

                holders[holderCount++] = holder;
            }

            if (holderCount > 0) store.addEntities(holders, 0, holderCount, AddReason.SPAWN);
        }

        return counts;
    }

    private static int spawnWeakpoints(@Nonnull final Store<EntityStore> store,
                                       @Nonnull final Ref<EntityStore> root,
                                       @Nonnull final TitanComponent titan,
                                       @Nonnull final TitanVariantAsset variant,
                                       @Nonnull final TitanSkeletonAsset skeleton,
                                       @Nonnull final Random random,
                                       final float nodeHealth) {

        final String modelId = variant.getWeakpointModel();
        final TitanSocketDef[] sockets = skeleton.getWeakpointSockets();
        if (modelId == null || sockets.length == 0) {
            // A pet is meant to be unkillable, and one whose own geometry is the target carries its
            // weakpoints on its bones instead of on sockets, so neither wants telling about it.
            if (!variant.isPet()) {
                LOGGER.at(Level.WARNING).log("Titan variant '%s' has no weakpoint model or the skeleton declares no sockets; it will be unkillable",
                    variant.getId());
            }
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

        for (final int socketIndex : chooseSockets(variant, sockets, nodeWidth * SOCKET_SPACING, random)) {
            final TitanSocketDef socket = sockets[socketIndex];
            final int bone = socket.getBoneIndex();
            if (bone < 0) continue;

            // Sockets are authored on the body surface, so the direction from the bone's pivot out to one
            // is the surface normal there. Aiming the ore's growth axis along it makes a chest node jut
            // forwards and a flank node jut sideways, and backing the node down that axis beds it into the
            // rock whichever face it landed on. A socket on a bone that pivots at its joint rather than its
            // centre declares its own normal instead.
            local.set(socket.getOffset());
            outward.set(socket.getNormal() != null ? socket.getNormal() : local);
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
                store, root, modelId, variant.getWeakpointScale(), nodeHealth,
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
     * <p>Sockets are authored all over the body, so shuffling them and taking a handful means two titans of
     * the same variant are climbed and attacked differently. The first pass skips picks closer together
     * than {@code minSeparation} model units so the ore clusters do not grow through each other; a second
     * pass without that rule still meets the quota on a skeleton whose sockets are crowded together.
     */
    @Nonnull
    private static int[] chooseSockets(@Nonnull final TitanVariantAsset variant,
                                       @Nonnull final TitanSocketDef[] sockets,
                                       final double minSeparation,
                                       @Nonnull final Random random) {

        final int min = Math.max(1, variant.getWeakpointCountMin());
        final int max = Math.max(min, variant.getWeakpointCountMax());
        final int wanted = Math.min(sockets.length, min + random.nextInt(max - min + 1));

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
        final int bone = sockets[candidate].getBoneIndex();
        for (int i = 0; i < count; i++) {
            final TitanSocketDef other = sockets[chosen[i]];
            // Offsets are bone-local, so only sockets on the same bone share a space. On a rig with four
            // identical limbs, comparing across bones would read the matching spot on every leg as the same
            // point and reject all but one.
            if (other.getBoneIndex() != bone) continue;
            if (other.getOffset().distanceSquared(offset) < minSq) return false;
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
