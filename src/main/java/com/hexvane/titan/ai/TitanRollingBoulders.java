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
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Two finite, terrain-following hazards. Their lifetime is independent of the boss's next attack. */
public final class TitanRollingBoulders {
    public static final String MODEL = "Titan_Temple_Rolling_Boulder";
    public static final double RADIUS = 1.65, MAX_SPEED = 13.5, TURN_RATE = .72;
    public static final float FORM_SECONDS = 2.8f, ROLL_SECONDS = 12;
    private static final double GRAVITY = 24, ACCELERATION = 2.8;
    public enum Stage { FORMING, FALLING, ROLLING, SHATTERED }

    public static final class Rock {
        public Ref<EntityStore> entity, target;
        public Stage stage = Stage.FORMING;
        public final Vector3d socket, position, landing;
        public float age, rollingAge, pulse, sound, damage;
        public double heading, speed = 4, spin, fallVelocity;
        Rock(Vector3d socket, Vector3d position, Vector3d landing, Ref<EntityStore> target, float damage) {
            this.socket = socket; this.position = position; this.landing = landing;
            this.target = target; this.damage = damage;
        }
    }

    public static final class Volley {
        public final List<Rock> rocks = new ArrayList<>();
        public boolean previousVolley;
    }

    private TitanRollingBoulders() { }

    public static boolean tryBegin(Store<EntityStore> store, CommandBuffer<EntityStore> cb,
                                   TitanComponent titan, TitanVariantAsset variant) {
        if (!titan.rollingBoulders.rocks.isEmpty()) return false;
        if (titan.rollingBoulders.previousVolley) { titan.rollingBoulders.previousVolley = false; return false; }
        return variant.getRollingBoulderChance() > 0
            && ThreadLocalRandom.current().nextFloat() < variant.getRollingBoulderChance()
            && begin(store, cb, titan, variant);
    }

    public static boolean begin(Store<EntityStore> store, CommandBuffer<EntityStore> cb,
                                TitanComponent titan, TitanVariantAsset variant) {
        if (titan.getState() == TitanState.DYING || titan.getPose() == null || titan.getSkeleton() == null
            || variant.getRollingBoulderChance() <= 0 || !titan.rollingBoulders.rocks.isEmpty()) return false;
        int body = titan.getSkeleton().indexOfBone("Body_Mesh");
        if (body < 0 || ModelAsset.getAssetMap().getAsset(MODEL) == null) return false;
        var matrix = titan.getPose().getWorld(body);
        var hub = matrix.transformPosition(new Vector3d());
        var players = new ArrayList<Ref<EntityStore>>();
        for (var player : store.getExternalData().getWorld().getPlayerRefs()) {
            var ref = player.getReference();
            if (alive(cb, ref) && cb.getComponent(ref, TransformComponent.getComponentType()).getPosition().distanceSquared(hub) < 60 * 60)
                players.add(ref);
        }
        if (players.isEmpty()) return false;
        if (players.remove(titan.getTarget())) players.addFirst(titan.getTarget());
        var bone = titan.getSkeleton().getBones()[body];
        var voxels = PrefabVoxelReader.read(bone.getPrefab(), variant.getRockType());
        var chunks = store.getExternalData().getWorld().getChunkStore();
        for (int i = 0; i < 2; i++) {
            // Two sockets between the legs, measured from real stone in the island prefab.
            double localX = i == 0 ? -5 : 5;
            int x = (int)Math.floor(localX / bone.getScale() + bone.getPivot().x);
            int z = (int)Math.floor(bone.getPivot().z), bottom = Integer.MAX_VALUE;
            for (var voxel : voxels.getVoxels()) if (voxel.x() == x && voxel.z() == z) bottom = Math.min(bottom, voxel.y());
            if (bottom == Integer.MAX_VALUE) continue;
            var socket = new Vector3d(localX, (bottom - bone.getPivot().y) * bone.getScale() - RADIUS * .85 / titan.getScale(), 0);
            var at = matrix.transformPosition(new Vector3d(socket));
            double floor = GroundSampler.sample(chunks, at.x, at.y, at.z, 0, 64);
            if (!GroundSampler.isValid(floor) || at.y - floor < RADIUS * 2) continue;
            var rock = new Rock(socket, at, new Vector3d(at.x, floor, at.z), players.get(i % players.size()),
                Math.max(0, variant.getRollingBoulderDamage()));
            rock.entity = spawn(cb, at);
            titan.rollingBoulders.rocks.add(rock);

            TitanSound.play(cb, "SFX_Temple_Boulder_Form", at);
        }
        if (titan.rollingBoulders.rocks.isEmpty()) return false;
        titan.rollingBoulders.previousVolley = true;
        titan.setState(TitanState.BOULDER_FORMATION);
        titan.getVelocity().set(0);
        return true;
    }

