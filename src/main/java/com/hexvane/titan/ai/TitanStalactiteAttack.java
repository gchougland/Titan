package com.hexvane.titan.ai;

import com.hexvane.titan.anim.TitanPose;
import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.combat.TitanSmashAttack;
import com.hexvane.titan.combat.TitanSound;
import com.hexvane.titan.combat.TitanTelegraph;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanState;
import com.hexvane.titan.ik.GroundSampler;
import com.hexvane.titan.spawn.PrefabVoxelReader;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Committed ground strikes shed from the actual underside of the Temple's island. */
public final class TitanStalactiteAttack {
    public static final String MODEL = "Titan_Temple_Stalactite";
    private static final double GRAVITY = 24;
    private static final double STONE_HEIGHT = 3.5;
    private static final float RECOVERY = 1.8f;
    private static final int MAX_STONES = 18;

    public static final class Stone {
        public final Vector3d from, landing;
        public final float release, fall;
        public Ref<EntityStore> entity;
        public boolean landed;
        Stone(Vector3d from, Vector3d landing, float release) {
            this.from = from; this.landing = landing; this.release = release;
            this.fall = (float)Math.sqrt(2 * (from.y - landing.y) / GRAVITY);
        }
    }

    /** Runtime only: a reload cancels this move rather than replaying already landed damage. */
    public static final class Rain {
        public final List<Stone> stones = new ArrayList<>();
        public boolean previousRain;
        float radius, damage, windup, end;
    }

    private TitanStalactiteAttack() { }

    public static boolean tryBegin(Store<EntityStore> store, CommandBuffer<EntityStore> cb,
                                   TitanComponent titan, TitanVariantAsset variant) {
        var rain = titan.stalactites;
        if (rain.previousRain) { rain.previousRain = false; return false; }
        if (variant.getStalactiteChance() <= 0
            || ThreadLocalRandom.current().nextFloat() >= variant.getStalactiteChance()) return false;
        return begin(store, cb, titan, variant);
    }

    /** Used by attack selection and the server regression fixture. All destinations are fixed here. */
    public static boolean begin(Store<EntityStore> store, CommandBuffer<EntityStore> cb,
                                TitanComponent titan, TitanVariantAsset variant) {
        if (titan.getState() == TitanState.DYING || titan.getPose() == null || titan.getSkeleton() == null || variant.getStalactiteChance() <= 0) return false;
        int body = titan.getSkeleton().indexOfBone("Body_Mesh");
        if (body < 0 || ModelAsset.getAssetMap().getAsset(MODEL) == null) return false;
        var rain = titan.stalactites;
        clear(titan, cb);
        rain.radius = Math.max(1, Math.min(5, variant.getStalactiteRadius()));
        rain.damage = Math.max(0, variant.getStalactiteDamage());
        rain.windup = Math.max(1.5f, variant.getStalactiteWindupSeconds());
        rain.end = 0;
        var bones = titan.getSkeleton().getBones();
        var voxels = PrefabVoxelReader.read(bones[body].getPrefab(), variant.getRockType());
        var matrix = titan.getPose().getWorld(body);
        var inverse = new Matrix4d(matrix).invert();
        var hub = matrix.transformPosition(new Vector3d());
        var chunks = store.getExternalData().getWorld().getChunkStore();
        var targets = new ArrayList<Ref<EntityStore>>();
        if (titan.getTarget() != null) targets.add(titan.getTarget());
        for (var player : store.getExternalData().getWorld().getPlayerRefs()) {
            var ref = player.getReference();
            if (ref != null && !targets.contains(ref)) targets.add(ref);
        }
        // Give every nearby player a central strike before spending the budget on surrounding stones.
        for (int wave = 0; wave < 3; wave++) for (var target : targets) {
            if (rain.stones.size() >= MAX_STONES) break;
            if (!target.isValid() || cb.getComponent(target, DeathComponent.getComponentType()) != null) continue;
            var tx = cb.getComponent(target, TransformComponent.getComponentType());
            if (tx == null || tx.getPosition().distanceSquared(hub) > 80 * 80) continue;
            var aim = new Vector3d(tx.getPosition());
            double angle = Math.atan2(aim.z - hub.z, aim.x - hub.x) + Math.PI / 2;
            double offset = wave == 0 ? 0 : (wave == 1 ? 1 : -1) * (rain.radius * 2 + .7);
            aim.add(Math.cos(angle) * offset, 0, Math.sin(angle) * offset);
            double ground = GroundSampler.sample(chunks, aim.x, aim.y, aim.z, 2, 32);
            if (!GroundSampler.isValid(ground)) continue;
            aim.y = ground;
            if (rain.stones.stream().anyMatch(s -> horizontalSquared(s.landing, aim) < Math.pow(rain.radius * 2 + .2, 2))) continue;
            // Find a real prefab column directly overhead, accounting for the island pivot and yaw.
            var local = inverse.transformPosition(new Vector3d(aim)).add(bones[body].getPivot());
            int x = (int)Math.floor(local.x), z = (int)Math.floor(local.z), bottom = Integer.MAX_VALUE;
            for (var voxel : voxels.getVoxels())
                if (voxel.x() == x && voxel.z() == z) bottom = Math.min(bottom, voxel.y());
            if (bottom == Integer.MAX_VALUE) continue;
            var surface = matrix.transformPosition(new Vector3d(local.x, bottom, local.z).sub(bones[body].getPivot()));
            var from = new Vector3d(aim.x, surface.y - STONE_HEIGHT + .25, aim.z);
            if (from.y - ground < 4) continue;
            var stone = new Stone(from, new Vector3d(aim), rain.windup + wave * .3f);
            stone.entity = spawn(cb, from);
            rain.stones.add(stone);
            rain.end = Math.max(rain.end, stone.release + stone.fall + RECOVERY);
            ring(cb, variant, stone, rain.radius);
            TitanTelegraph.burst(cb, "Block_Land_Hard_Stone", surface, 2);
        }
        if (rain.stones.isEmpty()) return false;
        rain.previousRain = true;
        titan.setState(TitanState.STALACTITES);
        titan.getVelocity().set(0);
        TitanSound.play(cb, "SFX_Temple_Stalactite_Rumble", hub);
        return true;
    }

