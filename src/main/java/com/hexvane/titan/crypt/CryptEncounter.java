package com.hexvane.titan.crypt;

import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.spawn.PrefabVoxelReader;
import com.hexvane.titan.spawn.PrefabVoxels;
import com.hexvane.titan.spawn.TitanPartBuilder;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.damage.*;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.*;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import java.util.*;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Coffin-owned cooperative encounter; all structural changes happen outside the entity tick. */
public final class CryptEncounter {
    private CryptEncounter() { }
    public static void register(PluginBase plugin) {
        var r = plugin.getEntityStoreRegistry();
        CryptBossComponent.TYPE = r.registerComponent(CryptBossComponent.class, CryptBossComponent::new);
        CryptPartComponent.TYPE = r.registerComponent(CryptPartComponent.class, CryptPartComponent::new);
        r.registerSystem(new Tick());
        r.registerSystem(new CryptPartSystem());
        r.registerSystem(new CryptDamageSystem());
        r.registerSystem(new CryptDamageSystem.Root());
        r.registerSystem(new CryptDamageSystem.CinematicGuard());
        r.registerSystem(new Removal());
        r.registerSystem(new CryptMusic.PlayerRemoval());
        r.registerSystem(new CryptCinematic.PlayerRemoval());
        r.registerSystem(new CryptBossBars.PlayerRemoval());
    }
    public static boolean start(Store<EntityStore> store, Ref<EntityStore> site, CryptArena arena, Ref<EntityStore> player) {
        if (!CryptSiteSystem.canStart(store, site, arena)) return false;
        if (!TitanConfig.get().isVariantEnabled("Crypt_Keeper")) return false;
        boolean[] occupied = {false};
        store.forEachChunk(CryptBossComponent.getComponentType(), (chunk, ignored) -> {
            for (int i = 0; i < chunk.size(); i++) {
                var existing = chunk.getComponent(i, CryptBossComponent.getComponentType());
                if (existing != null && existing.arena != null && existing.arena.coffinBlock().equals(arena.coffinBlock())) {
                    occupied[0] = true;
                    return;
                }
            }
        });
        // Pending coffin reservations are allowed; a live root, including queued cleanup, is not.
        if (occupied[0]) return false;
        var boss = new CryptBossComponent();
        boss.site = site; boss.arena = arena;
        int players = living(store, arena).size();
        boss.fight = new CryptFight(Double.doubleToLongBits(arena.point(0, 0, 0).x) ^ System.nanoTime(),
            TitanConfig.get().getWeakpointHealthMultiplier(), players);
        boss.rig = new CryptRig(arena);
        // Fail before publishing the boss if a required prefab is absent.
        for (var b : boss.rig.bones) if (PrefabVoxelReader.read(b.prefab).isEmpty()) return false;
        boss.rig.sample(boss);
        var holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(CryptBossComponent.getComponentType(), boss);
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(arena.point(0, 6, 0), new Rotation3f()));
        holder.addComponent(BoundingBox.getComponentType(), new BoundingBox(new Box(-1, -1, -1, 1, 1, 1)));
        boss.networkId = store.getExternalData().takeNextNetworkId();
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(boss.networkId));
        holder.addComponent(DisplayNameComponent.getComponentType(), new DisplayNameComponent(Message.raw("The Crypt Keeper")));
        holder.ensureComponent(EntityModule.get().getVisibleComponentType());
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        var stats = holder.ensureAndGetComponent(EntityStatMap.getComponentType());
        stats.update(); TitanPartBuilder.applyHealth(stats, boss.fight.maxCrown);
        Ref<EntityStore> root = store.addEntity(holder, AddReason.SPAWN);
        if (root == null) return false;
        try {
            buildParts(store, root, boss);
            for (Ref<EntityStore> ref : living(store, arena)) {
                if (CryptCinematic.play(store, ref, arena, false)) boss.cinematics.add(ref);
                CryptMusic.apply(store, ref); boss.viewers.add(ref);
            }
            tell(store, boss, "The crypt awakens. Break both bracelets; strike the fallen crown.");
            return true;
        } catch (RuntimeException ex) {
            dispose(store, boss);
            if (root.isValid()) store.removeEntity(root, RemoveReason.REMOVE);
            com.hypixel.hytale.logger.HytaleLogger.forEnclosingClass().atWarning().withCause(ex).log("Could not assemble Crypt Keeper");
            return false;
        }
    }
    private static void buildParts(Store<EntityStore> store, Ref<EntityStore> root, CryptBossComponent boss) {
        List<Holder<EntityStore>> holders = new ArrayList<>();
        for (int i = 0; i < boss.rig.bones.size(); i++) {
            var bone = boss.rig.bones.get(i);
            for (var voxel : PrefabVoxelReader.read(bone.prefab).getVoxels()) {
                if (!voxel.surface()) continue;
                holders.add(buildPart(store, root, boss, i, voxel));
            }
        }
        @SuppressWarnings("unchecked") Holder<EntityStore>[] batch = holders.toArray(Holder[]::new);
        Ref<EntityStore>[] refs = store.addEntities(batch, AddReason.SPAWN);
        if (refs != null) for (var ref : refs) if (ref != null) boss.parts.add(ref);
    }

    /** Shared by first assembly and bracelet regeneration, so rebuilt rings stay visible and hittable. */
    private static Holder<EntityStore> buildPart(Store<EntityStore> store, Ref<EntityStore> root,
                                                CryptBossComponent boss, int index, PrefabVoxels.Voxel voxel) {
        var bone = boss.rig.bones.get(index);
        var collider = HitboxCollisionConfig.getAssetMap().getAsset("Titan_Platform");
        var p = new CryptPartComponent();
        p.owner = root; p.bone = index; p.pool = bone.pool;
        // The lowered crown seats into the skull. A 0.005-block face offset
        // keeps its gold surfaces in front of coincident bone faces without moving the rig.
        if (p.pool == 0) p.scale = 1.01f;
        p.blockRotation = CryptBlockRotations.authoredIndex(voxel.rotation(), voxel.blockKey(), bone.mirrored);
        p.offset.set(localOffset(bone, voxel));
        p.platform = bone.platform; p.colliding = p.platform && collider != null && boss.fight.collidableArms();
        Vector3d scaled = new Vector3d(p.offset); scaled.z *= bone.lengthScale;
        Vector3d pos = bone.rotation.transform(scaled).add(bone.position);
        var rot = CryptBlockRotations.compose(bone.rotation, p.blockRotation, new Rotation3f(), new Quaterniond(), new Vector3d());
        var h = TitanPartBuilder.buildBlock(store, voxel.blockKey(), pos, rot, p.scale);
        h.addComponent(CryptPartComponent.getComponentType(), p);
        if (p.colliding) h.addComponent(HitboxCollision.getComponentType(), new HitboxCollision(collider));
        if (p.pool >= 0) {
            var hp = h.ensureAndGetComponent(EntityStatMap.getComponentType()); hp.update();
            TitanPartBuilder.applyHealth(hp, 100000);
        }
        return h;
    }

    private static Vector3d localOffset(CryptRig.Bone bone, PrefabVoxels.Voxel voxel) {
        return new Vector3d((voxel.x() + .5 - bone.pivot.x) * (bone.mirrored ? -1 : 1),
            voxel.y() + .5 - bone.pivot.y, voxel.z() + .5 - bone.pivot.z);
    }

    /** Runs outside store iteration; repairs only absent voxels and never duplicates surviving pieces. */
    static void reconcileBracelets(Store<EntityStore> store, Ref<EntityStore> root, CryptBossComponent boss) {
        if (!root.isValid() || boss.finishing || !boss.fight.damageable()) return;
        Map<Integer, Set<Vector3d>> present = new HashMap<>();
        for (var it = boss.parts.iterator(); it.hasNext();) {
            var ref = it.next();
            if (!ref.isValid()) { it.remove(); continue; }
            var p = store.getComponent(ref, CryptPartComponent.getComponentType());
            if (p == null || p.pool <= 0) continue;
            if (boss.fight.bracelet(p.pool - 1) <= 0) {
                store.removeEntity(ref, RemoveReason.REMOVE); it.remove(); continue;
            }
            present.computeIfAbsent(p.bone, ignored -> new HashSet<>()).add(new Vector3d(p.offset));
        }
        for (int index = 0; index < boss.rig.bones.size(); index++) {
            var bone = boss.rig.bones.get(index);
            if (bone.pool <= 0 || boss.fight.bracelet(bone.pool - 1) <= 0) continue;
            var occupied = present.getOrDefault(index, Set.of());
            for (var voxel : PrefabVoxelReader.read(bone.prefab).getVoxels()) {
                if (!voxel.surface() || occupied.contains(localOffset(bone, voxel))) continue;
                var ref = store.addEntity(buildPart(store, root, boss, index, voxel), AddReason.SPAWN);
                if (ref != null) boss.parts.add(ref);
            }
        }
    }
    static List<Ref<EntityStore>> living(ComponentAccessor<EntityStore> a, CryptArena arena) {
        List<Ref<EntityStore>> found = new ArrayList<>();
        for (var p : a.getExternalData().getWorld().getPlayerRefs()) {
            var ref = p.getReference(); if (ref == null || !ref.isValid()) continue;
            var t = a.getComponent(ref, TransformComponent.getComponentType());
            if (t == null || !arena.contains(t.getPosition()) || a.getComponent(ref, DeathComponent.getComponentType()) != null
                || a.getComponent(ref, Spectating.getComponentType()) != null) continue;
            var hp = a.getComponent(ref, EntityStatMap.getComponentType());
            if (hp != null && hp.get(DefaultEntityStatTypes.getHealth()) != null && hp.get(DefaultEntityStatTypes.getHealth()).get() <= 0) continue;
            found.add(ref);
        }
        return found;
    }
    static void tell(ComponentAccessor<EntityStore> a, CryptBossComponent b, String message) {
        for (var ref : living(a, b.arena)) {
            var p = a.getComponent(ref, PlayerRef.getComponentType()); if (p != null) p.sendMessage(Message.raw(message));
        }
    }
    static void dispose(Store<EntityStore> store, CryptBossComponent b) {
        CryptGrab.abort(store, b);
        for (var ref : b.cinematics) CryptCinematic.restore(store, ref);
        b.cinematics.clear();
        for (var ref : b.viewers) hide(store, b, ref);
        b.viewers.clear();
        for (var ref : b.parts) if (ref.isValid()) store.removeEntity(ref, RemoveReason.REMOVE);
        for (var ref : b.minions) if (ref.isValid()) store.removeEntity(ref, RemoveReason.REMOVE);
        b.parts.clear(); b.minions.clear(); b.hazards.clear();
    }
    static void hide(ComponentAccessor<EntityStore> a, CryptBossComponent b, Ref<EntityStore> ref) {
        if (!ref.isValid()) return;
        var p = a.getComponent(ref, PlayerRef.getComponentType());
        CryptMusic.stop(a, ref);
        if (p != null) CryptBossBars.hide(p, b.networkId);
    }
    public static final class Tick extends EntityTickingSystem<EntityStore> {
        private final Query<EntityStore> query = CryptBossComponent.getComponentType();
        @Override public Query<EntityStore> getQuery() { return query; }
        @Override public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store, CommandBuffer<EntityStore> cb) {
            var b = chunk.getComponent(index, CryptBossComponent.getComponentType());
            var root = chunk.getReferenceTo(index);
            if (b == null || b.finishing) return;
            double step = Math.min(.25, Math.max(0, dt)); b.elapsed += step;
            var players = living(cb, b.arena);
            // Death freezes the victory result; a simultaneous final hit and player death still wins.
            if (b.fight.state() != CryptFight.State.DYING && b.fight.state() != CryptFight.State.FINISHED) {
                b.emptyTime = players.isEmpty() ? b.emptyTime + step : 0;
                if (b.emptyTime >= 2) { finish(cb, root, b, false); return; }
            }
            for (var it = b.cinematics.iterator(); it.hasNext();) {
                var ref = it.next();
                if (!players.contains(ref)) { CryptCinematic.restore(cb, ref); it.remove(); }
            }
            CryptGrab.consider(cb, b, players);
            b.fight.tick(step);
            if (b.revision != b.fight.revision()) {
                b.revision = b.fight.revision(); enter(cb, root, b, players);
            }
            if (b.fight.state() == CryptFight.State.INTRO || b.fight.state() == CryptFight.State.DYING) {
                for (var ref : players) if (!b.cinematics.contains(ref)) {
                    if (CryptCinematic.play(cb, ref, b.arena, b.fight.state() == CryptFight.State.DYING, b.fight.time()))
                        b.cinematics.add(ref);
                }
            }
            aim(cb, b, players);
            if (b.grabbed != null && (b.fight.state() != CryptFight.State.ATTACK || b.fight.move() != CryptFight.Move.GRAB))
                CryptGrab.abort(cb, b);
            b.rig.sample(b);
            // The root remains in the room even if an individual ring voxel's section unloads.
            // Pool health is authoritative; reconstruct missing ring entities at the current wrist pose.
            if (b.elapsed >= b.braceletRepairTime && !b.braceletRepairPending) {
                b.braceletRepairTime = b.elapsed + .5;
                b.braceletRepairPending = true;
                cb.run(s -> {
                    b.braceletRepairPending = false;
                    reconcileBracelets(s, root, b);
                });
            }
            var transform = chunk.getComponent(index, TransformComponent.getComponentType());
            if (transform != null) transform.getPosition().set(b.rig.head());
            var hp = chunk.getComponent(index, EntityStatMap.getComponentType());
            if (b.fight.growPartyTo(players.size()) && hp != null) TitanPartBuilder.applyHealth(hp, b.fight.maxCrown);
            if (hp != null) hp.setStatValue(DefaultEntityStatTypes.getHealth(), Math.max(1, b.fight.crown()));
            boolean fx = b.elapsed >= b.fxTime;
            if (fx) b.fxTime = b.elapsed + .18;
            switch (b.fight.state()) {
                case INTRO -> intro(cb, b, fx);
                case ATTACK -> attack(cb, root, b, players, fx);
                case RECOVERING -> {
                    if (fx) for (int s = 0; s < 2; s++) CryptFx.burst(cb, "Crypt_Bracelet_Regen", b.rig.palm(s), 1);
                }
                case DYING -> {
                    if (!b.fired && b.fight.time() >= CryptFight.DEATH_SECONDS - 1.5) {
                        b.fired = true;
                        CryptFx.burst(cb, "Crypt_Loot_Form", b.arena.rewardPoint(), 1);
                    }
                    if (fx) {
                        if (b.elapsed >= b.cinematicFxTime) {
                            b.cinematicFxTime = b.elapsed + .65;
                            CryptFx.burst(cb, "Crypt_Death", b.rig.head(), .8f);
                        }
                        if (b.fight.time() > 6.8) CryptFx.soulStream(cb, b.rig.head(), b.arena.rewardPoint(), b.elapsed);
                    }
                }
                case FINISHED -> { finish(cb, root, b, true); return; }
                default -> { }
            }
            hazards(cb, root, b, players, fx);
            if (b.elapsed >= b.uiTime) { b.uiTime = b.elapsed + .5; ui(cb, b, players); }
        }
        private void enter(CommandBuffer<EntityStore> cb, Ref<EntityStore> root, CryptBossComponent b, List<Ref<EntityStore>> players) {
            b.fired = false; b.attackHits.clear(); b.castPoints.clear();
            b.hasPreviousAttackHand = false;
            if (b.grabbed != null) CryptGrab.abort(cb, b);
            switch (b.fight.state()) {
                case INTRO -> CryptFx.sound(cb, "SFX_Crypt_Coffin", b.arena.coffin());
                case REST -> {
                    for (var ref : b.cinematics) CryptCinematic.restore(cb, ref);
                    b.cinematics.clear();
                }
                case ATTACK -> {
                    int ordinal = b.attackOrdinal++;
                    if (!players.isEmpty()) {
                        var ref = b.fight.move() == CryptFight.Move.GRAB && players.contains(b.attackTarget)
                            ? b.attackTarget : players.get(Math.floorMod(ordinal, players.size()));
                        b.attackTarget = ref;
                        b.target.set(cb.getComponent(ref, TransformComponent.getComponentType()).getPosition());
                        b.aimSample.set(b.target); b.aimSampleTime = b.elapsed;
                        Vector3d local = CryptRig.local(b.arena, b.target);
                        b.target.set(b.arena.point(Math.clamp(local.x, -31, 31), Math.clamp(local.y, 0, 14), Math.clamp(local.z, 11, 50)));
                    } else { b.attackTarget = null; b.target.set(b.arena.point(0, 0, 27)); }
                    if (b.fight.move() == CryptFight.Move.POISON) b.target.y = b.arena.center().y;
                    b.sweepOffset = CryptAttackGeometry.sweepOffset(CryptRig.local(b.arena, b.target).z);
                    b.grabAttempted = false; b.grabThrown = false;
                    b.beamLeft = (ordinal & 1) == 0;
                    String sound = switch (b.fight.move()) {
                        case SLAM -> "Slam_Charge";
                        case SWEEP_LEFT, SWEEP_RIGHT -> "Sweep_Charge";
                        case BEAM -> "Beam_Charge";
                        case MINIONS -> "Minion_Summon";
                        case BLUE_FIRE -> "Blue_Fire";
                        case POISON -> "Poison";
                        case GRAB -> "Grasp_Charge";
                    };
                    CryptFx.sound(cb, "SFX_Crypt_" + sound, b.rig.head());
                    if (b.fight.move() == CryptFight.Move.GRAB) tell(cb, b, "The Keeper reaches for the bracelet attacker — move out of its grasp!");
                    if (b.fight.move() == CryptFight.Move.BLUE_FIRE) {
                        var spots = b.arena.flames();
                        // Alternate lanes, always preserving at least half the grid as safe paths.
                        for (int i = 0; i < spots.size(); i++) if ((i + b.revision) % 3 == 0) b.castPoints.add(spots.get(i));
                    } else if (b.fight.move() == CryptFight.Move.MINIONS) {
                        var spots = b.arena.minions();
                        for (int i = 0; i < Math.min(spots.size(), b.fight.phase() + 1); i++) b.castPoints.add(spots.get((i + b.revision) % spots.size()));
                    }
                }
                case FALLING -> {
                    b.hazards.clear();
                    b.braceletPressure.clear();
                    tell(cb, b, "Both shackles are broken! Strike the crown beside the podium.");
                    CryptFx.sound(cb, "SFX_Crypt_Bracelet_Break", b.rig.head());
                }
                case STUNNED -> {
                    CryptFx.burst(cb, "Crypt_Slam_Impact", b.arena.point(0, 0, 24), 1.7f);
                    CryptFx.sound(cb, "SFX_Crypt_Slam", b.arena.point(0, 0, 24));
                }
                case RECOVERING -> {
                    tell(cb, b, "The shackles are reforming...");
                    CryptFx.sound(cb, "SFX_Crypt_Bracelet_Regen", b.rig.head());
                }
                case DYING -> {
                    b.hazards.clear();
                    cb.run(s -> CryptSiteSystem.beginVictory(s, b.site, b.arena));
                    cb.run(s -> { for (var ref : b.minions) if (ref.isValid()) s.removeEntity(ref, RemoveReason.REMOVE); b.minions.clear(); });
                    for (var ref : players) if (CryptCinematic.play(cb, ref, b.arena, true)) b.cinematics.add(ref);
                    CryptFx.sound(cb, "SFX_Crypt_Death", b.rig.head());
                }
                default -> { }
            }
        }
        private void intro(CommandBuffer<EntityStore> cb, CryptBossComponent b, boolean fx) {
            double t = b.fight.time();
            boolean plume = fx;
            if (plume && t < 10.5) {
                Vector3d destination = t < 6.5 ? b.arena.pitBottom() : b.rig.head();
                Vector3d end = new Vector3d(b.arena.coffin()).lerp(destination, Math.clamp((t - .7) / 3, 0, 1));
                CryptFx.soulStream(cb, b.arena.coffin(), end, b.elapsed);
            }
            if (plume && t > 6.8 && t < 10.5 && b.elapsed >= b.cinematicFxTime) {
                b.cinematicFxTime = b.elapsed + .55;
                CryptFx.burst(cb, "Crypt_Emergence", b.arena.point(0, 0, 0), 1.5f);
            }
            if (!b.fired && t > 10.8) {
                b.fired = true; CryptFx.burst(cb, "Crypt_Roar", b.rig.mouth(), 2);
                CryptFx.sound(cb, "SFX_Crypt_Roar", b.rig.head());
            }
        }
        private void aim(ComponentAccessor<EntityStore> a, CryptBossComponent b, List<Ref<EntityStore>> players) {
            var look = players.contains(b.attackTarget) ? b.attackTarget : players.stream().min(Comparator.comparingDouble(
                ref -> a.getComponent(ref, TransformComponent.getComponentType()).getPosition().distanceSquared(b.rig.head()))).orElse(null);
            b.hasLookTarget = look != null;
            if (look != null) b.lookTarget.set(a.getComponent(look, TransformComponent.getComponentType()).getPosition()).add(0, 1.5, 0);
            if (b.fight.state() != CryptFight.State.ATTACK) return;
            if (b.attackTarget != null && players.contains(b.attackTarget) &&
                    b.fight.time() < b.fight.windup() - CryptAttackGeometry.AIM_LOCK_SECONDS) {
                Vector3d position = a.getComponent(b.attackTarget, TransformComponent.getComponentType()).getPosition();
                double elapsed = b.elapsed - b.aimSampleTime;
                Vector3d velocity = elapsed > .01 ? new Vector3d(position).sub(b.aimSample).div(elapsed) : new Vector3d();
                velocity.y = 0;
                if (velocity.lengthSquared() > 144) velocity.normalize(12);
                b.aimSample.set(position); b.aimSampleTime = b.elapsed;
                Vector3d local = CryptRig.local(b.arena, new Vector3d(position).fma(.18, velocity));
                b.target.set(b.arena.point(Math.clamp(local.x,-31,31), Math.clamp(local.y,0,14), Math.clamp(local.z,11,50)));
                if (b.fight.move() == CryptFight.Move.POISON) b.target.y = b.arena.center().y;
                b.sweepOffset = CryptAttackGeometry.sweepOffset(local.z);
            }
            if (b.fight.move() == CryptFight.Move.BEAM) b.beamAim.set(CryptAttackGeometry.beamEnd(b));
        }
        private void attack(CommandBuffer<EntityStore> cb, Ref<EntityStore> root, CryptBossComponent b, List<Ref<EntityStore>> players, boolean fx) {
            double t = b.fight.time(), wind = b.fight.windup(), active = t - wind;
            var move = b.fight.move();
            if (t < wind) {
                if (!fx) return;
                switch (move) {
                    case SWEEP_LEFT, SWEEP_RIGHT -> {
                        for (int i = 0; i <= CryptAttackGeometry.SWEEP_WARNING_SEGMENTS; i++) {
                            double u = (double) i / CryptAttackGeometry.SWEEP_WARNING_SEGMENTS;
                            Vector3d at = b.arena.point(CryptAttackGeometry.sweepRight(u, move == CryptFight.Move.SWEEP_LEFT), 0,
                                CryptAttackGeometry.sweepForward(u) + b.sweepOffset);
                            CryptFx.ring(cb, "Crypt_Sweep_Telegraph", at, CryptAttackGeometry.SWEEP_WARNING_RADIUS);
                        }
                    }
                    case SLAM -> CryptFx.ring(cb, "Crypt_Slam_Telegraph", b.target, CryptAttackGeometry.SLAM_RADIUS);
                    case BLUE_FIRE -> { for (var p : b.castPoints) CryptFx.ring(cb, "Crypt_Blue_Fire_Telegraph", p, CryptAttackGeometry.FIRE_RADIUS); }
                    case MINIONS -> { for (var p : b.castPoints) CryptFx.burst(cb, "Crypt_Minion_Summon", p, 1); }
                    case POISON -> CryptFx.ring(cb, "Crypt_Poison_Telegraph", b.target, CryptAttackGeometry.POISON_RADIUS);
                    case GRAB -> CryptFx.ring(cb, CryptFx.GRAB_TELEGRAPH, b.target, CryptAttackGeometry.GRAB_RADIUS);
                    case BEAM -> {
                        CryptFx.burst(cb, "Crypt_Beam_Charge", b.rig.mouth(), 1.2f);
                        Vector3d end = new Vector3d(b.beamAim); end.y = b.arena.center().y + .15;
                        Vector3d start = b.rig.mouth(); start.y = b.arena.point(0, .15, 0).y;
                        CryptFx.line(cb, "Crypt_Beam_Telegraph", start, end, (float) CryptAttackGeometry.BEAM_DAMAGE_RADIUS);
                    }
                }
                return;
            }
            if (!b.fired) {
                b.fired = true;
                switch (move) {
                    case SWEEP_LEFT, SWEEP_RIGHT -> CryptFx.sound(cb, "SFX_Crypt_Sweep", b.rig.head());
                    case SLAM -> { /* impact occurs when the fist reaches the floor, below */ }
                    case BLUE_FIRE -> {
                        for (var p : b.castPoints) b.hazards.add(new CryptBossComponent.Hazard(new Vector3d(p), CryptAttackGeometry.FIRE_RADIUS, b.elapsed + 6, false));
                        CryptFx.sound(cb, "SFX_Crypt_Blue_Fire", b.target);
                    }
                    case MINIONS -> summon(cb, root, b);
                    case POISON -> b.hazards.add(new CryptBossComponent.Hazard(new Vector3d(b.target), CryptAttackGeometry.POISON_RADIUS, b.elapsed + 8, true));
                    case BEAM -> CryptFx.sound(cb, "SFX_Crypt_Beam", b.rig.mouth());
                    case GRAB -> { }
                }
            }
            switch (move) {
                case SWEEP_LEFT, SWEEP_RIGHT -> {
                    if (active > b.fight.activeDuration()) return;
                    Vector3d hand = b.rig.palm(move == CryptFight.Move.SWEEP_LEFT ? 0 : 1);
                    if (fx) {
                        Vector3d floor = new Vector3d(hand); floor.y = b.arena.center().y;
                        CryptFx.area(cb, "Crypt_Sweep_Impact", floor, CryptAttackGeometry.SWEEP_DAMAGE_RADIUS);
                    }
                    Vector3d previous = b.hasPreviousAttackHand ? b.previousAttackHand : b.arena.point(
                        CryptAttackGeometry.sweepRight(0, move == CryptFight.Move.SWEEP_LEFT), 2.1,
                        CryptAttackGeometry.sweepForward(0) + b.sweepOffset);
                    for (var p : players) {
                        var pos = cb.getComponent(p, TransformComponent.getComponentType()).getPosition();
                        if (CryptAttackGeometry.sweptHandHit(pos, previous, hand, CryptAttackGeometry.SWEEP_DAMAGE_RADIUS, 4.2) && b.attackHits.add(p)) hit(cb, root, p, 38, "Physical");
                    }
                    b.previousAttackHand.set(hand); b.hasPreviousAttackHand = true;
                }
                case SLAM -> {
                    if (active < .28 || active > .65) return;
                    if (b.attackHits.isEmpty()) { CryptFx.area(cb, "Crypt_Slam_Impact", b.target, CryptAttackGeometry.SLAM_RADIUS); CryptFx.sound(cb, "SFX_Crypt_Slam", b.target); b.attackHits.add(root); }
                    for (var p : players) if (near(cb.getComponent(p, TransformComponent.getComponentType()).getPosition(), b.target, CryptAttackGeometry.SLAM_RADIUS, 5) && b.attackHits.add(p)) hit(cb, root, p, 58, "Physical");
                }
                case POISON -> { if (fx && active < 2) CryptFx.directed(cb, "Crypt_Poison_Breath", b.rig.mouth(), new Vector3d(b.target).add(0, 1.5, 0), 2); }
                case BEAM -> {
                    if (active > b.fight.activeDuration()) return;
                    Vector3d start = b.rig.mouth(), end = b.beamAim;
                    if (fx) CryptFx.beam(cb, start, end, CryptAttackGeometry.BEAM_DAMAGE_RADIUS);
                    for (var p : players) {
                        Vector3d chest = new Vector3d(cb.getComponent(p, TransformComponent.getComponentType()).getPosition()).add(0, 1, 0);
                        if (distanceToSegment(chest, start, end) < CryptAttackGeometry.BEAM_DAMAGE_RADIUS && cooldown(b, p, .55)) hit(cb, root, p, 27, "Elemental");
                    }
                }
                case GRAB -> CryptGrab.tick(cb, root, b, players, fx);
                default -> { }
            }
        }
        private void summon(CommandBuffer<EntityStore> cb, Ref<EntityStore> root, CryptBossComponent b) {
            List<Vector3d> points = new ArrayList<>(b.castPoints);
            cb.run(store -> {
                if (!root.isValid() || b.finishing || b.fight.state() == CryptFight.State.DYING) return;
                b.minions.removeIf(r -> !r.isValid() || store.getComponent(r, DeathComponent.getComponentType()) != null);
                for (var point : points) {
                    if (b.minions.size() >= 8) break;
                    var npc = NPCPlugin.get().spawnNPC(store, "Titan_Crypt_Minion", null, point, new Rotation3f());
                    if (npc != null) b.minions.add(npc.first());
                    CryptFx.burst(store, "Crypt_Minion_Summon", point, 1);
                }
            });
        }
        private void hazards(CommandBuffer<EntityStore> cb, Ref<EntityStore> root, CryptBossComponent b, List<Ref<EntityStore>> players, boolean fx) {
            b.hazards.removeIf(h -> h.expires() < b.elapsed);
            boolean draw = b.elapsed >= b.hazardFxTime;
            if (draw) b.hazardFxTime = b.elapsed + .4;
            for (var h : b.hazards) {
                if (draw) {
                    CryptFx.area(cb, h.poison() ? "Crypt_Poison_Cloud" : "Crypt_Blue_Fire_Flames", h.position(), h.radius());
                    CryptFx.ring(cb, h.poison() ? "Crypt_Poison_Telegraph" : "Crypt_Blue_Fire_Telegraph", h.position(), h.radius());
                }
                for (var p : players) {
                    var pos = cb.getComponent(p, TransformComponent.getComponentType()).getPosition();
                    if (!near(pos, h.position(), h.radius(), 3.5) || !cooldown(b, p, .8)) continue;
                    hit(cb, root, p, h.poison() ? 7 : 16, h.poison() ? "Poison" : "Fire");
                    if (h.poison()) {
                        var effect = EntityEffect.getAssetMap().getAsset("Crypt_Poison");
                        var controller = cb.getComponent(p, EffectControllerComponent.getComponentType());
                        if (effect != null && controller != null) controller.addEffect(p, effect, cb);
                    }
                }
            }
        }
        private boolean cooldown(CryptBossComponent b, Ref<EntityStore> p, double seconds) {
            if (b.damageCooldown.getOrDefault(p, 0d) > b.elapsed) return false;
            b.damageCooldown.put(p, b.elapsed + seconds); return true;
        }
        private void ui(ComponentAccessor<EntityStore> a, CryptBossComponent b, List<Ref<EntityStore>> players) {
            for (var it = b.viewers.iterator(); it.hasNext();) {
                var ref = it.next(); if (!players.contains(ref)) { hide(a, b, ref); it.remove(); }
            }
            int phase = b.fight.phase();
            boolean changed = b.shownPhase != phase;
            if (changed && b.shownPhase != 0) {
                tell(a, b, phase == 2 ? "Phase II — Blight of the Sepulchre" : "Phase III — The Last Requiem");
                CryptFx.sound(a, "SFX_Crypt_Roar", b.rig.head());
                CryptFx.burst(a, "Crypt_Roar", b.rig.head(), 1.5f);
            }
            b.shownPhase = phase;
            for (var ref : players) {
                CryptMusic.apply(a, ref);
                boolean added = b.viewers.add(ref); if (!added && !changed) continue;
                var p = a.getComponent(ref, PlayerRef.getComponentType());
                if (p != null) CryptBossBars.show(p, b.networkId,
                    Message.raw("The Crypt Keeper · Phase " + phase).getFormattedMessage());
            }
        }
        private void finish(CommandBuffer<EntityStore> cb, Ref<EntityStore> root, CryptBossComponent b, boolean victory) {
            if (b.finishing) return; b.finishing = true;
            cb.run(store -> {
                dispose(store, b);
                if (victory) {
                    CryptSiteSystem.victory(store, b.site, b.arena);
                    tell(store, b, "The Crypt Keeper has fallen. Its essence gathers into a staff atop the coffin.");
                } else CryptSiteSystem.reset(store, b.site);
                if (root.isValid()) store.removeEntity(root, RemoveReason.REMOVE);
            });
        }
    }
    static void hit(CommandBuffer<EntityStore> cb, Ref<EntityStore> root, Ref<EntityStore> victim, float amount, String cause) {
        int index = DamageCause.getAssetMap().getIndex(cause);
        if (index < 0) index = DamageCause.getAssetMap().getIndex("Physical");
        DamageSystems.executeDamage(victim, cb, new Damage(new Damage.EntitySource(root), index,
            amount * TitanConfig.get().getAttackDamageMultiplier()));
    }
    static boolean near(Vector3d p, Vector3d q, double radius, double height) {
        return Math.abs(p.y - q.y) <= height && (p.x - q.x) * (p.x - q.x) + (p.z - q.z) * (p.z - q.z) <= radius * radius;
    }
    public static double distanceToSegment(Vector3d p, Vector3d a, Vector3d b) {
        Vector3d d = new Vector3d(b).sub(a);
        double t = d.lengthSquared() < 1e-8 ? 0 : Math.clamp(new Vector3d(p).sub(a).dot(d) / d.lengthSquared(), 0, 1);
        return p.distance(new Vector3d(a).fma(t, d));
    }
    public static final class Removal extends HolderSystem<EntityStore> {
        @Override public Query<EntityStore> getQuery() { return CryptBossComponent.getComponentType(); }
        @Override public void onEntityAdd(Holder<EntityStore> holder, AddReason reason, Store<EntityStore> store) { }
        @Override public void onEntityRemoved(Holder<EntityStore> holder, RemoveReason reason, Store<EntityStore> store) {
            var b = holder.getComponent(CryptBossComponent.getComponentType()); if (b == null) return;
            // Parts self-clean on a missing root; use the world queue for minions and persistent coffin.
            for (var ref : b.cinematics) CryptCinematic.restore(store, ref);
            for (var ref : b.viewers) hide(store, b, ref);
            if (!b.finishing) store.getExternalData().getWorld().execute(() -> {
                dispose(store, b);
                if (b.fight != null && b.fight.crown() <= 0) CryptSiteSystem.victory(store, b.site, b.arena);
                else CryptSiteSystem.reset(store, b.site);
            });
        }
    }
}
