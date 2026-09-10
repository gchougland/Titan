package com.hexvane.titan.dunewyrm;

import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.combat.TitanEncounterScale;
import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.entity.TitanPartComponent;
import com.hexvane.titan.ik.GroundSampler;
import com.hexvane.titan.physics.DebrisBurst;
import com.hexvane.titan.spawn.BlockRotations;
import com.hexvane.titan.spawn.PrefabVoxelReader;
import com.hexvane.titan.spawn.PrefabVoxels;
import com.hexvane.titan.spawn.TitanGroundPrefab;
import com.hexvane.titan.spawn.TitanPartBuilder;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollisionConfig;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Builds a Dunewyrm snake: invisible root plus voxel flocks for head/jaw/tongue/body/tail.
 *
 * <p>Must run on the world thread outside of ticking — wrap in {@code world.execute(...)} from systems.
 */
public final class DunewyrmSpawner {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final double ROOT_BOX = 2.0;

    private DunewyrmSpawner() {
    }

    public record Result(@Nullable Ref<EntityStore> root, int parts, @Nullable String error) {
        public boolean ok() {
            return root != null;
        }

        @Nonnull
        static Result failure(@Nonnull final String error) {
            return new Result(null, 0, error);
        }
    }

    @Nonnull
    public static Result spawn(@Nonnull final Store<EntityStore> store,
                               @Nonnull final Vector3d position,
                               final float yaw,
                               final long seed,
                               final boolean paintGround) {
        return spawn(store, position, yaw, seed, paintGround, null, null);
    }