    /** The boss resumes fighting before the rolling hazards expire. */
    public static void tickFormation(TitanComponent titan) {
        titan.getVelocity().set(0);
        if (titan.getStateTime() >= FORM_SECONDS + 1.6f || titan.rollingBoulders.rocks.isEmpty()) {
            titan.setState(TitanState.IDLE);
            titan.setAttackCooldown(2.2f);
        }
    }

    /** Called on every alive AI tick, including stomps, rain and recovery. */
    public static void tick(Store<EntityStore> store, CommandBuffer<EntityStore> cb, Ref<EntityStore> root,
                            TitanComponent titan, float dt) {
        if (titan.rollingBoulders.rocks.isEmpty()) return;
        var chunks = store.getExternalData().getWorld().getChunkStore();
        for (var rock : titan.rollingBoulders.rocks) {
            if (rock.entity == null || !rock.entity.isValid()) { rock.stage = Stage.SHATTERED; continue; }
            if (!alive(cb, rock.target)) { shatter(store, cb, root, rock, false); continue; }
            rock.age += Math.max(0, dt);
            if (rock.age > FORM_SECONDS + ROLL_SECONDS + 5 || rock.position.distanceSquared(titan.getHome()) > 90 * 90) {
                shatter(store, cb, root, rock, false); continue;
            }
            rock.pulse -= dt; rock.sound -= dt;
            // Short time and distance steps retain wall/leg/player collision even on a slow server tick.
            int steps = Math.max(1, (int)Math.ceil(Math.min(dt, 2) / .02));
            double step = Math.min(dt, 2) / steps;
            double startAge = rock.age - Math.min(dt, 2);
            for (int i = 0; i < steps && rock.stage != Stage.SHATTERED; i++) {
                double age = startAge + (i + 1) * step;
                if (rock.stage == Stage.FORMING) {
                    int body = titan.getSkeleton().indexOfBone("Body_Mesh");
                    rock.position.set(titan.getPose().getWorld(body).transformPosition(new Vector3d(rock.socket)));
                    if (age < FORM_SECONDS) continue;
                    rock.stage = Stage.FALLING;
                    var animation = cb.getComponent(rock.entity, ActiveAnimationComponent.getComponentType());
                    if (animation != null) animation.setPlayingAnimation(AnimationSlot.Movement, "Complete");
                    AnimationUtils.playAnimation(rock.entity, AnimationSlot.Movement, "Complete", true, cb);
                    TitanSound.play(cb, "SFX_Temple_Boulder_Release", rock.position);
                }
                if (rock.stage == Stage.FALLING) {
                    rock.fallVelocity += GRAVITY * step;
                    var next = new Vector3d(rock.position).add(0, -rock.fallVelocity * step, 0);
                    double ground = GroundSampler.sample(chunks, next.x, rock.position.y - RADIUS + .5, next.z, 0, 64);
                    if (!GroundSampler.isValid(ground)) { shatter(store, cb, root, rock, false); break; }
                    if (next.y <= ground + RADIUS) {
                        next.y = ground + RADIUS;
                        rock.position.set(next);
                        if (hitsLeg(titan, next) || terrainBlocked(chunks, next)) { shatter(store, cb, root, rock, false); break; }
                        rock.stage = Stage.ROLLING;
                        var aim = cb.getComponent(rock.target, TransformComponent.getComponentType()).getPosition();
                        rock.heading = Math.atan2(aim.z - next.z, aim.x - next.x);
                        TitanTelegraph.burst(cb, "Temple_Boulder_Impact", new Vector3d(next).add(0, -RADIUS, 0), 1);
                        TitanSound.play(cb, "SFX_Temple_Boulder_Land", next);
                    } else rock.position.set(next);
                } else if (rock.stage == Stage.ROLLING) {
                    rock.rollingAge += (float)step;
                    if (rock.rollingAge >= ROLL_SECONDS) { shatter(store, cb, root, rock, false); break; }
                    var target = cb.getComponent(rock.target, TransformComponent.getComponentType()).getPosition();
                    rock.heading = steer(rock.heading, Math.atan2(target.z - rock.position.z, target.x - rock.position.x), step);
                    rock.speed = Math.min(MAX_SPEED, rock.speed + ACCELERATION * step);
                    var next = new Vector3d(rock.position).add(Math.cos(rock.heading) * rock.speed * step, 0, Math.sin(rock.heading) * rock.speed * step);
                    double floor = GroundSampler.sample(chunks, next.x, rock.position.y - RADIUS, next.z, 1, 4);
                    if (!GroundSampler.isValid(floor) || Math.abs(floor + RADIUS - rock.position.y) > 1.05) {
                        shatter(store, cb, root, rock, false); break;
                    }
                    next.y = floor + RADIUS;
                    if (terrainBlocked(chunks, next) || hitsLeg(titan, next)) {
                        shatter(store, cb, root, rock, false); break;
                    }
                    rock.position.set(next);
                    rock.spin += rock.speed * step / RADIUS;
                }
                if (rock.stage != Stage.FORMING && hitsPlayer(store, cb, rock.position)) {
                    shatter(store, cb, root, rock, true); break;
                }
            }
            if (rock.stage == Stage.SHATTERED) continue;
            if (rock.stage == Stage.FORMING) {
                // New viewers receive a short remaining assembly clip rather than restarting the windup.
                var animation = cb.getComponent(rock.entity, ActiveAnimationComponent.getComponentType());
                if (animation != null) animation.setPlayingAnimation(AnimationSlot.Movement, "Form_" + Math.min(13, (int)(rock.age / .2f)));
            }
            var tx = cb.getComponent(rock.entity, TransformComponent.getComponentType());
            if (tx != null) {
                tx.setPosition(new Vector3d(rock.position));
                if (rock.stage == Stage.ROLLING) tx.setRotation(new Rotation3f((float)rock.spin, (float)(-rock.heading - Math.PI / 2), 0));
            }
            if (rock.pulse <= 0) {
                rock.pulse = .18f;
                if (rock.stage == Stage.FORMING) {
                    TitanTelegraph.burst(cb, "Temple_Boulder_Form_Dust", new Vector3d(rock.position).add(0, RADIUS, 0), 1);
                    if (rock.age > 1.6) TitanTelegraph.burst(cb, "Temple_Boulder_Sparks", rock.position, 1);
                } else if (rock.stage == Stage.ROLLING) {
                    TitanTelegraph.burst(cb, "Temple_Boulder_Trail", new Vector3d(rock.position).add(0, -RADIUS + .12, 0), 1);
                }
                if (rock.stage != Stage.ROLLING)
                    TitanTelegraph.ring(cb, null, titan.getVariant().getTelegraphRingParticle(), new Vector3d(rock.landing).add(0, .15, 0), RADIUS + .4, 0);
            }
            if (rock.stage == Stage.ROLLING && rock.sound <= 0) {
                rock.sound = .8f;
                TitanSound.play(cb, "SFX_Temple_Boulder_Roll", rock.position);
            }
        }
        titan.rollingBoulders.rocks.removeIf(rock -> rock.stage == Stage.SHATTERED);
    }

