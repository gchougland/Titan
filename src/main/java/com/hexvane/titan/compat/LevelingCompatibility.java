package com.hexvane.titan.compat;

import com.hexvane.titan.config.TitanConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.joml.Vector3d;

/** Optional public API reads only. No dependency classes or third-party configuration are bundled. */
public final class LevelingCompatibility {
    public record Scaling(float health, float damage) {
        public static final Scaling NONE = new Scaling(1, 1);
    }
    private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
    private static final Provider RPG = new Provider("org.zuxaw.plugin.api.RPGLevelingAPI", true);
    private static final Provider ENDLESS = new Provider("com.airijko.endlessleveling.api.EndlessLevelingAPI", false);
    private LevelingCompatibility() { }

    /** World-thread encounter snapshot; no reflection in the combat tick. */
    public static Scaling near(Store<EntityStore> store, Vector3d center, double radius) {
        var refs = new ArrayList<Ref<EntityStore>>();
        for (var player : store.getExternalData().getWorld().getPlayerRefs()) {
            var ref = player.getReference();
            if (ref == null || !ref.isValid() || store.getComponent(ref, DeathComponent.getComponentType()) != null) continue;
            var tx = store.getComponent(ref, TransformComponent.getComponentType());
            if (tx == null) continue;
            var p = tx.getPosition();
            double dx = p.x - center.x, dz = p.z - center.z;
            if (dx * dx + dz * dz <= radius * radius && Math.abs(p.y - center.y) <= radius) refs.add(ref);
        }
        return forPlayers(store, refs);
    }

    public static Scaling forPlayers(Store<EntityStore> store, List<Ref<EntityStore>> players) {
        var config = TitanConfig.get();
        double total = 0;
        int count = 0;
        for (var ref : players) {
            var player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player == null || store.getComponent(ref, DeathComponent.getComponentType()) != null) continue;
            int rpg = config.isRpgLevelingCompatibility() ? RPG.level(player, store) : 0;
            int endless = config.isEndlessLevelingCompatibility() ? ENDLESS.level(player, store) : 0;
            total += Math.max(1, Math.max(rpg, endless));
            count++;
        }
        return fromLevel(count == 0 ? 1 : total / count, config);
    }

    public static Scaling fromLevel(double level, TitanConfig config) {
        if (!Double.isFinite(level) || level < 1) return Scaling.NONE;
        double extra = Math.max(0, level - config.getLevelScalingBaseline());
        return new Scaling((float) Math.min(config.getMaxLevelHealthMultiplier(), 1 + extra * config.getHealthPerLevel()),
            (float) Math.min(config.getMaxLevelDamageMultiplier(), 1 + extra * config.getDamagePerLevel()));
    }

    /** Natural titans can be generated before anyone approaches: lock levels when combat first begins. */
    public static void engage(Store<EntityStore> store, com.hypixel.hytale.component.CommandBuffer<EntityStore> cb,
                              Ref<EntityStore> root, com.hexvane.titan.entity.TitanComponent titan, Vector3d center) {
        titan.levelScalingCaptured = true;
        var scale = near(store, center, Math.max(32, titan.getVariant().getLoseTargetRadius()));
        float ratio = scale.health() / titan.levelHealthMultiplier;
        titan.levelHealthMultiplier = scale.health(); titan.setLevelDamageMultiplier(scale.damage());
        if (Math.abs(ratio - 1) < .00001) return;
        titan.setNodeHealth(titan.getNodeHealth() * ratio);
        for (var ref : titan.getWeakpoints()) {
            if (!ref.isValid() || cb.getComponent(ref, DeathComponent.getComponentType()) != null) continue;
            var stats = cb.getComponent(ref, com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap.getComponentType());
            if (stats == null) continue;
            int hp = com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes.getHealth();
            var health = stats.get(hp);
            if (health == null || health.get() <= 0) continue;
            float value = health.get() * ratio;
            com.hexvane.titan.spawn.TitanPartBuilder.applyHealth(stats, health.getMax() * ratio);
            stats.setStatValue(hp, value);
        }
        var rootStats = cb.getComponent(root, com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap.getComponentType());
        if (rootStats != null) com.hexvane.titan.spawn.TitanPartBuilder.applyHealth(rootStats, titan.getTotalHealth());
    }

    public static void engageWorm(Store<EntityStore> store, com.hexvane.titan.dunewyrm.DunewyrmComponent worm) {
        var encounter = com.hexvane.titan.dunewyrm.DunewyrmEncounter.getOrCreate(worm.getEncounterId());
        if (encounter.levelScalingCaptured) return;
        encounter.levelScalingCaptured = true;
        var scale = near(store, worm.getHeadPosition(), Math.max(48, worm.getVariant().getLoseTargetRadius()));
        float ratio = scale.health() / encounter.getLeveling().health();
        encounter.setLeveling(scale);
        if (Math.abs(ratio - 1) < .00001) return;
        float remaining = encounter.getRemainingBodyHealth() * ratio;
        encounter.setInitialBodyHealth(encounter.getInitialBodyHealth() * ratio);
        encounter.setRemainingBodyHealth(remaining);
        // Include any child produced by an opening hit; never multiply again when a snake splits.
        store.forEachChunk(com.hexvane.titan.dunewyrm.DunewyrmComponent.getComponentType(), (chunk, cb) -> {
            for (int i = 0; i < chunk.size(); i++) {
                var snake = chunk.getComponent(i, com.hexvane.titan.dunewyrm.DunewyrmComponent.getComponentType());
                if (snake == null || !snake.getEncounterId().equals(worm.getEncounterId())) continue;
                snake.setInitialBodyHealth(snake.getInitialBodyHealth() * ratio);
                for (var segment : snake.getSegments()) {
                    segment.setMaxHealth(segment.getMaxHealth() * ratio);
                    segment.setHealth(segment.getHealth() * ratio);
                }
            }
        });
    }

    /** Resolve once, but obtain the live singleton each encounter: mods can start after our setup. */
    static final class Provider {
        private final String className;
        private final boolean rpg;
        private Method get, read, infoLevel;
        private boolean resolved, warned;
        Provider(String className, boolean rpg) { this.className = className; this.rpg = rpg; }
        int level(PlayerRef player, Store<EntityStore> store) { return level(player.getUuid(), player, store); }
        synchronized int level(UUID uuid, PlayerRef player, Store<EntityStore> store) {
            try {
                if (!resolved) {
                    resolved = true;
                    Class<?> type = Class.forName(className, true, LevelingCompatibility.class.getClassLoader());
                    get = type.getMethod("get");
                    read = rpg ? type.getMethod("getPlayerLevelInfo", PlayerRef.class, Store.class)
                        : type.getMethod("getPlayerLevel", UUID.class);
                    LOG.atInfo().log("Titan enabled optional leveling bridge: %s", className);
                }
                if (get == null || read == null) return 0;
                Object api = get.invoke(null);
                if (api == null) return 0;
                Object value = rpg ? read.invoke(api, player, store) : read.invoke(api, uuid);
                if (value == null) return 0;
                if (rpg) {
                    if (infoLevel == null) infoLevel = value.getClass().getMethod("getLevel");
                    value = infoLevel.invoke(value);
                }
                return value instanceof Number n ? Math.max(0, n.intValue()) : 0;
            } catch (ClassNotFoundException absent) {
                return 0;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
                if (!warned) {
                    warned = true;
                    LOG.atWarning().withCause(error).log("Titan could not read %s; using unscaled levels for this provider", className);
                }
                return 0;
            }
        }
    }
}