    /**
     * @param encounterId existing encounter for a split child, or null for a fresh fight
     * @param bodyPrefs   optional body segment roll (prefab + mirror) reused when rebuilding after split
     */
    @Nonnull
    public static Result spawn(@Nonnull final Store<EntityStore> store,
                               @Nonnull final Vector3d position,
                               final float yaw,
                               final long seed,
                               final boolean paintGround,
                               @Nullable final UUID encounterId,
                               @Nullable final List<DunewyrmSegment> bodyPrefs) {

        final TitanVariantAsset variant = TitanVariantAsset.find("Dunewyrm");
        if (variant == null) return Result.failure("unknown variant 'Dunewyrm'");
        if (!TitanConfig.get().isVariantEnabled(variant.getId())) {
            return Result.failure("variant 'Dunewyrm' is turned off under DisabledVariants in config.json");
        }

        final var random = new Random(seed);
        final HitboxCollisionConfig colliderConfig =
            HitboxCollisionConfig.getAssetMap().getAsset(DunewyrmTuning.COLLIDER_CONFIG);
        if (colliderConfig == null) {
            LOGGER.at(Level.WARNING).log("Missing HitboxCollisionConfig '%s'; Dunewyrm will not be climbable",
                DunewyrmTuning.COLLIDER_CONFIG);
        }

        final var worm = new DunewyrmComponent(variant);
        worm.setYaw(yaw);
        worm.getHome().set(position);
        // Pivot is the prefab bottom — sit flush on the spawn ground rather than hovering.
        worm.getHeadPosition().set(position.x, position.y, position.z);
        worm.setState(DunewyrmState.IDLE);
        worm.setAttackCooldown(1.5f);
        worm.setOrbitAngle(yaw);
        worm.setOrbitRadius(DunewyrmTuning.ORBIT_RADIUS
            + (float) ((random.nextDouble() * 2.0 - 1.0) * DunewyrmTuning.ORBIT_RADIUS_JITTER));

        final UUID eid = encounterId != null ? encounterId : UUID.randomUUID();
        worm.setEncounterId(eid);
        worm.setPrimary(encounterId == null);

        final var encounter = DunewyrmEncounter.getOrCreate(eid);
        if (encounterId == null) encounter.setLeveling(com.hexvane.titan.compat.LevelingCompatibility.near(
            store, position, Math.max(32.0, variant.getWakeRadius())));
        final float healthScale = encounter.getLeveling().health() * TitanEncounterScale.healthScaleNear(
            store, position, Math.max(32.0, variant.getWakeRadius()));
        final float segmentHealth = DunewyrmTuning.SEGMENT_HEALTH * healthScale;

        final List<DunewyrmSegment> chain = worm.getSegments();
        chain.add(new DunewyrmSegment(DunewyrmSegmentRole.HEAD, DunewyrmTuning.PREFAB_HEAD, false, 0f));
        chain.add(new DunewyrmSegment(DunewyrmSegmentRole.JAW, DunewyrmTuning.PREFAB_JAW, false, 0f));
        chain.add(new DunewyrmSegment(DunewyrmSegmentRole.TONGUE, DunewyrmTuning.PREFAB_TONGUE, false, 0f));

        if (bodyPrefs != null && !bodyPrefs.isEmpty()) {
            for (final DunewyrmSegment body : bodyPrefs) {
                final float hp = body.getHealth() > 0 ? body.getHealth() : segmentHealth;
                chain.add(new DunewyrmSegment(DunewyrmSegmentRole.BODY, body.getPrefabKey(), body.isMirrored(), hp));
            }
        } else {
            for (int i = 0; i < DunewyrmTuning.BODY_COUNT; i++) {
                final String prefab = random.nextBoolean()
                    ? DunewyrmTuning.PREFAB_SEGMENT1
                    : DunewyrmTuning.PREFAB_SEGMENT2;
                chain.add(new DunewyrmSegment(DunewyrmSegmentRole.BODY, prefab, random.nextBoolean(),
                    segmentHealth));
            }
        }

        chain.add(new DunewyrmSegment(DunewyrmSegmentRole.TAIL, DunewyrmTuning.PREFAB_TAIL, false, 0f));

        final float bodyHealth = worm.bodyHealth();
        worm.setInitialBodyHealth(bodyHealth);

        if (encounterId == null) {
            encounter.setInitialBodyHealth(bodyHealth);
        }
        encounter.addSnake();

        final int pathPieces = Math.max(8, chain.size() * 2);
        worm.getPath().seedCurl(
            worm.getHeadPosition().x, worm.getHeadPosition().y, worm.getHeadPosition().z, yaw,
            DunewyrmTuning.SEGMENT_SPACING, pathPieces, DunewyrmTuning.SPAWN_CURL);
        layoutAlongPath(worm, store.getExternalData().getWorld().getChunkStore());

        final var rootHolder = EntityStore.REGISTRY.newHolder();
        rootHolder.addComponent(TransformComponent.getComponentType(),
            new TransformComponent(new Vector3d(position), new Rotation3f(0, yaw, 0)));
        rootHolder.addComponent(BoundingBox.getComponentType(),
            new BoundingBox(new Box(-ROOT_BOX, 0, -ROOT_BOX, ROOT_BOX, ROOT_BOX * 2, ROOT_BOX)));
        rootHolder.addComponent(DunewyrmComponent.getComponentType(), worm);
        rootHolder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        rootHolder.addComponent(NetworkId.getComponentType(),
            new NetworkId(store.getExternalData().takeNextNetworkId()));
        rootHolder.ensureComponent(EntityModule.get().getVisibleComponentType());
        final String displayName = variant.getDisplayName() != null ? variant.getDisplayName() : "Dunewyrm";
        rootHolder.addComponent(DisplayNameComponent.getComponentType(),
            new DisplayNameComponent(Message.raw(displayName)));
        final var stats = rootHolder.ensureAndGetComponent(EntityStatMap.getComponentType());
        stats.update();
        TitanPartBuilder.applyHealth(stats, Math.max(1f, encounter.getInitialBodyHealth()));

        final Ref<EntityStore> root = store.addEntity(rootHolder, AddReason.SPAWN);
        if (root == null) {
            encounter.removeSnake();
            return Result.failure("the world would not accept an entity at " + position
                + "; the chunk is probably not loaded");
        }

        if (paintGround) {
            TitanGroundPrefab.paint(store.getExternalData().getWorld(), store, variant.getGroundPrefab(), position);
        }

        int parts = 0;
        for (int i = 0; i < chain.size(); i++) {
            parts += spawnSegmentVoxels(store, root, worm, i, colliderConfig);
        }
        worm.setSegmentsDirty(true);
        syncRootHealth(store, root, encounter);

        LOGGER.at(Level.INFO).log("Spawned Dunewyrm with %d parts (%d body segments) at %s",
            parts, worm.bodyCount(), position);
        return new Result(root, parts, null);
    }

