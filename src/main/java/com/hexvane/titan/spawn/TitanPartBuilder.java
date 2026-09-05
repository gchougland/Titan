package com.hexvane.titan.spawn;

import com.hexvane.titan.asset.TitanFixtureDef;
import com.hexvane.titan.entity.TitanFixtureComponent;
import com.hexvane.titan.entity.TitanPartComponent;
import com.hexvane.titan.entity.TitanSpawnFxComponent;
import com.hexvane.titan.entity.TitanWeakpointComponent;
import com.hexvane.titan.system.TitanPartSyncSystem;
import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.modules.interaction.DoorBlockUtils;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
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
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Assembles the entities a titan is made of.
 *
 * <p>Voxels are built without a {@code Velocity} component, since the core
 * {@code BlockEntitySystems.Ticking} system only simulates block entities that have one. While a part is
 * attached the skeleton owns its transform outright; the death ragdoll adds {@code Velocity} back and hands
 * the debris over to engine physics.
 */
public final class TitanPartBuilder {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Stat modifier key used to lift an entity's health ceiling off the stat type's default. */
    @Nonnull
    private static final String HEALTH_MODIFIER = "TITAN_MAX_HEALTH";

    /**
     * {@code RootInteraction} every fixture points at.
     *
     * <p>Deliberately a no-op asset. The engine needs a valid interaction on the entity before it will
     * carry a use through to the event the mod listens on, but the interaction assets cannot express
     * anything a fixture needs to do, so all the work happens in the listener and this only opens the door
     * to it.
     */
    @Nonnull
    public static final String FIXTURE_INTERACTION = "Titan_Fixture_Use";

