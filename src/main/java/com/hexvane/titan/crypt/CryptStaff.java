package com.hexvane.titan.crypt;

import com.hexvane.titan.ik.GroundSampler;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import java.util.UUID;

/** Registration and authoritative casting for the encounter's reward weapon. */
public final class CryptStaff {
    public static final String ITEM = "Titan_Staff_Crypt_Keeper";
    static final String MISSILE_MODEL = "Crypt_Emerald_Soul";
    static final float MISSILE_STAMINA_COST = 5;
    static ComponentType<EntityStore, CryptSpellComponent> spellType;
    static ComponentType<EntityStore, CastState> castType;
    private CryptStaff() {}

    public static void register(PluginBase plugin) {
        Interaction.CODEC.register(CryptMissilesInteraction.TYPE, CryptMissilesInteraction.class, CryptMissilesInteraction.CODEC);
        Interaction.CODEC.register(CryptGraspInteraction.TYPE, CryptGraspInteraction.class, CryptGraspInteraction.CODEC);
        var registry = plugin.getEntityStoreRegistry();
        spellType = registry.registerComponent(CryptSpellComponent.class, CryptSpellComponent::new);
        castType = registry.registerComponent(CastState.class, CastState::new);
        registry.registerSystem(new CryptSpellSystem());
    }

    public static final class CastState implements Component<EntityStore> {
        long missilesReady, graspReady;
        @Override public Component<EntityStore> clone() {
            var c = new CastState(); c.missilesReady = missilesReady; c.graspReady = graspReady; return c;
        }
    }

    static void cast(InteractionContext context, boolean grasp) {
        var buffer = context.getCommandBuffer();
        if (buffer == null) return;
        var owner = context.getEntity();
        var transform = buffer.getComponent(owner, TransformComponent.getComponentType());
        var head = buffer.getComponent(owner, HeadRotation.getComponentType());
        if (transform == null || head == null) return;
        Vector3d origin = new Vector3d(transform.getPosition()).add(0, 1.6, 0);
        Vector3d direction = head.getDirection().normalize();
        float yaw = head.getRotation().yaw();
        // Recheck cost and cooldown at the moment the deferred spawn runs. Duplicate packets and
        // cancelled interaction chains cannot produce free missiles or an unpaid signature.
        buffer.run(store -> {
            if (!CryptSpellSystem.alive(store, owner)) return;
            var held = InventoryComponent.getItemInHand(store, owner);
            if (held == null || held.isEmpty() || !ITEM.equals(held.getItemId())) return;
            var state = store.getComponent(owner, castType);
            if (state == null) { state = new CastState(); store.addComponent(owner, castType, state); }
            long now = System.nanoTime();
            if (now < (grasp ? state.graspReady : state.missilesReady)) return;
            var stats = store.getComponent(owner, EntityStatMap.getComponentType());
            // Mana has a zero maximum in the base game. Use the same active resource as its staves.
            int statId = grasp ? DefaultEntityStatTypes.getSignatureEnergy() : DefaultEntityStatTypes.getStamina();
            var stat = stats == null ? null : stats.get(statId);
            float cost = grasp && stat != null ? stat.getMax() : MISSILE_STAMINA_COST;
            if (stat == null || stat.get() < cost || cost <= 0) return;
            Vector3d target = grasp ? aimGround(store, origin, direction) : null;
            if (grasp && target == null) return;
            stats.subtractStatValue(statId, cost);
            if (grasp) {
                state.graspReady = now + 8_000_000_000L;
                var spell = new CryptSpellComponent(owner, CryptSpellComponent.Kind.ARM);
                spell.anchor.set(target); spell.yaw = yaw;
                var root = spawn(store, spell, new Vector3d(target).add(0, 7, 0));
                CryptStaffArm.build(store, root, target, yaw);
                CryptFx.sound(store, "SFX_Crypt_Grasp_Charge", target);
            } else {
                state.missilesReady = now + 1_100_000_000L;
                int staminaDelay = EntityStatType.getAssetMap().getIndex("StaminaRegenDelay");
                if (staminaDelay >= 0 && stats.get(staminaDelay) != null) stats.setStatValue(staminaDelay, -1.5f);
                launchMissiles(store, owner, origin, direction);
                CryptFx.burst(store, "Crypt_Staff_Cast", new Vector3d(origin).fma(0.8, direction), 1);
                CryptFx.sound(store, "SFX_Crypt_Staff_Cast", origin);
            }
        });
    }