    /**
     * Spawns voxels for one segment. Returns how many entities were added.
     */
    public static int spawnSegmentVoxels(@Nonnull final Store<EntityStore> store,
                                         @Nonnull final Ref<EntityStore> root,
                                         @Nonnull final DunewyrmComponent worm,
                                         final int segmentIndex,
                                         @Nullable final HitboxCollisionConfig colliderConfig) {

        final DunewyrmSegment segment = worm.getSegments().get(segmentIndex);
        destroySegmentVoxels(store, segment);

        final PrefabVoxels voxels = PrefabVoxelReader.read(segment.getPrefabKey());
        if (voxels.isEmpty()) {
            LOGGER.at(Level.WARNING).log("Dunewyrm prefab missing or empty: %s", segment.getPrefabKey());
            return 0;
        }

        final Vector3d pivot = voxels.defaultPivot();
        // Centre the long body segments on their midpoint so spacing reads correctly.
        final Vector3d center = voxels.center();
        pivot.set(center.x, voxels.defaultPivot().y, center.z);
        // Tongue: AABB centre sits between the two fork tips (stem is one column, fork spans x).
        // Do not shove the pivot — any extra half-block offset parked the stem beside the mouth.
        if (segment.getRole() == DunewyrmSegmentRole.TONGUE) {
            pivot.set(center);
        }

        final double mirror = segment.isMirrored() ? -1.0 : 1.0;
        final float yaw = segment.getYaw();
        final var rotation = new Rotation3f(0, yaw, 0);
        final var worldPos = new Vector3d();
        final var scratchQ = new Quaterniond();
        final var scratchE = new Vector3d();

        @SuppressWarnings("unchecked")
        final Holder<EntityStore>[] holders = new Holder[voxels.size() + 1];
        int holderCount = 0;
        int colliderCandidate = -1;

        for (final PrefabVoxels.Voxel voxel : voxels.getVoxels()) {
            if (!voxel.surface()) continue;

            final var local = new Vector3d(
                (voxel.x() + 0.5 - pivot.x) * mirror,
                (voxel.y() + 0.5 - pivot.y),
                (voxel.z() + 0.5 - pivot.z)
            );

            // Rotate local offset by segment yaw into world (same basis as DunewyrmPartSyncSystem).
            final double cos = Math.cos(yaw);
            final double sin = Math.sin(yaw);
            final double lx = local.x * cos + local.z * sin;
            final double lz = -local.x * sin + local.z * cos;
            worldPos.set(segment.getPosition().x + lx, segment.getPosition().y + local.y,
                segment.getPosition().z + lz);

            boolean collider = false;
            if (colliderConfig != null && (voxel.standable() || voxel.surface())) {
                colliderCandidate++;
                // Thin colliders for cost while keeping a climbable shell.
                collider = colliderCandidate % 2 == 0;
            }

            final var worldRotation = new Rotation3f(rotation);
            BlockRotations.compose(worldRotation, voxel.rotation(), scratchQ, scratchE);

            final float partScale = switch (segment.getRole()) {
                case HEAD, JAW, TONGUE -> DunewyrmTuning.HEAD_SCALE;
                default -> 1f;
            };

            final Holder<EntityStore> holder = TitanPartBuilder.buildBlock(
                store, voxel.blockKey(), worldPos, worldRotation, partScale);
            holder.addComponent(DunewyrmPartComponent.getComponentType(),
                new DunewyrmPartComponent(root, segmentIndex, local, voxel.rotation(), partScale, collider));

            if (collider && colliderConfig != null) {
                holder.addComponent(HitboxCollision.getComponentType(), new HitboxCollision(colliderConfig));
            }

            if (segment.getRole().hasHealth()) {
                holder.addComponent(DunewyrmHitComponent.getComponentType(),
                    new DunewyrmHitComponent(root, segmentIndex));
                final var hitStats = holder.ensureAndGetComponent(EntityStatMap.getComponentType());
                hitStats.update();
                TitanPartBuilder.applyHealth(hitStats, segment.getMaxHealth());
            }

            holders[holderCount++] = holder;
        }

        if (colliderConfig != null && segment.getRole() != DunewyrmSegmentRole.TONGUE) {
            holders[holderCount++] = buildSegmentSolidFill(
                store, root, worm, segmentIndex, voxels, pivot, mirror, rotation, colliderConfig);
        }

        if (holderCount == 0) return 0;

        @SuppressWarnings("unchecked")
        final Holder<EntityStore>[] batch = new Holder[holderCount];
        System.arraycopy(holders, 0, batch, 0, holderCount);
        final Ref<EntityStore>[] refs = store.addEntities(batch, AddReason.SPAWN);
        if (refs != null) {
            for (final Ref<EntityStore> ref : refs) {
                if (ref != null) segment.getVoxels().add(ref);
            }
        }
        return holderCount;
    }

