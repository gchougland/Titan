package com.hexvane.titan.combat;

import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.entity.TitanBoulderComponent;
import com.hexvane.titan.entity.TitanBoulderPartComponent;
import com.hexvane.titan.spawn.PrefabVoxelReader;
import com.hexvane.titan.spawn.PrefabVoxels;
import com.hexvane.titan.spawn.TitanPartBuilder;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds the boulder a titan throws.
 *
 * <p>A boulder reuses the titan's own hand prefab and rock type, and is assembled the same way the body is:
 * a root entity holding the transform with scaled blocks hung off it.
 */
public final class TitanBoulder {

    /**
     * Gravity on a thrown boulder, in blocks per second squared.
     *
     * <p>Kept separate from world and debris gravity because it sets the height of the arc for a given
     * throwing speed, and the arc is what gives the player time to leave the marked circle.
     */
    public static final double GRAVITY = 20.0;

    /** Tumble given to a thrown boulder, in radians per second on each axis. */
    private static final double SPIN = 2.5;
    /** How long the rock is intangible after leaving the hand. See the component for why. */
    private static final float ARM_SECONDS = 0.2f;
    /** Ceiling on how many blocks one boulder is made of, so a large prefab cannot flood the entity count. */
    private static final int MAX_VOXELS = 48;
    /** Launch angle used when the target is out of range, which is the angle of maximum distance. */
    private static final double MAX_RANGE_ANGLE = Math.PI / 4;

    private TitanBoulder() {
    }

    /**
     * Throws a boulder from {@code origin} at {@code target}.
     *
     * <p>Assembling the rock writes entities to the store, which a ticking system may not do, so the build
     * is deferred to the world the same way natural titan spawning is. The launch is solved immediately,
     * because the titan's hand will have moved by the time the deferred work runs.
     *
     * @param voxelScale world size of one prefab block, which for a titan's own hand is its body scale
     */
    public static void throwAt(@Nonnull final Store<EntityStore> store,
                               @Nonnull final Ref<EntityStore> thrower,
                               @Nonnull final TitanVariantAsset variant,
                               @Nonnull final String prefabKey,
                               @Nonnull final Vector3d origin,
                               @Nonnull final Vector3d target,
                               final float voxelScale) {

        final PrefabVoxels voxels = PrefabVoxelReader.read(prefabKey, variant.getRockType());
        if (voxels.isEmpty()) return;

        final var launch = new Vector3d();
        solveLaunch(origin, target, variant.getHurlSpeed(), launch);

        final var from = new Vector3d(origin);
        final var landing = new Vector3d(target);
        final var world = store.getExternalData().getWorld();

        world.execute(() -> build(store, thrower, variant, voxels, from, landing, launch, voxelScale));
    }

    /**
     * Aims a throw of fixed speed so it lands on the target, taking the flatter of the two arcs that get
     * there.
     *
     * <p>Both arcs land on the same spot, but the lofted one hangs for a couple of seconds and peaks a
     * dozen blocks up. The flat solution still arcs to roughly twenty-five degrees at maximum range and
     * arrives in about a second, which is enough time to leave the marked circle at a walk.
     *
     * <p>{@code HurlSpeed} and {@code HurlMaxRange} in the variant files are therefore coupled: the flat
     * solution only exists out to {@code speed² / GRAVITY}, and beyond that the throw falls back to
     * forty-five degrees and lands short. Each variant's range is set inside that limit.
     *
     * <p>Aimed at the target's current position rather than a lead, so a player who keeps moving is not hit.
     *
     * @return {@code false} if the target is out of range at this speed, in which case {@code dest} holds
     *         the furthest throw towards it
     */
    public static boolean solveLaunch(@Nonnull final Vector3d origin,
                                      @Nonnull final Vector3d target,
                                      final double speed,
                                      @Nonnull final Vector3d dest) {

        final double dx = target.x - origin.x;
        final double dz = target.z - origin.z;
        final double horizontal = Math.sqrt(dx * dx + dz * dz);
        final double rise = target.y - origin.y;

        if (horizontal < 1.0e-3) {
            dest.set(0, speed, 0);
            return true;
        }

        final double v2 = speed * speed;
        final double discriminant = v2 * v2 - GRAVITY * (GRAVITY * horizontal * horizontal + 2 * rise * v2);

        // Out of range: 45 degrees throws furthest, so it gets the rock as close as the speed allows.
        final double angle = discriminant < 0
            ? MAX_RANGE_ANGLE
            : Math.atan((v2 - Math.sqrt(discriminant)) / (GRAVITY * horizontal));

        final double flat = speed * Math.cos(angle) / horizontal;
        dest.set(dx * flat, speed * Math.sin(angle), dz * flat);
        return discriminant >= 0;
    }

