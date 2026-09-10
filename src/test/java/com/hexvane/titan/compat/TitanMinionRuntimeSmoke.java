package com.hexvane.titan.compat;

import com.hexvane.titan.spawn.TitanPartBuilder;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.damage.*;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.joml.Vector3d;

/** Real NPC assets, health modifiers and damage-filter integration; invoked on the smoke world's thread. */
public final class TitanMinionRuntimeSmoke {
    public static void run(Store<EntityStore> store, Vector3d point) {
        for (String role : new String[]{"Titan_Crypt_Minion", "Titan_Dunewyrm_Minion"}) {
            var spawned = NPCPlugin.get().spawnNPC(store, role, null, point, new Rotation3f());
            if (spawned == null) throw new AssertionError("Cannot spawn " + role);
            var npc = spawned.first();
            var weakpoint = TitanPartBuilder.buildWeakpoint(store, npc, "Titan_OreNode_Iron", 1, 100,
                point, new Rotation3f(), 0, new Vector3d(), new org.joml.Quaterniond());
            check(weakpoint != null && "Hurt".equals(weakpoint.getComponent(ModelComponent.getComponentType())
                .getModel().getFirstBoundAnimationId("Hurt")), "ore-node hit reaction survives runtime model construction");
            var victimHolder = EntityStore.REGISTRY.newHolder();
            victimHolder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(point).add(4, 0, 0), new Rotation3f()));
            victimHolder.addComponent(BoundingBox.getComponentType(), new BoundingBox(new Box(-.4, 0, -.4, .4, 1.8, .4)));
            var victimStats = victimHolder.ensureAndGetComponent(EntityStatMap.getComponentType());
            victimStats.update(); TitanPartBuilder.applyHealth(victimStats, 100);
            var victim = store.addEntity(victimHolder, AddReason.SPAWN);
            try {
                int hp = DefaultEntityStatTypes.getHealth();
                var stats = store.getComponent(npc, EntityStatMap.getComponentType());
                float before = stats.get(hp).getMax();
                TitanMinionScaling.apply(store, npc, before, new LevelingCompatibility.Scaling(2, 1.5f));
                check(Math.abs(stats.get(hp).getMax() - before * 2) < .01, role + " doubles health");
                // Reapplying the named modifier cannot multiply an already-scaled result again.
                TitanMinionScaling.apply(store, npc, before, new LevelingCompatibility.Scaling(2, 1.5f));
                check(Math.abs(stats.get(hp).getMax() - before * 2) < .01, role + " has idempotent health scaling");
                DamageSystems.executeDamage(victim, store, new Damage(new Damage.EntitySource(npc), DamageCause.getAssetMap().getIndex("Physical"), 10));
                check(Math.abs(victimStats.get(hp).get() - 85) < .01, role + " deals level-scaled damage");
            } finally {
                if (npc.isValid()) store.removeEntity(npc, RemoveReason.REMOVE);
                if (victim != null && victim.isValid()) store.removeEntity(victim, RemoveReason.REMOVE);
            }
        }
    }
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        System.out.println("[Titan minion smoke] " + message);
    }
}