    @Nonnull
    private static Holder<EntityStore> buildSegmentSolidFill(
        @Nonnull final Store<EntityStore> store,
        @Nonnull final Ref<EntityStore> root,
        @Nonnull final DunewyrmComponent worm,
        final int segmentIndex,
        @Nonnull final PrefabVoxels voxels,
        @Nonnull final Vector3d pivot,
        final double mirror,
        @Nonnull final Rotation3f rotation,
        @Nonnull final HitboxCollisionConfig colliderConfig) {

        final DunewyrmSegment segment = worm.getSegments().get(segmentIndex);
        // The head leads every charge, so its core fills almost the whole skull: a player cannot slip
        // between the thinned shell voxels and an undersized core and end up riding inside the mouth.
        final float inset = segment.getRole() == DunewyrmSegmentRole.HEAD
            ? DunewyrmTuning.HEAD_SOLID_FILL_INSET
            : DunewyrmTuning.SOLID_FILL_INSET;
        final float partScale = switch (segment.getRole()) {
            case HEAD, JAW, TONGUE -> DunewyrmTuning.HEAD_SCALE;
            default -> 1f;
        };
        final double hx = voxels.sizeX() * 0.5 * inset * partScale;
        final double hy = voxels.sizeY() * 0.5 * inset * partScale;
        final double hz = voxels.sizeZ() * 0.5 * inset * partScale;
        final Vector3d center = voxels.center();
        final var local = new Vector3d(
            (center.x - pivot.x) * mirror * partScale,
            (center.y - pivot.y) * partScale,
            (center.z - pivot.z) * partScale);

        final float yaw = segment.getYaw();
        final double cos = Math.cos(yaw);
        final double sin = Math.sin(yaw);
        final double lx = local.x * cos + local.z * sin;
        final double lz = -local.x * sin + local.z * cos;
        final var worldPos = new Vector3d(
            segment.getPosition().x + lx,
            segment.getPosition().y + local.y,
            segment.getPosition().z + lz);

        final var box = new Box(-hx, -hy, -hz, hx, hy, hz);
        final Holder<EntityStore> holder = TitanPartBuilder.buildSolidFill(
            store, worldPos, rotation, box, colliderConfig);
        holder.addComponent(DunewyrmPartComponent.getComponentType(),
            new DunewyrmPartComponent(root, segmentIndex, local, 0, partScale, true));
        return holder;
    }

    public static void destroySegmentVoxels(@Nonnull final Store<EntityStore> store,
                                            @Nonnull final DunewyrmSegment segment) {
        for (final Ref<EntityStore> ref : segment.getVoxels()) {
            if (ref != null && ref.isValid()) {
                store.removeEntity(ref, RemoveReason.REMOVE);
            }
        }
        segment.clearVoxels();
    }