    public static Vector3d position(Stone stone, float age) {
        double t = Math.max(0, age - stone.release);
        return new Vector3d(stone.from).sub(0, Math.min(stone.from.y - stone.landing.y, .5 * GRAVITY * t * t), 0);
    }

    public static void tick(Store<EntityStore> store, CommandBuffer<EntityStore> cb, Ref<EntityStore> self,
                            TitanComponent titan, TitanVariantAsset variant, float dt) {
        var rain = titan.stalactites;
        titan.getVelocity().set(0);
        float age = titan.getStateTime();
        boolean pulse = titan.consumePulse(dt, TitanTelegraph.pulseInterval(rain.windup - age));
        for (var stone : rain.stones) {
            if (stone.landed) continue;
            if (pulse) {
                ring(cb, variant, stone, rain.radius);
                TitanTelegraph.burst(cb, "Block_Land_Hard_Stone", position(stone, age), .75f);
            }
            if (stone.entity != null && stone.entity.isValid()) {
                var tx = cb.getComponent(stone.entity, TransformComponent.getComponentType());
                if (tx != null) tx.setPosition(position(stone, age));
            }
            if (age >= stone.release + stone.fall) {
                stone.landed = true;
                if (stone.entity != null && stone.entity.isValid()) cb.removeEntity(stone.entity, RemoveReason.REMOVE);
                stone.entity = null;
                TitanSmashAttack.execute(store, cb, self, stone.landing, rain.radius, rain.damage, 0, 0,
                    null, "SFX_Golem_Earth_Stomp_Impact");
                TitanTelegraph.burst(cb, "Block_Land_Hard_Stone", stone.landing, rain.radius);
            }
        }
        if (rain.stones.isEmpty() || age >= rain.end) {
            clear(titan, cb);
            titan.setAttackCooldown(variant.getAttackCooldown());
            titan.setState(TitanState.IDLE);
        }
    }

    private static void ring(CommandBuffer<EntityStore> cb, TitanVariantAsset variant, Stone stone, float radius) {
        // The already sampled landing is also the damage center. Do not snap the ring to a different ledge.
        TitanTelegraph.ring(cb, null, variant.getTelegraphRingParticle(), new Vector3d(stone.landing).add(0, .15, 0), radius, 0);
    }

    /** Tremble only the island; planted legs remain under IK control. The envelope settles smoothly. */
    public static void pose(TitanComponent titan, TitanSkeletonAsset skeleton, TitanPose pose) {
        if (titan.getState() != TitanState.STALACTITES) return;
        int body = skeleton.indexOfBone("Body_Mesh");
        if (body < 0) return;
        double t = titan.getStateTime(), windup = titan.stalactites.windup;
        double envelope = Math.min(1, t / .5) * Math.max(0, Math.min(1, (windup + .5 - t) / .5));
        pose.getLocalTranslation(body).y += Math.sin(t * 30) * .12 * envelope;
        pose.getLocalRotation(body).rotateX(Math.sin(t * 23) * .006 * envelope)
            .rotateZ(Math.sin(t * 29) * .006 * envelope);
    }

    private static Ref<EntityStore> spawn(CommandBuffer<EntityStore> cb, Vector3d at) {
        var holder = EntityStore.REGISTRY.newHolder();
        var model = Model.createStaticScaledModel(ModelAsset.getAssetMap().getAsset(MODEL), 1);
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(at), new Rotation3f()));
        holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
        holder.addComponent(BoundingBox.getComponentType(), new BoundingBox(model.getBoundingBox()));
        holder.addComponent(HeadRotation.getComponentType(), new HeadRotation(new Rotation3f()));
        holder.addComponent(UUIDComponent.getComponentType(), new UUIDComponent(UUID.randomUUID()));
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(cb.getExternalData().takeNextNetworkId()));
        holder.ensureComponent(Intangible.getComponentType());
        holder.ensureComponent(EntityModule.get().getVisibleComponentType());
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        return cb.addEntity(holder, AddReason.SPAWN);
    }

    public static void clear(TitanComponent titan, CommandBuffer<EntityStore> cb) {
        for (var stone : titan.stalactites.stones)
            if (stone.entity != null && stone.entity.isValid()) cb.removeEntity(stone.entity, RemoveReason.REMOVE);
        titan.stalactites.stones.clear();
    }

    public static void clear(TitanComponent titan, Store<EntityStore> store) {
        var entities = titan.stalactites.stones.stream().map(stone -> stone.entity)
            .filter(ref -> ref != null && ref.isValid()).toList();
        titan.stalactites.stones.clear();
        if (entities.isEmpty()) return;
        // Holder removal runs while the store is locked. Match the brain/encounter teardown path.
        store.getExternalData().getWorld().execute(() -> {
            for (var entity : entities) if (entity.isValid()) store.removeEntity(entity, RemoveReason.REMOVE);
        });
    }

    private static double horizontalSquared(Vector3d a, Vector3d b) {
        return (a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z);
    }
}