    /** Shared by the actual released cast and the real-engine range regression. */
    static void launchMissiles(Store<EntityStore> store, Ref<EntityStore> owner, Vector3d origin, Vector3d direction) {
        var forward = new Vector3d(direction).normalize();
        var side = new Vector3d(forward.z, 0, -forward.x);
        if (side.lengthSquared() < .001) side.set(1, 0, 0); else side.normalize();
        // Acquire from the player's eye before offsetting any soul; close enemies stay in front.
        var target = CryptSpellSystem.seek(store, owner, origin, forward);
        for (int i = 0; i < 5; i++) {
            double spread = (i - 2) * .14;
            var spell = new CryptSpellComponent(owner, CryptSpellComponent.Kind.MISSILE);
            spell.target = target;
            spell.velocity.set(forward).fma(spread, side).add(0, Math.abs(spread) * .8, 0).normalize().mul(CryptStaffRules.MISSILE_SPEED);
            spell.anchor.set(origin);spell.age = -i * .07f;
            spawn(store, spell, new Vector3d(origin).fma(.12, forward).fma((i - 2) * .06, side));
        }
    }

    static Ref<EntityStore> spawn(Store<EntityStore> store, CryptSpellComponent spell, Vector3d position) {
        var holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(spellType, spell);
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(position, new Rotation3f()));
        holder.addComponent(BoundingBox.getComponentType(), new BoundingBox(new Box(-0.2, -0.2, -0.2, 0.2, 0.2, 0.2)));
        if (spell.kind == CryptSpellComponent.Kind.MISSILE) {
            var asset = ModelAsset.getAssetMap().getAsset(MISSILE_MODEL);
            if (asset == null) throw new IllegalStateException("Missing Crypt missile model: " + MISSILE_MODEL);
            var model = Model.createStaticScaledModel(asset, 1);
            holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
            holder.addComponent(PersistentModel.getComponentType(), new PersistentModel(model.toReference()));
            holder.addComponent(HeadRotation.getComponentType(), new HeadRotation(new Rotation3f()));
            holder.addComponent(UUIDComponent.getComponentType(), new UUIDComponent(UUID.randomUUID()));
            holder.addComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
            holder.ensureComponent(EntityModule.get().getVisibleComponentType());
        }
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        return store.addEntity(holder, AddReason.SPAWN);
    }

    /** Trace the view ray; empty sky falls back to visible ground within twenty blocks. */
    static Vector3d aimGround(Store<EntityStore> store, Vector3d origin, Vector3d direction) {
        var chunks = store.getExternalData().getWorld().getChunkStore();
        Vector3d p = new Vector3d(origin);
        for (double d = 0.25; d <= 28; d += 0.25) {
            p.set(origin).fma(d, direction);
            if (GroundSampler.isSolid(chunks, (int)Math.floor(p.x), (int)Math.floor(p.y), (int)Math.floor(p.z)))
                return new Vector3d(p.x, Math.floor(p.y) + 1.05, p.z);
        }
        p.set(origin).fma(20, direction);
        double y = GroundSampler.sample(chunks, p.x, Math.min(p.y, origin.y), p.z, 0, 30);
        if (!GroundSampler.isValid(y)) return null;
        p.y = y + 0.05;
        return CryptSpellSystem.visible(store, origin, new Vector3d(p).add(0, 0.2, 0)) ? p : null;
    }
}