    /**
     * Hands a killed segment's blocks to {@link com.hexvane.titan.system.TitanRagdollSystem} so they burst
     * apart like other titans, instead of vanishing.
     */
    public static void releaseSegmentAsDebris(@Nonnull final Store<EntityStore> store,
                                              @Nonnull final DunewyrmSegment segment) {
        final Vector3d burst = new Vector3d();
        final Vector3d spin = new Vector3d();
        final Vector3d offset = new Vector3d();
        final var random = ThreadLocalRandom.current();
        final Vector3d centre = segment.getPosition();

        for (final Ref<EntityStore> ref : segment.getVoxels()) {
            if (ref == null || !ref.isValid()) continue;

            final DunewyrmPartComponent part = store.getComponent(ref, DunewyrmPartComponent.getComponentType());
            final TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (part == null || transform == null) {
                store.removeEntity(ref, RemoveReason.REMOVE);
                continue;
            }

            offset.set(transform.getPosition()).sub(centre);
            DebrisBurst.solve(offset, Math.max(2.5, offset.length() * 0.9), 4.5, burst);
            DebrisBurst.spin(random, 3.0, spin);

            // Detached before Dunewyrm sync can delete the entity for a missing owner.
            final TitanPartComponent rubble = new TitanPartComponent();
            rubble.detach(burst, spin, random.nextFloat(3.5f, 8f));
            store.putComponent(ref, TitanPartComponent.getComponentType(), rubble);
            store.tryRemoveComponent(ref, DunewyrmPartComponent.getComponentType());
            store.tryRemoveComponent(ref, DunewyrmHitComponent.getComponentType());
            store.tryRemoveComponent(ref, HitboxCollision.getComponentType());
        }
        segment.clearVoxels();
    }

    public static void destroyAllVoxels(@Nonnull final Store<EntityStore> store,
                                        @Nonnull final DunewyrmComponent worm) {
        for (final DunewyrmSegment segment : worm.getSegments()) {
            destroySegmentVoxels(store, segment);
        }
    }

    /** Release every remaining segment as physics debris (full snake death). */
    public static void releaseAllAsDebris(@Nonnull final Store<EntityStore> store,
                                          @Nonnull final DunewyrmComponent worm) {
        for (final DunewyrmSegment segment : worm.getSegments()) {
            releaseSegmentAsDebris(store, segment);
        }
    }

    /** Places each segment transform along the path buffer behind the head. */
    public static void layoutAlongPath(@Nonnull final DunewyrmComponent worm) {
        layoutAlongPath(worm, null);
    }

    /**
     * Structural (re)layout: snaps every segment straight onto the terrain with no easing.
     */
    public static void layoutAlongPath(@Nonnull final DunewyrmComponent worm,
                                       @Nullable final ChunkStore chunks) {
        layoutAlongPath(worm, chunks, -1f);
    }

