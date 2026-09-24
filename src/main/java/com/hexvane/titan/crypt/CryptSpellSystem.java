package com.hexvane.titan.crypt;

import com.hexvane.titan.ik.GroundSampler;
import com.hexvane.titan.dunewyrm.*;
import com.hexvane.titan.entity.TitanPartComponent;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanWeakpointComponent;
import com.hexvane.titan.entity.TitanState;
import com.hexvane.titan.yaga.YagaComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.flock.FlockMembership;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.HashSet;

/** Curved missiles with swept collision, and the signature's fixed telegraph/impact timeline. */
public final class CryptSpellSystem extends EntityTickingSystem<EntityStore> {
    @Override public Query<EntityStore> getQuery() { return Archetype.of(CryptStaff.spellType, TransformComponent.getComponentType()); }

    @Override public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                               CommandBuffer<EntityStore> buffer) {
        var spell = chunk.getComponent(index, CryptStaff.spellType);
        var transform = chunk.getComponent(index, TransformComponent.getComponentType());
        var self = chunk.getReferenceTo(index);
        if (spell == null || transform == null) return;
        spell.age += dt;
        if (spell.kind == CryptSpellComponent.Kind.PART) {
            if (spell.root == null || !spell.root.isValid()) { buffer.removeEntity(self, RemoveReason.REMOVE); return; }
            var parent = store.getComponent(spell.root, CryptStaff.spellType);
            var parentTransform = store.getComponent(spell.root, TransformComponent.getComponentType());
            if (parent == null || parentTransform == null) { buffer.removeEntity(self, RemoveReason.REMOVE); return; }
            CryptStaffArm.pose(spell, parent, parentTransform.getPosition(), transform);
            return;
        }
        if (!alive(store, spell.owner) || spell.age > (spell.kind == CryptSpellComponent.Kind.ARM ? 2.8 : 6)) {
            buffer.removeEntity(self, RemoveReason.REMOVE); return;
        }
        if (spell.age < 0) return;
        if (spell.kind == CryptSpellComponent.Kind.ARM) {
            tickArm(dt, store, buffer, self, spell, transform); return;
        }
        var position = transform.getPosition();
        spell.seekTimer -= dt;
        if (spell.seekTimer <= 0) {
            spell.seekTimer = 0.18f;
            if (!enemy(store, spell.owner, spell.target) || !visible(store, position, center(store, spell.target)))
                spell.target = seek(store, spell.owner, position, spell.velocity);
        }
        if (enemy(store, spell.owner, spell.target))
            CryptStaffRules.steer(spell.velocity, center(store, spell.target).sub(position), dt);
        // Spatial indexes store entity origins (usually feet), not their full boxes. A chest-height
        // missile's old two-block-high query excluded a standing enemy before testing its hitbox.
        double travel = spell.velocity.length() * Math.max(0, dt);
        var nearby = new ArrayList<>(TargetUtil.getAllEntitiesInCylinder(position, 4 + travel, 16 + 2 * travel, store));
        if (spell.target != null && !nearby.contains(spell.target)) nearby.add(spell.target);
        nearby.removeIf(candidate -> !enemy(store, spell.owner, candidate) && !blocksMissile(store,candidate));
        // Sweep the actual body boxes, including the start point; substeps also stop at crypt walls.
        int steps = Math.max(1, (int)Math.ceil(travel / .18));
        var previous = new Vector3d(position);
        for (int step = 0; step < steps; step++) {
            previous.set(position);
            position.fma(dt / steps, spell.velocity);
            var chunks = store.getExternalData().getWorld().getChunkStore();
            if (GroundSampler.isSolid(chunks, (int)Math.floor(position.x), (int)Math.floor(position.y), (int)Math.floor(position.z))) {
                impact(store, buffer, self, position); return;
            }
            Ref<EntityStore> victim = null;
            double earliest = Double.POSITIVE_INFINITY;
            for (var candidate : nearby) {
                double fraction = bodyIntersection(store, candidate, previous, position);
                if (fraction < earliest) { earliest = fraction;victim = candidate; }
            }
            if (victim != null) {
                previous.lerp(position, earliest);position.set(previous);
                if (enemy(store,spell.owner,victim)) hit(store, buffer, spell.owner, self, victim, 15, true);
                impact(store, buffer, self, position); return;
            }
        }
        if (spell.velocity.lengthSquared() > 1e-8) {
            var rotation = Rotation3f.lookAt(new Vector3d(), spell.velocity);
            rotation.setRoll((float)(spell.age * 2.4));
            transform.setRotation(rotation);
            var head = chunk.getComponent(index, HeadRotation.getComponentType());
            if (head != null) head.setRotation(rotation);
        }
        spell.fxTimer -= dt;
        if (spell.fxTimer <= 0) {
            spell.fxTimer = 0.10f;
            CryptFx.burst(buffer, "Crypt_Missile_Trail", position, 0.65f);
        }
    }

    private static void tickArm(float dt, Store<EntityStore> store, CommandBuffer<EntityStore> buffer, Ref<EntityStore> self,
                                CryptSpellComponent spell, TransformComponent transform) {
        double t = spell.age;
        double height = CryptStaffRules.armHeight(t);
        transform.setPosition(new Vector3d(spell.anchor).add(0, height, 0));
        spell.fxTimer -= dt;
        if (t < 1.48 && spell.fxTimer <= 0) {
            spell.fxTimer = 0.22f;
            CryptFx.ring(buffer, "Crypt_Grasp_Telegraph", spell.anchor, 4.5f);
        }
        if (t >= 1.48 && !spell.impacted) {
            spell.impacted = true;
            var victims = new ArrayList<>(TargetUtil.getAllEntitiesInCylinder(spell.anchor, 4.5, 5, store));
            var struckPools = new HashSet<String>();
            for (var victim : victims) {
                if (!enemy(store, spell.owner, victim)) continue;
                var part = CryptPartComponent.TYPE == null ? null : store.getComponent(victim, CryptPartComponent.TYPE);
                if (part != null && !struckPools.add(part.owner + ":" + part.pool)) continue;
                var wormHit=store.getComponent(victim,DunewyrmHitComponent.getComponentType());
                if (wormHit!=null && !struckPools.add(wormHit.getOwner()+":"+wormHit.getSegmentIndex())) continue;
                var target = center(store, victim);
                if (!visible(store, new Vector3d(spell.anchor).add(0, 1, 0), target)) continue;
                hit(store, buffer, spell.owner, self, victim, 110, false);
            }
            CryptFx.burst(buffer, "Crypt_Grasp_Impact", spell.anchor, 1.5f);
            CryptFx.sound(buffer, "SFX_Crypt_Grasp_Impact", spell.anchor);
        }
    }

    private static void hit(Store<EntityStore> store, CommandBuffer<EntityStore> buffer,
                            Ref<EntityStore> owner, Ref<EntityStore> projectile, Ref<EntityStore> victim, float amount, boolean charge) {
        int cause = DamageCause.getAssetMap().getIndex("Physical");
        var cryptPart = CryptPartComponent.TYPE == null ? null : store.getComponent(victim, CryptPartComponent.TYPE);
        var boss = cryptPart == null || cryptPart.owner == null || !cryptPart.owner.isValid() ? null :
            store.getComponent(cryptPart.owner, CryptBossComponent.TYPE);
        float previousPool = boss == null || cryptPart.pool < 0 ? 0 :
            cryptPart.pool == 0 ? boss.fight.crown() : boss.fight.bracelet(cryptPart.pool - 1);
        var wormSegment=wormSegment(store,victim);
        float previousWormHealth=wormSegment==null?0:wormSegment.getHealth();
        var damage = new Damage(new Damage.ProjectileSource(owner, projectile), cause, amount);
        DamageSystems.executeDamage(victim, buffer, damage);
        float nextPool = boss == null || cryptPart.pool < 0 ? 0 :
            cryptPart.pool == 0 ? boss.fight.crown() : boss.fight.bracelet(cryptPart.pool - 1);
        if (charge && ((!damage.isCancelled() && damage.getAmount() > 0) || nextPool < previousPool
            || (wormSegment!=null && wormSegment.getHealth()<previousWormHealth))) {
            var stats = store.getComponent(owner, EntityStatMap.getComponentType());
            var energy = stats == null ? null : stats.get(DefaultEntityStatTypes.getSignatureEnergy());
            if (energy != null) stats.addStatValue(DefaultEntityStatTypes.getSignatureEnergy(), energy.getMax() * 0.025f);
        }
    }

    private static void impact(Store<EntityStore> store, CommandBuffer<EntityStore> buffer, Ref<EntityStore> self, Vector3d at) {
        CryptFx.burst(buffer, "Crypt_Missile_Impact", at, 0.7f);
        CryptFx.sound(buffer, "SFX_Crypt_Missile_Impact", at);
        buffer.removeEntity(self, RemoveReason.REMOVE);
    }

    static Ref<EntityStore> seek(Store<EntityStore> store, Ref<EntityStore> owner, Vector3d at, Vector3d direction) {
        Ref<EntityStore> best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        var forward = new Vector3d(direction).normalize();
        for (var candidate : new ArrayList<>(TargetUtil.getAllEntitiesInCylinder(at, 18, 36, store))) {
            if (!enemy(store, owner, candidate)) continue;
            var target = center(store, candidate);
            var delta = new Vector3d(target).sub(at);
            double distance = delta.length();
            if (distance < 0.001) return candidate;
            if (delta.div(distance).dot(forward) < 0.1 || !visible(store, at, target)) continue;
            double score = distance + (1 - delta.dot(forward)) * 12;
            if (score < bestScore) { bestScore = score; best = candidate; }
        }
        return best;
    }

    static boolean alive(Store<EntityStore> store, Ref<EntityStore> entity) {
        if (entity == null || !entity.isValid()) return false;
        if (store.getComponent(entity, DeathComponent.getComponentType()) != null) return false;
        var stats = store.getComponent(entity, EntityStatMap.getComponentType());
        var health = stats == null ? null : stats.get(DefaultEntityStatTypes.getHealth());
        return health != null && health.get() > 0;
    }

    static boolean enemy(Store<EntityStore> store, Ref<EntityStore> owner, Ref<EntityStore> candidate) {
        if (!alive(store, candidate) || candidate.equals(owner)) return false;
        if (store.getComponent(candidate, PlayerRef.getComponentType()) != null) return false;
        var part = CryptPartComponent.TYPE == null ? null : store.getComponent(candidate, CryptPartComponent.TYPE);
        if (part != null) {
            if (part.owner == null || !part.owner.isValid()) return false;
            var boss = store.getComponent(part.owner, CryptBossComponent.TYPE);
            var wielder = store.getComponent(owner, TransformComponent.getComponentType());
            return boss != null && !boss.finishing && wielder != null && boss.arena.contains(wielder.getPosition()) &&
                CryptStaffRules.canTargetPool(boss.fight, part.pool);
        }
        // Invisible roots carry the aggregate health bar, but the missiles must select actual hit parts.
        if (CryptBossComponent.TYPE != null && store.getComponent(candidate, CryptBossComponent.TYPE) != null) return false;
        var wormHit=store.getComponent(candidate,DunewyrmHitComponent.getComponentType());
        if (wormHit!=null) return wormSegment(store,candidate)!=null;
        if (store.getComponent(candidate,DunewyrmComponent.getComponentType())!=null) return false;
        var weakpoint = store.getComponent(candidate, TitanWeakpointComponent.getComponentType());
        if (weakpoint != null) {
            var titanRef = weakpoint.getOwner();
            if (weakpoint.isBroken() || titanRef == null || !titanRef.isValid()) return false;
            if (store.getComponent(titanRef, YagaComponent.getComponentType()) != null) return false;
            var titan = store.getComponent(titanRef, TitanComponent.getComponentType());
            return titan != null && titan.getState() != TitanState.DYING;
        }
        if (store.getComponent(candidate, TitanComponent.getComponentType()) != null) return false;
        var ownFlock = store.getComponent(owner, FlockMembership.getComponentType());
        var flock = store.getComponent(candidate, FlockMembership.getComponentType());
        if (ownFlock != null && flock != null && ownFlock.getFlockId() != null && ownFlock.getFlockId().equals(flock.getFlockId())) return false;
        var npcWorld = store.getComponent(candidate, WorldSupport.getComponentType());
        if (npcWorld == null) return false;
        // Roles without attitude sensors never request this optional cache. External spell queries
        // must request it too, just as the engine's attitude filters do during their initialization.
        npcWorld.requireAttitudeCache();
        return npcWorld.getAttitude(candidate, owner, store) == Attitude.HOSTILE;
    }

    private static DunewyrmSegment wormSegment(Store<EntityStore> store, Ref<EntityStore> candidate) {
        var hit=store.getComponent(candidate,DunewyrmHitComponent.getComponentType());
        if (hit==null || hit.getOwner()==null || !hit.getOwner().isValid()) return null;
        var worm=store.getComponent(hit.getOwner(),DunewyrmComponent.getComponentType());
        if (worm==null || worm.getState()==DunewyrmState.DYING || worm.isPendingStructural()
            || hit.getSegmentIndex()<0 || hit.getSegmentIndex()>=worm.getSegments().size()) return null;
        var segment=worm.getSegments().get(hit.getSegmentIndex());
        return segment.getRole().hasHealth() && segment.getHealth()>0?segment:null;
    }

    private static boolean blocksMissile(Store<EntityStore> store, Ref<EntityStore> candidate) {
        if(candidate==null || !candidate.isValid()) return false;
        var part=store.getComponent(candidate,TitanPartComponent.getComponentType());
        if(part!=null) return !part.isCombinedVisual() && !part.isDetached()
            && store.getComponent(candidate,com.hypixel.hytale.server.core.modules.entity.component.RespondToHit.getComponentType())!=null;
        var worm=store.getComponent(candidate,DunewyrmPartComponent.getComponentType());
        if(worm!=null) return !worm.isCombinedVisual() && worm.getOwner()!=null && worm.getOwner().isValid()
            && store.getComponent(candidate,com.hypixel.hytale.server.core.modules.entity.component.RespondToHit.getComponentType())!=null;
        return CryptPartComponent.TYPE!=null && store.getComponent(candidate,CryptPartComponent.TYPE)!=null;
    }

    static Vector3d center(Store<EntityStore> store, Ref<EntityStore> entity) {
        var transform = store.getComponent(entity, TransformComponent.getComponentType());
        var result = transform == null ? new Vector3d() : new Vector3d(transform.getPosition());
        var bounding = store.getComponent(entity, BoundingBox.getComponentType());
        if (bounding != null) { var b = bounding.getBoundingBox(); result.add(new Vector3d(b.min).add(b.max).mul(0.5)); }
        else result.add(0, 0.8, 0);
        return result;
    }

    private static double bodyIntersection(Store<EntityStore> store, Ref<EntityStore> entity, Vector3d from, Vector3d to) {
        var transform = store.getComponent(entity, TransformComponent.getComponentType());
        var bounding = store.getComponent(entity, BoundingBox.getComponentType());
        if (transform == null) return Double.POSITIVE_INFINITY;
        if (bounding == null) {
            var middle = center(store, entity);
            return CryptStaffRules.bodyIntersection(from, to, new Vector3d(middle).add(-.3,-.5,-.3),
                new Vector3d(middle).add(.3,.5,.3), CryptStaffRules.MISSILE_RADIUS);
        }
        var b = bounding.getBoundingBox();
        return CryptStaffRules.bodyIntersection(from, to, new Vector3d(b.min).add(transform.getPosition()),
            new Vector3d(b.max).add(transform.getPosition()), CryptStaffRules.MISSILE_RADIUS);
    }

    static boolean visible(Store<EntityStore> store, Vector3d from, Vector3d to) {
        var chunks = store.getExternalData().getWorld().getChunkStore();
        var direction = new Vector3d(to).sub(from);
        double distance = direction.length();
        if (distance < 0.5) return true;
        direction.div(distance);
        var p = new Vector3d();
        for (double d = 0.35; d < distance - 0.35; d += 0.35) {
            p.set(from).fma(d, direction);
            if (GroundSampler.isSolid(chunks, (int)Math.floor(p.x), (int)Math.floor(p.y), (int)Math.floor(p.z))) return false;
        }
        return true;
    }
}