    /** Bounded turn rate is the dodge window; never snap toward a close player. */
    public static double steer(double heading, double desired, double dt) {
        double delta = Math.atan2(Math.sin(desired - heading), Math.cos(desired - heading));
        return heading + Math.max(-TURN_RATE * dt, Math.min(TURN_RATE * dt, delta));
    }

    public static boolean sphereBox(Vector3d p, double radius, Vector3d min, Vector3d max) {
        double distance = 0;
        for (int axis = 0; axis < 3; axis++) {
            double v = p.get(axis), nearest = Math.max(min.get(axis), Math.min(max.get(axis), v));
            distance += (v - nearest) * (v - nearest);
        }
        return distance < radius * radius;
    }

    public static boolean hitsLeg(TitanComponent titan, Vector3d center) {
        for (var core : titan.getSolidCores()) {
            var matrix = titan.getPose().getWorld(core.bone());
            var local = new Matrix4d(matrix).invert().transformPosition(new Vector3d(center)).sub(core.center());
            double scale = matrix.transformDirection(new Vector3d(1, 0, 0)).length();
            if (sphereBox(local, RADIUS / scale, new Vector3d(core.halfSize()).negate(), core.halfSize())) return true;
        }
        return false;
    }

    public static boolean terrainBlocked(ChunkStore chunks, Vector3d center) {
        for (int x = (int)Math.floor(center.x - RADIUS); x <= Math.floor(center.x + RADIUS); x++)
            for (int y = (int)Math.floor(center.y - RADIUS + .08); y <= Math.floor(center.y + RADIUS); y++)
                for (int z = (int)Math.floor(center.z - RADIUS); z <= Math.floor(center.z + RADIUS); z++) {
                    if (!sphereBox(center, RADIUS - .06, new Vector3d(x,y,z), new Vector3d(x+1,y+1,z+1))) continue;
                    if (GroundSampler.blockId(chunks,x,y,z) == BlockType.UNKNOWN_ID || GroundSampler.isSolid(chunks,x,y,z)) return true;
                }
        return false;
    }