    /**
     * Places each segment along the path. When {@code chunks} is provided, each body samples its own
     * ground height so the snake drapes over terrain instead of floating at the head's Y. With a positive
     * {@code dt} the drape height eases toward the terrain instead of stepping, so a one-block hump lifts
     * the segment over a few ticks rather than snapping it (and every voxel on it) up at once.
     */
    public static void layoutAlongPath(@Nonnull final DunewyrmComponent worm,
                                       @Nullable final ChunkStore chunks,
                                       final float dt) {
        final List<DunewyrmSegment> segments = worm.getSegments();
        if (segments.isEmpty()) return;

        final DunewyrmSegment headSeg = worm.findRole(DunewyrmSegmentRole.HEAD);
        final float rise = worm.getCobraRise();
        final float lean = worm.getCobraLean();
        final float headY = (float) worm.getHeadPosition().y;
        final float yaw0 = worm.getYaw();
        final double fx = DunewyrmAiSystem.forwardX(yaw0);
        final double fz = DunewyrmAiSystem.forwardZ(yaw0);

        // Spacing chain: HEAD → bodies → TAIL. Jaw/tongue are parented to the head afterward.
        final int[] chain = new int[segments.size()];
        int chainLen = 0;
        for (int i = 0; i < segments.size(); i++) {
            final DunewyrmSegmentRole role = segments.get(i).getRole();
            if (role == DunewyrmSegmentRole.JAW || role == DunewyrmSegmentRole.TONGUE) continue;
            chain[chainLen++] = i;
        }
        if (chainLen == 0) return;

        final Vector3d[] pts = new Vector3d[chainLen];
        final float[] yaws = new float[chainLen];
        final float[] pitches = new float[chainLen];
        final Vector3d ideal = new Vector3d();
        final float[] yawOut = new float[1];

        // Head tip — reared up and leaned forward when cobring.
        pts[0] = new Vector3d(
            worm.getHeadPosition().x + fx * lean,
            headY + rise - worm.getTunnelDepth(),
            worm.getHeadPosition().z + fz * lean);
        yaws[0] = yaw0;
        pitches[0] = rise > 0.05f
            ? (float) Math.atan2(rise * 0.55 + lean * 0.25, DunewyrmTuning.HEAD_BODY_SPACING)
            : 0f;

        int bodiesBefore = 0;
        double traveled = 0;
        for (int c = 1; c < chainLen; c++) {
            final DunewyrmSegment segment = segments.get(chain[c]);
            final double gap = switch (segment.getRole()) {
                case BODY -> bodiesBefore == 0
                    ? DunewyrmTuning.HEAD_BODY_SPACING
                    : DunewyrmTuning.SEGMENT_SPACING;
                case TAIL -> DunewyrmTuning.TAIL_SPACING;
                default -> DunewyrmTuning.SEGMENT_SPACING;
            };
            traveled += gap;

            if (!worm.getPath().sampleBehind(traveled, ideal, yawOut)) {
                ideal.set(
                    worm.getHeadPosition().x - fx * traveled,
                    headY,
                    worm.getHeadPosition().z - fz * traveled);
                yawOut[0] = yaw0;
            }

            // Ideal rests on the ground path; S-curve lifts the front into a continuous rearing arc.
            final double curveLen = DunewyrmTuning.HEAD_BODY_SPACING
                + DunewyrmTuning.SEGMENT_SPACING * Math.max(1, DunewyrmTuning.COBRA_CURVE_BODIES);
            final double t = rise <= 0.05f ? 1.0 : Math.min(1.0, traveled / curveLen);
            final double s = t * t * (3.0 - 2.0 * t);
            final double sCurve = (1.0 - s) * (1.0 - s) * (1.0 + 2.0 * s);
            final double height = rise * sCurve;
            final double neckArch = rise > 0.05f
                ? -rise * DunewyrmTuning.COBRA_NECK_ARCH * Math.sin(Math.PI * (1.0 - s))
                : 0.0;
            final double forwardPull = lean * sCurve + neckArch;

            ideal.x += fx * forwardPull;
            ideal.z += fz * forwardPull;

            double groundY = headY;
            if (chunks != null && rise <= 0.05f && worm.getTunnelDepth() < 0.1f) {
                // Drape on the surrounding floor — never perch body links on pillar tops.
                groundY = cachedDrapeHeight(segment, chunks, ideal.x, ideal.z, headY);
                groundY = easeGround(segment, groundY, dt);
            } else {
                segment.setSmoothGroundY(Double.NaN);
            }
            // Each link may only step so far from the previous joint (real terrain drape).
            final double prevY = pts[c - 1].y;
            final float step = DunewyrmTuning.SEGMENT_GROUND_STEP;
            if (groundY > prevY + step) groundY = prevY + step;
            if (groundY < prevY - step) groundY = prevY - step;

            ideal.y = groundY + height - worm.getTunnelDepth();

            // Exact spacing from the previous joint so segment ends stay welded.
            final Vector3d prev = pts[c - 1];
            double dx = ideal.x - prev.x;
            double dy = ideal.y - prev.y;
            double dz = ideal.z - prev.z;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-5) {
                dx = -fx;
                dy = -height * 0.05;
                dz = -fz;
                len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            final double inv = gap / len;
            pts[c] = new Vector3d(prev.x + dx * inv, prev.y + dy * inv, prev.z + dz * inv);

            final double tx = prev.x - pts[c].x;
            final double ty = prev.y - pts[c].y;
            final double tz = prev.z - pts[c].z;
            final double horiz = Math.sqrt(tx * tx + tz * tz);
            yaws[c] = (float) Math.atan2(-tx, -tz);
            float pitch = horiz < 1e-5 ? (ty >= 0 ? (float) (Math.PI * 0.5) : (float) (-Math.PI * 0.5))
                : (float) Math.atan2(ty, horiz);
            // On the ground the body should read as lying over the terrain, not tilting to match every
            // joint-to-joint height difference: soft deadzone (no snap from 0 to the threshold), scaled
            // down, and capped. Rearing keeps the true pitch so the neck curve stays welded.
            if (rise <= 0.05f) {
                final float excess = Math.abs(pitch) - DunewyrmTuning.GROUND_PITCH_DEADZONE;
                pitch = excess <= 0f
                    ? 0f
                    : Math.copySign(
                        Math.min(excess * DunewyrmTuning.GROUND_PITCH_SCALE, DunewyrmTuning.GROUND_PITCH_MAX),
                        pitch);
            }
            pitches[c] = pitch;

            if (segment.getRole() == DunewyrmSegmentRole.BODY) {
                bodiesBefore++;
            }
        }

        if (chainLen > 1) {
            pitches[0] = pitches[1];
            yaws[0] = yaws[1];
        }

        for (int c = 0; c < chainLen; c++) {
            final DunewyrmSegment segment = segments.get(chain[c]);
            segment.getPosition().set(pts[c]);
            segment.setYaw(yaws[c]);
            segment.setPitch(pitches[c]);
        }

        if (worm.getState() == DunewyrmState.FLAIL) {
            applyFlailOffsets(worm, pts, chain, chainLen);
        }

        // Jaw / tongue hang off the posed head.
        if (headSeg != null) {
            final float hy = headSeg.getYaw();
            final float hp = headSeg.getPitch();
            final double hfx = DunewyrmAiSystem.forwardX(hy);
            final double hfz = DunewyrmAiSystem.forwardZ(hy);
            final double cosP = Math.cos(hp);
            final double sinP = Math.sin(hp);

            final DunewyrmSegment jaw = worm.findRole(DunewyrmSegmentRole.JAW);
            if (jaw != null) {
                final float open = worm.getJawOpen();
                final double reach = 1.2 + open * 1.5;
                jaw.getPosition().set(
                    headSeg.getPosition().x + hfx * reach * cosP,
                    headSeg.getPosition().y - 0.6 - open * 1.4 + reach * sinP,
                    headSeg.getPosition().z + hfz * reach * cosP);
                jaw.setYaw(hy);
                jaw.setPitch(hp - open * 0.35f);
            }

            final DunewyrmSegment tongue = worm.findRole(DunewyrmSegmentRole.TONGUE);
            if (tongue != null) {
                final float flick = worm.getTongueActive() > 0f ? DunewyrmTuning.TONGUE_EXTEND : 0.15f;
                final float reach = DunewyrmTuning.TONGUE_MOUTH + flick;
                // Mouth axis only — fork midpoint is the pivot, so no lateral shove.
                tongue.getPosition().set(
                    headSeg.getPosition().x + hfx * reach * cosP,
                    headSeg.getPosition().y + reach * sinP,
                    headSeg.getPosition().z + hfz * reach * cosP);
                // Prefab's fork faces the rear of the model; flip so the fork leads out of the mouth.
                tongue.setYaw(hy + DunewyrmTuning.TONGUE_YAW_OFFSET);
                tongue.setPitch(0f);
            }
        }
    }

