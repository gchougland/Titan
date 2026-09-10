package com.hexvane.titan.crypt;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollisionConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Set;
import org.joml.Quaterniond;
import org.joml.Vector3d;

public final class CryptPartSystem extends EntityTickingSystem<EntityStore> {
    private final Query<EntityStore> query = Archetype.of(CryptPartComponent.getComponentType(), TransformComponent.getComponentType());
    private final Vector3d position = new Vector3d(), euler = new Vector3d();
    private final Quaterniond rotation = new Quaterniond();
    @Override public Query<EntityStore> getQuery() { return query; }
    @Override public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(new SystemDependency<>(Order.AFTER, CryptEncounter.Tick.class));
    }
    @Override public void tick(float dt, int i, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store, CommandBuffer<EntityStore> cb) {
        var p = chunk.getComponent(i, CryptPartComponent.getComponentType());
        var tx = chunk.getComponent(i, TransformComponent.getComponentType());
        if (p == null || tx == null) return;
        var boss = p.owner != null && p.owner.isValid() ? cb.getComponent(p.owner, CryptBossComponent.getComponentType()) : null;
        var self = chunk.getReferenceTo(i);
        if (boss == null || boss.finishing) { cb.removeEntity(self, RemoveReason.REMOVE); return; }
        var bone = boss.rig.bones.get(p.bone);
        boolean broken = p.pool > 0 && boss.fight.bracelet(p.pool - 1) <= 0;
        // A distant hiding position can cross into a non-ticking section and invalidate the ref.
        // Broken rings leave the live store; the root rebuilds their voxels when their pool refills.
        if (broken) {
            cb.run(s -> { if (self.isValid()) s.removeEntity(self, RemoveReason.REMOVE); });
            return;
        }
        position.set(p.offset);
        position.z *= bone.lengthScale;
        bone.rotation.transform(position).add(bone.position);
        if (boss.fight.state() == CryptFight.State.DYING && boss.fight.time() > 5) {
            double dissolve = CryptFight.smooth((boss.fight.time() - 5) / 4);
            double seed = self.getIndex() * 2.399963;
            position.add(Math.sin(seed) * dissolve * 4, dissolve * 3, Math.cos(seed) * dissolve * 4);
        }
        var rot = CryptBlockRotations.compose(bone.rotation, p.blockRotation, new Rotation3f(), rotation, euler);
        // Tight thresholds keep connected fingers coherent while avoiding idle subpixel packets.
        if (tx.getPosition().distanceSquared(position) > .0004 || Math.abs(tx.getRotation().pitch() - rot.pitch()) > .004
            || Math.abs(tx.getRotation().yaw() - rot.yaw()) > .004 || Math.abs(tx.getRotation().roll() - rot.roll()) > .004) {
            tx.getPosition().set(position); tx.setRotation(rot);
        }
        boolean platform = p.platform && boss.fight.collidableArms() && !broken;
        if (platform != p.colliding) {
            if (platform) {
                var config = HitboxCollisionConfig.getAssetMap().getAsset("Titan_Platform");
                if (config != null) cb.putComponent(self, HitboxCollision.getComponentType(), new HitboxCollision(config));
            } else cb.tryRemoveComponent(self, HitboxCollision.getComponentType());
            p.colliding = platform;
        }
        p.age += dt; p.scaleTimer -= dt;
        float wantedScale = p.scale * (float) Math.max(1, bone.lengthScale);
        if (p.scaleTimer <= 0 || Math.abs(wantedScale - p.drawnScale) > .015f) {
            var scale = chunk.getComponent(i, EntityScaleComponent.getComponentType());
            if (scale != null) scale.setScale(wantedScale);
            var bounds = chunk.getComponent(i, BoundingBox.getComponentType());
            if (bounds != null) {
                double half = wantedScale * .5;
                bounds.setBoundingBox(new Box(-half, -half, -half, half, half, half));
            }
            p.drawnScale = wantedScale;
            p.scaleTimer = p.age < 1 ? .15 : 2.5;
        }
    }
}