    private static boolean hitsPlayer(Store<EntityStore> store, CommandBuffer<EntityStore> cb, Vector3d center) {
        for (var player : store.getExternalData().getWorld().getPlayerRefs()) {
            var ref = player.getReference();
            if (!alive(cb, ref)) continue;
            var p = cb.getComponent(ref, TransformComponent.getComponentType()).getPosition();
            var box = cb.getComponent(ref, BoundingBox.getComponentType());
            var min = box == null ? new Vector3d(-.4, 0, -.4) : new Vector3d(box.getBoundingBox().min);
            var max = box == null ? new Vector3d(.4, 1.8, .4) : new Vector3d(box.getBoundingBox().max);
            if (sphereBox(center, RADIUS, min.add(p), max.add(p))) return true;
        }
        return false;
    }

    private static boolean alive(ComponentAccessor<EntityStore> cb, Ref<EntityStore> ref) {
        return ref != null && ref.isValid() && cb.getComponent(ref, TransformComponent.getComponentType()) != null
            && cb.getComponent(ref, DeathComponent.getComponentType()) == null;
    }

    private static void shatter(Store<EntityStore> store, CommandBuffer<EntityStore> cb, Ref<EntityStore> root, Rock rock, boolean damage) {
        if (rock.stage == Stage.SHATTERED) return;
        rock.stage = Stage.SHATTERED;
        if (rock.entity != null && rock.entity.isValid()) cb.removeEntity(rock.entity, RemoveReason.REMOVE);
        rock.entity = null;
        if (damage) TitanSmashAttack.execute(store, cb, root, new Vector3d(rock.position).add(0, -RADIUS, 0), RADIUS + .4,
            rock.damage, 4, .15, null, null);
        TitanTelegraph.burst(cb, "Temple_Boulder_Shatter", rock.position, 1);
        TitanSound.play(cb, "SFX_Temple_Boulder_Shatter", rock.position);
    }

    public static void pose(TitanComponent titan, TitanSkeletonAsset skeleton, TitanPose pose) {
        if (titan.getState() != TitanState.BOULDER_FORMATION) return;
        int body = skeleton.indexOfBone("Body_Mesh");
        if (body < 0) return;
        double t = titan.getStateTime(), envelope = Math.min(1, t / .4) * Math.max(0, Math.min(1, (FORM_SECONDS + .4 - t) / .4));
        pose.getLocalTranslation(body).y += Math.sin(t * 19) * .09 * envelope;
    }

    private static Ref<EntityStore> spawn(CommandBuffer<EntityStore> cb, Vector3d at) {
        var holder = EntityStore.REGISTRY.newHolder();
        var model = Model.createScaledModel(ModelAsset.getAssetMap().getAsset(MODEL), 1);
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(at), new Rotation3f()));
        holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
        var animation = new ActiveAnimationComponent();
        animation.setPlayingAnimation(AnimationSlot.Movement, "Form_0");
        holder.addComponent(ActiveAnimationComponent.getComponentType(), animation);
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
        for (var rock : titan.rollingBoulders.rocks) if (rock.entity != null && rock.entity.isValid()) cb.removeEntity(rock.entity, RemoveReason.REMOVE);
        titan.rollingBoulders.rocks.clear();
    }

    public static void clear(TitanComponent titan, Store<EntityStore> store) {
        var refs = titan.rollingBoulders.rocks.stream().map(rock -> rock.entity).filter(ref -> ref != null && ref.isValid()).toList();
        titan.rollingBoulders.rocks.clear();
        if (!refs.isEmpty()) store.getExternalData().getWorld().execute(() -> {
            for (var ref : refs) if (ref.isValid()) store.removeEntity(ref, RemoveReason.REMOVE);
        });
    }
}