    private static void applyFlailOffsets(@Nonnull final DunewyrmComponent worm,
                                          @Nonnull final Vector3d[] pts,
                                          @Nonnull final int[] chain,
                                          final int chainLen) {
        final float wave = (float) Math.sin(worm.getStateTimer() * 14.0);
        final float fade = 1f - Math.min(1f, worm.getStateTimer() / DunewyrmTuning.FLAIL_DURATION);
        final List<DunewyrmSegment> segments = worm.getSegments();
        for (int c = 0; c < chainLen; c++) {
            final DunewyrmSegment segment = segments.get(chain[c]);
            final float along = chainLen <= 1 ? 0f : (float) c / (chainLen - 1);
            final float amp = DunewyrmTuning.FLAIL_AMP * fade * (0.35f + 0.65f * (1f - along));
            final float lift = wave * amp * (float) Math.sin(along * Math.PI + worm.getStateTimer() * 3.0);
            segment.getPosition().y = pts[c].y + lift;
            segment.setPitch(lift * 0.08f);
        }
    }

    public static void syncRootHealth(@Nonnull final Store<EntityStore> store,
                                      @Nonnull final Ref<EntityStore> root,
                                      @Nonnull final DunewyrmEncounter encounter) {
        final var stats = store.getComponent(root, EntityStatMap.getComponentType());
        if (stats == null) return;
        final float max = Math.max(1f, encounter.getInitialBodyHealth());
        final float current = Math.max(0f, encounter.getRemainingBodyHealth());
        TitanPartBuilder.applyHealth(stats, max);
        // Drain displayed health to match remaining by applying a temporary deficit via maximize then...
        // EntityStatMap lacks a simple set; maximize fills to max. Health sync system will rewrite each tick.
        encounter.setRemainingBodyHealth(current);
    }