    /**
     * Where a boulder should leave from: out in front of the titan at chest height, on the line to the
     * target. Close enough to the hand that the throw reads, without starting inside the body.
     */
    @Nonnull
    public static Vector3d resolveReleasePoint(@Nonnull final Vector3d titanPosition,
                                               final float yaw,
                                               final double reach,
                                               final double height,
                                               @Nonnull final Vector3d dest) {
        return dest.set(
            titanPosition.x - Math.sin(yaw) * reach,
            titanPosition.y + height,
            titanPosition.z - Math.cos(yaw) * reach
        );
    }

    private static void build(@Nonnull final Store<EntityStore> store,
                              @Nonnull final Ref<EntityStore> thrower,
                              @Nonnull final TitanVariantAsset variant,
                              @Nonnull final PrefabVoxels voxels,
                              @Nonnull final Vector3d origin,
                              @Nonnull final Vector3d landing,
                              @Nonnull final Vector3d launch,
                              final float voxelScale) {

        // The titan can die between the throw being decided and this running, and a rock thrown by nobody
        // has no damage source and no one to spare from its blast.
        if (!thrower.isValid()) return;

        final var random = ThreadLocalRandom.current();
        final var spin = new Vector3d(
            random.nextDouble(-SPIN, SPIN),
            random.nextDouble(-SPIN, SPIN),
            random.nextDouble(-SPIN, SPIN)
        );

        final var boulder = new TitanBoulderComponent(thrower, launch, spin,
            variant.getHurlDamage(), variant.getHurlRadius(), variant.getHurlKnockback(),
            variant.getImpactParticle(), variant.getImpactSound(), ARM_SECONDS);
        boulder.getLanding().set(landing);

        final var rootHolder = EntityStore.REGISTRY.newHolder();
        rootHolder.addComponent(TransformComponent.getComponentType(),
            new TransformComponent(new Vector3d(origin), new Rotation3f()));
        rootHolder.addComponent(BoundingBox.getComponentType(),
            new BoundingBox(new Box(-voxelScale, -voxelScale, -voxelScale, voxelScale, voxelScale, voxelScale)));
        rootHolder.addComponent(TitanBoulderComponent.getComponentType(), boulder);
        rootHolder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());

        final Ref<EntityStore> root = store.addEntity(rootHolder, AddReason.SPAWN);

        final Vector3d centre = voxels.center();
        final int stride = Math.max(1, (int) Math.ceil((double) voxels.size() / MAX_VOXELS));
        final var identity = new Rotation3f();
        final var local = new Vector3d();
        final var worldPosition = new Vector3d();

        int index = -1;
        for (final PrefabVoxels.Voxel voxel : voxels.getVoxels()) {
            index++;
            if (stride > 1 && index % stride != 0) continue;

            local.set(
                (voxel.x() + 0.5 - centre.x) * voxelScale,
                (voxel.y() + 0.5 - centre.y) * voxelScale,
                (voxel.z() + 0.5 - centre.z) * voxelScale
            );
            worldPosition.set(origin).add(local);

            final var holder = TitanPartBuilder.buildBlock(
                store, voxel.blockKey(), worldPosition, identity, voxelScale);
            holder.addComponent(TitanBoulderPartComponent.getComponentType(),
                new TitanBoulderPartComponent(root, local, voxelScale));

            store.addEntity(holder, AddReason.SPAWN);
        }
    }

    /**
     * The prefab a variant throws: whatever it names, or the caller's fallback when it names nothing.
     *
     * <p>The fallback is the skeleton's own hand, so a titan built on a new rig throws a piece of itself
     * without the variant having to declare a prefab.
     */
    @Nullable
    public static String resolvePrefab(@Nonnull final TitanVariantAsset variant, @Nullable final String fallback) {
        final String named = variant.getHurlPrefab();
        return named != null && !named.isEmpty() ? named : fallback;
    }
}
