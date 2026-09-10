package com.hexvane.titan.compat;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.damage.*;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/** Titan owns the scaling of these two dedicated summon roles; ordinary world NPCs are untouched. */
public final class TitanMinionScaling {
    private static final String[] ROLES = {"Titan_Crypt_Minion", "Titan_Dunewyrm_Minion"};
    private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
    private static ComponentType<EntityStore, Minion> type;
    private TitanMinionScaling() { }

    public static final class Minion implements Component<EntityStore> {
        float damage = 1;
        public Minion() { }
        @Override public Component<EntityStore> clone() { var copy = new Minion(); copy.damage = damage; return copy; }
    }

    public static void register(PluginBase plugin) {
        var registry = plugin.getEntityStoreRegistry();
        type = registry.registerComponent(Minion.class, Minion::new);
        registry.registerSystem(new DamageScale());
    }

    public static void apply(Store<EntityStore> store, Ref<EntityStore> ref, float baseHealth,
                             LevelingCompatibility.Scaling scale) {
        var marker = new Minion(); marker.damage = scale.damage();
        store.putComponent(ref, type, marker);
        // Encounter minions must not persist without their runtime scaling/owner across server restarts.
        store.ensureComponent(ref, EntityStore.REGISTRY.getNonSerializedComponentType());
        var stats = store.getComponent(ref, EntityStatMap.getComponentType());
        if (stats != null) {
            int health = DefaultEntityStatTypes.getHealth();
            stats.putModifier(health, "Titan.MinionLevel", new StaticModifier(Modifier.ModifierTarget.MAX,
                StaticModifier.CalculationType.ADDITIVE, baseHealth * (scale.health() - 1)));
            stats.maximizeStatValue(health);
        }
    }

    private static final class DamageScale extends DamageEventSystem {
        @Override public SystemGroup<EntityStore> getGroup() { return DamageModule.get().getFilterDamageGroup(); }
        @Override @Nonnull public Query<EntityStore> getQuery() { return Query.any(); }
        @Override public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                                     @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> cb,
                                     @Nonnull Damage damage) {
            if (damage.isCancelled() || damage.getAmount() <= 0 || !(damage.getSource() instanceof Damage.EntitySource source)) return;
            var attacker = source.getRef();
            if (!attacker.isValid()) return;
            var minion = cb.getComponent(attacker, type);
            if (minion != null) damage.setAmount(damage.getAmount() * minion.damage);
        }
    }

    /** Register only our summon IDs, before RPG Leveling's start phase. Admin overrides take precedence. */
    public static void configureRpgDefaults() {
        try {
            var api = Class.forName("org.zuxaw.plugin.api.RPGLevelingAPI");
            Consumer<Object> defaults = builder -> {
                try {
                    Object config = builder.getClass().getMethod("forConfig", String.class).invoke(builder, "ZoneLevelConfig.json");
                    for (String role : ROLES) {
                        var entry = new JsonObject(); entry.addProperty("EntityId", role);
                        entry.addProperty("Level", 1); entry.addProperty("DisableLevelScaling", true);
                        // The public builder accepts JSON objects; support its declared JsonElement supertype too.
                        Method append = java.util.Arrays.stream(config.getClass().getMethods())
                            .filter(m -> m.getName().equals("appendToArray") && m.getParameterCount() == 3
                                && m.getParameterTypes()[0] == String.class && m.getParameterTypes()[1] == String.class
                                && m.getParameterTypes()[2].isAssignableFrom(JsonObject.class))
                            .findFirst().orElseThrow(() -> new NoSuchMethodException("appendToArray"));
                        append.invoke(config, "EntityOverrides", "EntityId", entry);
                    }
                    config.getClass().getMethod("done").invoke(config);
                } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            };
            api.getMethod("registerConfigDefaults", String.class, Consumer.class).invoke(null, "Hexvane:Titan", defaults);
        } catch (ClassNotFoundException absent) {
            // Optional dependency.
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            LOG.atWarning().withCause(error).log("Could not register Titan minion defaults with RPG Leveling");
        }
    }

    /** EL's public runtime blacklist keeps its automatic NPC pass from adding a second level multiplier. */
    public static void configureEndless() {
        try {
            var apiClass = Class.forName("com.airijko.endlessleveling.api.EndlessLevelingAPI");
            var api = apiClass.getMethod("get").invoke(null);
            if (api == null) return;
            var add = apiClass.getMethod("addMobBlacklistEntry", String.class);
            for (String role : ROLES) add.invoke(api, role);
        } catch (ClassNotFoundException absent) {
            // Optional dependency.
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            LOG.atWarning().withCause(error).log("Could not configure Titan minion scaling with Endless Leveling");
        }
    }
}