    /**
     * Rebuilds visual head/jaw/tongue/tail voxels after a structural change, and refreshes body voxels
     * so a shortened neck does not leave half a segment stranded.
     */
    public static void rebuildVisuals(@Nonnull final Store<EntityStore> store,
                                      @Nonnull final Ref<EntityStore> root,
                                      @Nonnull final DunewyrmComponent worm) {
        final HitboxCollisionConfig colliderConfig =
            HitboxCollisionConfig.getAssetMap().getAsset(DunewyrmTuning.COLLIDER_CONFIG);
        for (int i = 0; i < worm.getSegments().size(); i++) {
            spawnSegmentVoxels(store, root, worm, i, colliderConfig);
        }
    }

    @Nonnull
    public static List<DunewyrmSegment> copyBodySegments(@Nonnull final DunewyrmComponent worm) {
        final List<DunewyrmSegment> bodies = new ArrayList<>();
        for (final DunewyrmSegment segment : worm.getSegments()) {
            if (segment.getRole() == DunewyrmSegmentRole.BODY) {
                bodies.add(segment.copyMeta());
            }
        }
        return bodies;
    }

    /**
     * Ground height for body draping: use the column top unless it is an isolated pillar/structure
     * above the neighbourhood floor — then rest on the floor so the snake hangs off ledges instead of
     * floating the whole body up there.
     */
    /**
     * Eases the segment's resting height toward the sampled terrain at {@link DunewyrmTuning#DRAPE_EASE_RATE}.
     * Terrain heights are whole blocks, so without this every hump was a one-block vertical snap of the
     * whole segment in a single tick. Snaps when there is no previous value, no {@code dt} (structural
     * relayout) or the jump is too large to be a bump (teleport, respawn).
     */
    private static double easeGround(@Nonnull final DunewyrmSegment segment, final double target, final float dt) {
        final double prev = segment.getSmoothGroundY();
        if (dt <= 0f || Double.isNaN(prev) || Math.abs(target - prev) > DunewyrmTuning.DRAPE_EASE_SNAP) {
            segment.setSmoothGroundY(target);
            return target;
        }
        final double maxStep = DunewyrmTuning.DRAPE_EASE_RATE * dt;
        final double eased = prev + Math.max(-maxStep, Math.min(maxStep, target - prev));
        segment.setSmoothGroundY(eased);
        return eased;
    }

    /**
     * The drape sample is a 5×5 column scan per segment; the segment only slides a fraction of a block per
     * tick, so the answer is reused until it has moved off the column it was sampled on.
     */
    private static double cachedDrapeHeight(@Nonnull final DunewyrmSegment segment,
                                            @Nonnull final ChunkStore chunks,
                                            final double x,
                                            final double z,
                                            final double nearY) {
        final double dx = x - segment.getDrapeSampleX();
        final double dz = z - segment.getDrapeSampleZ();
        final double reuse = DunewyrmTuning.DRAPE_RESAMPLE_DISTANCE;
        if (!Double.isNaN(segment.getDrapeSampleY())
            && dx * dx + dz * dz < reuse * reuse
            && Math.abs(segment.getDrapeSampleY() - nearY) < 12.0) {
            return segment.getDrapeSampleY();
        }
        final double y = sampleDrapeHeight(chunks, x, z, nearY);
        segment.setDrapeSample(x, z, y);
        return y;
    }

    private static double sampleDrapeHeight(@Nonnull final ChunkStore chunks,
                                            final double x,
                                            final double z,
                                            final double nearY) {
        final double local = GroundSampler.sample(chunks, x, nearY + 2.0, z, 4, 16);
        if (!GroundSampler.isValid(local)) return nearY;

        final double floor = GroundSampler.sampleLowestInRadius(
            chunks, x, nearY + 2.0, z,
            DunewyrmTuning.FLOOR_SAMPLE_RADIUS, 4, 16);
        if (GroundSampler.isValid(floor) && local > floor + DunewyrmTuning.PILLAR_CLEARANCE) {
            return floor;
        }
        return local;
    }
}
