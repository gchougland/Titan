package com.hexvane.titan.spawn;

import com.hexvane.titan.entity.TitanPartComponent;
import com.hexvane.titan.entity.TitanWeakpointComponent;
import com.hexvane.titan.system.TitanPartSyncSystem;
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
import com.hypixel.hytale.server.core.modules.entity.component.RespondToHit;
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
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Assembles the entities a titan is made of.
 *
 * <p>Voxels are deliberately built without a {@code Velocity} component: the core
 * {@code BlockEntitySystems.Ticking} system only simulates block entities that have one, so while a part is
 * attached the skeleton owns its transform outright. The death ragdoll adds {@code Velocity} back and hands
 * the debris over to engine physics.
 */
public final class TitanPartBuilder {

    /** Stat modifier key used to lift an entity's health ceiling off the stat type's default. */
    @Nonnull
    private static final String HEALTH_MODIFIER = "TITAN_MAX_HEALTH";

    private TitanPartBuilder() {
    }

    /**
     * Builds one voxel of a bone.
     *
     * @param rotation      the bone's world rotation; the block's own orientation is folded onto it
     * @param blockRotation the block's authored orientation, as a {@code RotationTuple} index
     * @param collider      whether this voxel gets hard collision, making it climbable
     */
    @Nonnull
    public static Holder<EntityStore> buildVoxel(@Nonnull final Store<EntityStore> store,
                                                 @Nonnull final Ref<EntityStore> owner,
                                                 @Nonnull final String blockKey,
                                                 @Nonnull final Vector3d worldPosition,
                                                 @Nonnull final Rotation3f rotation,
                                                 final int blockRotation,
                                                 final float scale,
                                                 final int boneIndex,
                                                 @Nonnull final Vector3d localOffset,
                                                 final boolean collider,
                                                 @Nullable final HitboxCollisionConfig colliderConfig) {

        // The caller's rotation is a scratch object shared by every voxel of the bone, so the block
        // composes into its own copy rather than turning the rest of the limb with it.
        final var worldRotation = new Rotation3f(rotation);
        BlockRotations.compose(worldRotation, blockRotation, new Quaterniond(), new Vector3d());

        final var holder = buildBlock(store, blockKey, worldPosition, worldRotation, scale);

        holder.addComponent(TitanPartComponent.getComponentType(),
            new TitanPartComponent(owner, boneIndex, localOffset, blockRotation, scale,
                ThreadLocalRandom.current().nextFloat() * TitanPartSyncSystem.SCALE_REFRESH_SECONDS));

        if (collider && colliderConfig != null) {
            holder.addComponent(HitboxCollision.getComponentType(), new HitboxCollision(colliderConfig));
        }

        return holder;
    }

    /**
     * Builds a bare rendered block with no notion of what it belongs to.
     *
     * <p>Split out from {@link #buildVoxel} because a thrown boulder is made of the same thing a titan is —
     * scaled blocks whose transforms something else owns — but it is emphatically not a titan part. Giving
     * it the part marker up front would have the sync system look for a skeleton on a rock, not find one,
     * and delete the boulder in mid-air.
     */
    @Nonnull
    public static Holder<EntityStore> buildBlock(@Nonnull final Store<EntityStore> store,
                                                 @Nonnull final String blockKey,
                                                 @Nonnull final Vector3d worldPosition,
                                                 @Nonnull final Rotation3f rotation,
                                                 final float scale) {

        final var holder = EntityStore.REGISTRY.newHolder();

        holder.addComponent(BlockEntity.getComponentType(), new BlockEntity(blockKey));
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(worldPosition), rotation));
        holder.addComponent(EntityScaleComponent.getComponentType(), new EntityScaleComponent(scale));

        // Sized to the scaled block. Left without a base model box on purpose so the engine's rotation pass
        // is a no-op and the box stays exactly one scaled cube.
        final double half = scale * 0.5;
        holder.addComponent(BoundingBox.getComponentType(), new BoundingBox(new Box(-half, -half, -half, half, half, half)));

        // Without this the voxel is not a legal attack target and the engine drops it: Interaction's
        // invulnerability check treats anything with neither EntityStatMap nor RespondToHit as unhittable.
        // A player's swing is resolved from the hit list their client reports, so the rock was already in
        // it and was being discarded here, leaving only the ore node behind it to take the blow. The
        // marker makes a sword or arrow land on the body; no stat map means it still takes no damage.
        holder.addComponent(RespondToHit.getComponentType(), RespondToHit.INSTANCE);

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
                                                     @Nonnull final Rotation3f worldRotation,
                                                     final int boneIndex,
                                                     @Nonnull final Vector3d localOffset,
                                                     @Nonnull final Quaterniondc localRotation) {

        final ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(modelAssetId);
        if (modelAsset == null) return null;

        final Model model = Model.createStaticScaledModel(modelAsset, modelScale);

        final var holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(worldPosition), new Rotation3f(worldRotation)));
        holder.addComponent(HeadRotation.getComponentType(), new HeadRotation(Rotation3f.IDENTITY));
        holder.addComponent(UUIDComponent.getComponentType(), new UUIDComponent(UUID.randomUUID()));
        holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
        holder.addComponent(PersistentModel.getComponentType(), new PersistentModel(model.toReference()));
        holder.addComponent(BoundingBox.getComponentType(), new BoundingBox(model.getBoundingBox()));
        holder.addComponent(TitanWeakpointComponent.getComponentType(),
            new TitanWeakpointComponent(owner, boneIndex, localOffset, localRotation));

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
     * The space a weakpoint occupies at {@code modelScale}, in world blocks relative to its entity position.
     *
     * <p>Needed because a blockymodel's origin is wherever the artist left it — for the ore cluster it is
     * down at the base, so an entity placed on a socket draws its ore hanging in the air above it. The
     * spawner reads the box back to seat the node on its socket and to space nodes apart.
     *
     * @return {@code null} when the model or its hitbox is missing
     */
    @Nullable
    public static Box weakpointBox(@Nonnull final String modelAssetId, final float modelScale) {
        final ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(modelAssetId);
        if (modelAsset == null) return null;

        return Model.createStaticScaledModel(modelAsset, modelScale).getBoundingBox();
    }

    /**
     * Raises an entity's health ceiling to {@code health} and fills it. Health is expressed as a modifier
     * because {@code EntityStatType} assets own the base range.
     */
    public static void applyHealth(@Nonnull final EntityStatMap stats, final float health) {
        final int healthIndex = DefaultEntityStatTypes.getHealth();
        final var statType = EntityStatType.getAssetMap().getAsset(healthIndex);
        if (statType == null) return;

        stats.putModifier(healthIndex, HEALTH_MODIFIER,
            new StaticModifier(Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.ADDITIVE, health - statType.getMax()));
        stats.maximizeStatValue(healthIndex);
    }
}
