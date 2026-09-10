package com.hexvane.titan.crypt;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Pool damage is intercepted before voxel health/death; the rest of the skeleton is invulnerable. */
public final class CryptDamageSystem extends DamageEventSystem {
    @Override public Query<EntityStore> getQuery() { return CryptPartComponent.getComponentType(); }
    @Override public SystemGroup<EntityStore> getGroup() { return DamageModule.get().getFilterDamageGroup(); }
    @Override public void handle(int i, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store, CommandBuffer<EntityStore> cb, Damage damage) {
        if (damage.isCancelled()) return;
        damage.setCancelled(true);
        var part = chunk.getComponent(i, CryptPartComponent.getComponentType());
        if (part == null || part.pool < 0 || part.owner == null || !part.owner.isValid()) return;
        var boss = cb.getComponent(part.owner, CryptBossComponent.getComponentType());
        if (boss == null || boss.finishing) return;
        // Environment, boss attacks and its minions cannot chip the crown or release their own master.
        if (!(damage.getSource() instanceof Damage.EntitySource source) || !source.getRef().isValid()) return;
        var attacker = source.getRef();
        if (attacker.equals(part.owner) || boss.minions.contains(attacker)) return;
        var pos = cb.getComponent(attacker, TransformComponent.getComponentType());
        if (pos == null || !boss.arena.contains(pos.getPosition())) return;
        float before = part.pool == 0 ? boss.fight.crown() : boss.fight.bracelet(part.pool - 1);
        float crownBefore = boss.fight.crown();
        if (!boss.fight.hit(part.pool, damage.getAmount(), attacker.getIndex(), store.getExternalData().getWorld().getTick())) return;
        if (part.pool > 0 && cb.getComponent(attacker, PlayerRef.getComponentType()) != null)
            CryptGrab.noteHit(cb, boss, attacker, part.pool - 1);
        if (crownBefore > 0 && boss.fight.crown() <= 0)
            cb.run(s -> CryptSiteSystem.beginVictory(s, boss.site, boss.arena));
        boolean braceletBroken = part.pool > 0 && before > 0 && boss.fight.bracelet(part.pool - 1) == 0;
        var tx = chunk.getComponent(i, TransformComponent.getComponentType());
        if (tx != null) {
            CryptFx.burst(cb, part.pool == 0 ? "Crypt_Crown_Hit" : "Crypt_Bracelet_Break", tx.getPosition(), .6f);
            // One sound per accepted strike, not per voxel selected by a sword swing.
            CryptFx.sound(cb, part.pool == 0 ? "SFX_Crypt_Crown_Hit"
                : braceletBroken ? "SFX_Crypt_Bracelet_Break" : "SFX_Crypt_Bracelet_Hit", tx.getPosition());
        }
        if (braceletBroken) {
            CryptEncounter.tell(cb, boss, part.pool == 1 ? "Left bracelet shattered, cracking the crown." : "Right bracelet shattered, cracking the crown.");
            CryptFx.burst(cb, "Crypt_Bracelet_Break", boss.rig.palm(part.pool - 1), 1.5f);
            CryptFx.burst(cb, "Crypt_Crown_Hit", boss.rig.crown(), 1);
            CryptFx.sound(cb, "SFX_Crypt_Crown_Hit", boss.rig.crown());
        }
    }
    public static final class Root extends DamageEventSystem {
        @Override public Query<EntityStore> getQuery() { return CryptBossComponent.getComponentType(); }
        @Override public SystemGroup<EntityStore> getGroup() { return DamageModule.get().getFilterDamageGroup(); }
        @Override public void handle(int i, ArchetypeChunk<EntityStore> c, Store<EntityStore> s, CommandBuffer<EntityStore> cb, Damage d) { d.setCancelled(true); }
    }
    /** No player can be killed while their controls are taken by one of this encounter's tracks. */
    public static final class CinematicGuard extends DamageEventSystem {
        @Override public Query<EntityStore> getQuery() { return PlayerRef.getComponentType(); }
        @Override public SystemGroup<EntityStore> getGroup() { return DamageModule.get().getFilterDamageGroup(); }
        @Override public void handle(int i, ArchetypeChunk<EntityStore> c, Store<EntityStore> s, CommandBuffer<EntityStore> cb, Damage d) {
            var victim = c.getReferenceTo(i);
            s.forEachChunk(CryptBossComponent.getComponentType(), (chunk, ignored) -> {
                for (int j = 0; j < chunk.size(); j++) {
                    var b = chunk.getComponent(j, CryptBossComponent.getComponentType());
                    if (b != null && b.cinematics.contains(victim)) d.setCancelled(true);
                }
            });
        }
    }
}