    /**
     * How far a spawn-effect block's flight direction is allowed to wander off the line it belongs on, as a
     * fraction of that line. Enough to spread the shell out; well short of sending a block the wrong way.
     */
    private static final double DIRECTION_JITTER = 0.35;

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
                TitanPartSyncSystem.SCALE_REFRESH_SECONDS));

        if (collider && colliderConfig != null) {
            holder.addComponent(HitboxCollision.getComponentType(), new HitboxCollision(colliderConfig));
        }

        return holder;
    }

    /**
     * Turns a voxel already built by {@link #buildVoxel} into something a player can hit.
     *
     * <p>The voxel keeps its part component and stays glued to its bone. What this adds is a stat map,
     * which is what the engine looks for before it will let a swing land, plus the owner link that says
     * which titan was hit.
     *
     * <p>The health it is given is never actually spent: every hit on a shell voxel is cancelled by
     * {@link com.hexvane.titan.system.TitanShellDamageSystem} and charged to the shell's own pool instead.
     * It is set to the pool's full total all the same, so that a hit arriving by some path that does not
     * pass through that system cannot knock a block out of the shell on its own.
     *
     * @param health the shell's whole pool, not this voxel's share of it
     */
    public static void makeShell(@Nonnull final Holder<EntityStore> holder,
                                 @Nonnull final Ref<EntityStore> owner,
                                 final int boneIndex,
                                 @Nonnull final Vector3d localOffset,
                                 final float health) {

        holder.addComponent(TitanWeakpointComponent.getComponentType(),
            new TitanWeakpointComponent(owner, boneIndex, localOffset, new Quaterniond()));

        final var stats = holder.ensureAndGetComponent(EntityStatMap.getComponentType());
        stats.update();
        applyHealth(stats, health);
    }

    /**
     * Starts a voxel out on a shell around the titan, so the body assembles itself out of flying blocks
     * instead of appearing whole.
     *
     * <p>The voxel is moved to its starting point here rather than by the system that flies it in, so it is
     * never once drawn at its final position: the first frame any client sees of it is already out on the
     * shell. Which direction it comes from follows where it belongs on the body, so the blocks converge
     * rather than crossing over each other, and the delay follows how far out that is, so the middle of the
     * body lands first and the extremities follow.
     *
     * @param centre where the flight converges on, normally the titan's root
     */
    public static void attachSpawnFx(@Nonnull final Holder<EntityStore> holder,
                                     @Nonnull final Vector3d worldPosition,
                                     @Nonnull final Vector3d centre,
                                     final float radius,
                                     final float duration,
                                     final float stagger) {

        final var random = ThreadLocalRandom.current();

        // Jittered so blocks that belong to the same column of the body do not all set off from one point,
        // and normalised afterwards so the jitter changes the direction without shortening the throw.
        final var direction = new Vector3d(worldPosition).sub(centre)
            .normalize(1.0, new Vector3d())
            .add(random.nextDouble(-DIRECTION_JITTER, DIRECTION_JITTER),
                random.nextDouble(-DIRECTION_JITTER, DIRECTION_JITTER),
                random.nextDouble(-DIRECTION_JITTER, DIRECTION_JITTER));

        // Degenerate for a voxel sitting exactly on the centre, and for anything whose jitter happened to
        // cancel it out. Straight up is as good a direction as any and costs no second roll.
        if (direction.lengthSquared() < 1e-6) direction.set(0, 1, 0);
        direction.normalize();
        // Upper half only. A block rising out of the ground reads as the terrain being wrong rather than as
        // the creature assembling, and half the shell is underground for anything standing on flat land.
        direction.y = Math.abs(direction.y);

        final var origin = new Vector3d(centre).fma(radius, direction);

        // Measured against the shell's own radius, so the ramp spans the same scale the blocks fly across
        // and the figure in the variant means the same thing whatever size the creature is.
        final double reach = radius <= 0 ? 1 : radius;
        final float delay = (float) (stagger * Math.min(1.0, worldPosition.distance(centre) / reach));

        holder.addComponent(TitanSpawnFxComponent.getComponentType(),
            new TitanSpawnFxComponent(origin, delay, duration));

        final var transform = holder.getComponent(TransformComponent.getComponentType());
        if (transform != null) transform.getPosition().set(origin);
    }

    /**
     * Makes a voxel usable, so right-clicking it runs the mod's own interaction rather than swinging at it.
     *
     * <p>{@code Interactable} is what puts the block in the client's list of things worth pointing at, and
     * the {@code Interactions} entry is what the engine follows when the use arrives. The interaction asset
     * itself does nothing: the work happens in {@code YagaInteractSystem}, which watches the event the
     * engine fires on the way there. Without a valid asset the use is dropped before reaching it.
     *
     * @param scale world blocks per voxel, for sizing the footprint this fixture is clicked on
     * @return {@code false} when the interaction asset is missing, leaving the voxel as plain geometry
     */
    public static boolean attachFixture(@Nonnull final Holder<EntityStore> holder,
                                        @Nonnull final Ref<EntityStore> owner,
                                        @Nonnull final TitanFixtureDef fixture,
                                        final int inventoryIndex,
                                        final float scale) {

        if (RootInteraction.getAssetMap().getIndex(FIXTURE_INTERACTION) == AssetMapWithIndexes.NOT_FOUND) return false;

        final var component = new TitanFixtureComponent(owner, fixture.getKind(), inventoryIndex,
            fixture.getHint(), fixture.getOpenHint());
        if (fixture.getKind() == TitanFixtureDef.Kind.DOOR) {
            component.setBlocks(fixture.getBlock(), openState(fixture.getBlock()));
        }
        holder.addComponent(TitanFixtureComponent.getComponentType(), component);

        holder.addComponent(Interactable.getComponentType(), Interactable.INSTANCE);

        final var interactions = holder.ensureAndGetComponent(Interactions.getComponentType());
        interactions.setInteractionId(InteractionType.Use, FIXTURE_INTERACTION);
        // So a use is not diverted by whatever the player is carrying. A house with a door in it should
        // open when clicked, not be dug at because the player happens to be holding a shovel.
        interactions.setOverrideAll(true);
        if (fixture.getHint() != null) interactions.setInteractionHint(fixture.getHint());

        fitToBlock(holder, fixture.getBlock(), scale);
        return true;
    }

    /**
     * The block a door wears while it stands open.
     *
     * <p>Doors in Hytale are one block type with a handful of named states hanging off it, each carrying
     * its own model animation and its own hitbox: the leaf at ninety degrees is {@code OpenDoorIn}, and a
     * voxel wearing that block is a door that looks and measures open. So a titan's door is opened the way
     * the world's are, by swapping which state the block is, and the only thing needed here is the key —
     * resolved once at spawn, since a state lookup walks the asset's own table.
     *
     * <p>Inward first and outward second, because a door authored to swing out has no inward state. A door
     * with neither is left as scenery by {@link TitanFixtureComponent#canSwing}.
     */
    @Nullable
    private static String openState(@Nonnull final String blockKey) {
        final BlockType blockType = BlockType.getAssetMap().getAsset(blockKey);
        if (blockType == null) {
            LOGGER.at(Level.WARNING).log("Titan door block '%s' is not a loaded block type", blockKey);
            return null;
        }

        final String in = blockType.getBlockKeyForState(DoorBlockUtils.OPEN_DOOR_IN);
        if (in != null) return in;

        final String out = blockType.getBlockKeyForState(DoorBlockUtils.OPEN_DOOR_OUT);
        if (out == null) {
            LOGGER.at(Level.WARNING).log("Titan door block '%s' has no open state; it will not swing", blockKey);
        }
        return out;
    }

    /**
     * Gives a fixture the footprint its own block has, in place of the cube every other voxel gets.
     *
     * <p>Most of the furniture worth clicking is bigger than a block. A double chest, a workbench and a
     * bed are all authored as one block whose model and hitbox reach out over the cells beside it, and the
     * world fills those cells with markers that point back at it — which is why the prefab reader skips
     * them, and why the piece arrives here as a single voxel. Left with a one-cube box, half of a chest
     * two blocks wide is scenery: the client will not point at it and the server would not believe the
     * click if it did.
     *
     * <p>Only the one box, not the block's detail boxes: this is what a click is tested against, and a
     * bed the player can lie on from either side is worth more than a bed with an authentic gap under it.
     *
     * <p>The base model box is set as well as the box itself, which is what has the engine turn the
     * footprint with the fixture — a chest lying east-west on a house that walks round to face north is
     * clicked on north-south — and see {@code TitanPartSyncSystem} for keeping it turned after that.
     */
    private static void fitToBlock(@Nonnull final Holder<EntityStore> holder,
                                   @Nonnull final String blockKey,
                                   final float scale) {

        final BlockType blockType = BlockType.getAssetMap().getAsset(blockKey);
        if (blockType == null) return;

        final var boxes = BlockBoundingBoxes.getAssetMap().getAsset(blockType.getHitboxTypeIndex());
        // Anything that fits inside its own cell is left alone: the cube already built for it is the same
        // box, and this is most of a titan.
        if (boxes == null || !boxes.protrudesUnitBox()) return;

        // Read unrotated and relative to the middle of the cell, the way a block entity's own box is
        // built, so the engine's rotation pass has the same thing to work from as it does for those.
        final Box authored = boxes.get(RotationTuple.NONE_INDEX).getBoundingBox();
        final var box = new Box(
            (authored.min.x - 0.5) * scale, (authored.min.y - 0.5) * scale, (authored.min.z - 0.5) * scale,
            (authored.max.x - 0.5) * scale, (authored.max.y - 0.5) * scale, (authored.max.z - 0.5) * scale);

        final var bounds = new BoundingBox(box);
        bounds.setBaseModelBox(box);
        holder.putComponent(BoundingBox.getComponentType(), bounds);
    }

    /**
     * Makes a plain voxel something the player can click, without giving it a job of its own.
     *
     * <p>For a bone the player addresses as a whole — clicking the Baba Yaga's house to tell it to sit —
     * rather than for a named piece of furniture. Carries no fixture component, which is how the dispatch
     * tells the two apart: a click here is a question for whatever is on the titan's root.
     *
     * @return {@code false} when the interaction asset is missing, leaving the voxel as plain geometry
     */
    public static boolean makeUsable(@Nonnull final Holder<EntityStore> holder, @Nullable final String hint) {
        if (RootInteraction.getAssetMap().getIndex(FIXTURE_INTERACTION) == AssetMapWithIndexes.NOT_FOUND) return false;

        holder.addComponent(Interactable.getComponentType(), Interactable.INSTANCE);

        final var interactions = holder.ensureAndGetComponent(Interactions.getComponentType());
        interactions.setInteractionId(InteractionType.Use, FIXTURE_INTERACTION);
        interactions.setOverrideAll(true);
        if (hint != null) interactions.setInteractionHint(hint);

        return true;
    }

    /**
     * Builds a bare rendered block with no notion of what it belongs to.
     *
     * <p>Split out from {@link #buildVoxel} because a thrown boulder is the same thing, a scaled block whose
     * transform something else owns, without being a titan part. With the part marker attached the sync
     * system would look for a skeleton on the boulder, fail to find one, and delete it in mid-air.
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

        // Interaction's invulnerability check treats anything with neither EntityStatMap nor RespondToHit
        // as unhittable, so without this the engine drops the voxel from the hit list the client reported
        // for a swing. The marker makes a sword or arrow land on the body; with no stat map it still takes
        // no damage.
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
     * <p>A blockymodel's origin is wherever the artist left it; for the ore cluster it sits at the base, so
     * an entity placed on a socket draws its ore hanging in the air above it. The spawner reads the box
     * back to seat the node on its socket and to space nodes apart.
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
