package com.hexvane.titan.spawn;

import com.hexvane.titan.entity.TitanPartComponent;
import com.hexvane.titan.entity.TitanWeakpointComponent;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollisionConfig;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.entityui.UIComponentList;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Assembles the entities a titan is made of.
 *
 * <p>Voxels are deliberately built without a {@code Velocity} component: the core
 * {@code BlockEntitySystems.Ticking} system only simulates block entities that have one, so while a part is
 * attached the skeleton owns its transform outright. The death ragdoll adds {@code Velocity} back and hands
 * the debris over to engine physics.
 */
public final class TitanPartBuilder {

    /** Stat modifier key used to lift an ore node's health to the variant's value. */
    @Nonnull
    private static final String WEAKPOINT_HEALTH_MODIFIER = "TITAN_WEAKPOINT_MAX";

    private TitanPartBuilder() {
    }

    /**
     * Builds one voxel of a bone.
     *
     * @param collider whether this voxel gets hard collision, making it climbable
     */
    @Nonnull
    public static Holder<EntityStore> buildVoxel(@Nonnull final Store<EntityStore> store,
                                                 @Nonnull final Ref<EntityStore> owner,
                                                 @Nonnull final String blockKey,
                                                 @Nonnull final Vector3d worldPosition,
                                                 @Nonnull final Rotation3f rotation,
                                                 final float scale,
                                                 final int boneIndex,
                                                 @Nonnull final Vector3d localOffset,
                                                 final boolean collider,
                                                 @Nullable final HitboxCollisionConfig colliderConfig) {

        final var holder = EntityStore.REGISTRY.newHolder();

        holder.addComponent(BlockEntity.getComponentType(), new BlockEntity(blockKey));
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(worldPosition), rotation));
        holder.addComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(scale));
        holder.addComponent(TitanPartComponent.getComponentType(), new TitanPartComponent(owner, boneIndex, localOffset));

        // Sized to the scaled block. Left without a base model box on purpose so the engine's rotation pass
        // is a no-op and the box stays exactly one scaled cube.
        final double half = scale * 0.5;
        holder.addComponent(BoundingBox.getComponentType(), new BoundingBox(new Box(-half, -half, -half, half, half, half)));

        if (collider && colliderConfig != null) {
            holder.addComponent(HitboxCollision.getComponentType(), new HitboxCollision(colliderConfig));
        }

        holder.addComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.ensureComponent(EntityModule.get().getVisibleComponentType());
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());

        return holder;
    }

    /**
     * Builds an ore weakpoint. It renders the ore's own model but is a real damageable entity rather than a
     * dropped item, because item entities are intangible and cannot be attacked.
     *
     * @return {@code null} if the variant's model asset is missing
     */
    @Nullable
    public static Holder<EntityStore> buildWeakpoint(@Nonnull final Store<EntityStore> store,
                                                     @Nonnull final Ref<EntityStore> owner,
                                                     @Nonnull final String modelAssetId,
                                                     final float modelScale,
                                                     final float health,
                                                     @Nonnull final Vector3d worldPosition,
                                                     final int boneIndex,
                                                     @Nonnull final Vector3d localOffset) {

        final ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(modelAssetId);
        if (modelAsset == null) return null;

        final Model model = Model.createStaticScaledModel(modelAsset, modelScale);

        final var holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(worldPosition), Rotation3f.IDENTITY));
        holder.addComponent(HeadRotation.getComponentType(), new HeadRotation(Rotation3f.IDENTITY));
        holder.addComponent(UUIDComponent.getComponentType(), new UUIDComponent(UUID.randomUUID()));
        holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
        holder.addComponent(PersistentModel.getComponentType(), new PersistentModel(model.toReference()));
        holder.addComponent(BoundingBox.getComponentType(), new BoundingBox(model.getBoundingBox()));
        holder.addComponent(TitanWeakpointComponent.getComponentType(), new TitanWeakpointComponent(owner, boneIndex, localOffset));

        holder.addComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.ensureComponent(EntityModule.get().getVisibleComponentType());
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());

        final var ui = holder.ensureAndGetComponent(UIComponentList.getComponentType());
        ui.update();

        final var stats = holder.ensureAndGetComponent(EntityStatMap.getComponentType());
        stats.update();
        applyHealth(stats, health);

        return holder;
    }

    /**
     * Raises the node's health ceiling to {@code health} and fills it. Health is expressed as a modifier
     * because {@code EntityStatType} assets own the base range.
     */
    private static void applyHealth(@Nonnull final EntityStatMap stats, final float health) {
        final int healthIndex = DefaultEntityStatTypes.getHealth();
        final var statType = EntityStatType.getAssetMap().getAsset(healthIndex);
        if (statType == null) return;

        stats.putModifier(healthIndex, WEAKPOINT_HEALTH_MODIFIER,
            new StaticModifier(Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.ADDITIVE, health - statType.getMax()));
        stats.maximizeStatValue(healthIndex);
    }
}
