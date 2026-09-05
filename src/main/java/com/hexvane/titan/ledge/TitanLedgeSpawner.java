package com.hexvane.titan.ledge;

import com.hexvane.titan.spawn.TitanPartBuilder;
import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollisionConfig;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3f;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Level;

/**
 * Spawns grab-ledge slabs and the playground shelf used to test pull-up.
 */
public final class TitanLedgeSpawner {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Block used for the pull-up shelf in the playground. */
    @Nonnull
    private static final String SHELF_BLOCK = "Rock_Stone";

    @Nonnull
    private static final String SHELF_COLLIDER = "Titan_Platform";

    private TitanLedgeSpawner() {
    }

    /**
     * Spawns one grab ledge at {@code position}, facing {@code yaw} (radians).
     *
     * @return the ledge ref, or {@code null} if assets are missing or the world refused the entity
     */
    @Nullable
    public static Ref<EntityStore> spawn(@Nonnull final Store<EntityStore> store,
                                         @Nonnull final Vector3d position,
                                         final float yaw) {

        final Holder<EntityStore> holder = buildLedge(store, position, yaw);
        if (holder == null) return null;

        return store.addEntity(holder, AddReason.SPAWN);
    }

    /**
     * Spawns a continuous row, a jump gap, a second row, and a Hard shelf above the middle of the first row.
     *
     * @param origin centre of the first row; rows run along world right of {@code yaw}
     * @param yaw    facing of every ledge
     * @return how many ledge slabs were spawned
     */
    public static int spawnPlayground(@Nonnull final Store<EntityStore> store,
                                      @Nonnull final Vector3d origin,
                                      final float yaw) {

        final double rightX = Math.cos(yaw);
        final double rightZ = -Math.sin(yaw);
        final double forwardX = -Math.sin(yaw);
        final double forwardZ = -Math.cos(yaw);

        final float spacing = TitanLedge.HALF_WIDTH * 2f;
        int spawned = 0;

        for (int i = 0; i < 5; i++) {
            final double t = (i - 2) * spacing;
            final var at = new Vector3d(
                origin.x + rightX * t,
                origin.y,
                origin.z + rightZ * t);
            if (spawn(store, at, yaw) != null) spawned++;
        }

        final double gapStart = 2.5 * spacing + TitanLedge.TRANSFER_GAP + 1.0;
        for (int i = 0; i < 2; i++) {
            final double t = gapStart + i * spacing;
            final var at = new Vector3d(
                origin.x + rightX * t,
                origin.y,
                origin.z + rightZ * t);
            if (spawn(store, at, yaw) != null) spawned++;
        }

        final var shelfOrigin = new Vector3d(
            origin.x - forwardX * TitanLedge.PULL_FORWARD,
            origin.y + TitanLedge.PULL_UP - 0.5,
            origin.z - forwardZ * TitanLedge.PULL_FORWARD);
        spawnShelf(store, shelfOrigin, yaw);

        return spawned;
    }

    @Nullable
    private static Holder<EntityStore> buildLedge(@Nonnull final Store<EntityStore> store,
                                                  @Nonnull final Vector3d position,
                                                  final float yaw) {

        if (RootInteraction.getAssetMap().getIndex(TitanLedge.USE_INTERACTION) == AssetMapWithIndexes.NOT_FOUND) {
            LOGGER.at(Level.WARNING).log("Ledge RootInteraction '%s' is not loaded", TitanLedge.USE_INTERACTION);
            return null;
        }

        if (BlockType.getAssetMap().getAsset(TitanLedge.BLOCK_ID) == null) {
            LOGGER.at(Level.WARNING).log("Ledge block '%s' is not loaded", TitanLedge.BLOCK_ID);
            return null;
        }

        final Holder<EntityStore> holder = TitanPartBuilder.buildBlock(
            store, TitanLedge.BLOCK_ID, position, new Rotation3f(0, yaw, 0), 1f);

        holder.addComponent(TitanLedgeComponent.getComponentType(), new TitanLedgeComponent());
        holder.ensureAndGetComponent(EntityStatMap.getComponentType()).update();

        holder.addComponent(Interactable.getComponentType(), Interactable.INSTANCE);
        final var interactions = holder.ensureAndGetComponent(Interactions.getComponentType());
        interactions.setInteractionId(InteractionType.Use, TitanLedge.USE_INTERACTION);
        interactions.setOverrideAll(true);
        interactions.setInteractionHint("Grab ledge");

        return holder;
    }

    private static void spawnShelf(@Nonnull final Store<EntityStore> store,
                                   @Nonnull final Vector3d centre,
                                   final float yaw) {

        final HitboxCollisionConfig collider = HitboxCollisionConfig.getAssetMap().getAsset(SHELF_COLLIDER);
        if (collider == null) {
            LOGGER.at(Level.WARNING).log("Shelf HitboxCollision '%s' is not loaded", SHELF_COLLIDER);
            return;
        }

        final double rightX = Math.cos(yaw);
        final double rightZ = -Math.sin(yaw);
        final double forwardX = -Math.sin(yaw);
        final double forwardZ = -Math.cos(yaw);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                final var at = new Vector3d(
                    centre.x + rightX * dx + forwardX * dz,
                    centre.y,
                    centre.z + rightZ * dx + forwardZ * dz);
                final Holder<EntityStore> holder = TitanPartBuilder.buildBlock(
                    store, SHELF_BLOCK, at, new Rotation3f(0, yaw, 0), 1f);
                holder.addComponent(HitboxCollision.getComponentType(), new HitboxCollision(collider));
                store.addEntity(holder, AddReason.SPAWN);
            }
        }
    }

    /**
     * Queues an invisible cling-cart for spawn via {@code commandBuffer}.
     *
     * <p>Must not call {@link Store#addEntity} from a ticking system — use the command buffer.
     *
     * @return the cart ref (valid after the buffer flushes)
     */
    @Nonnull
    public static Ref<EntityStore> spawnCart(@Nonnull final Store<EntityStore> store,
                                             @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                             @Nonnull final Ref<EntityStore> ledge,
                                             @Nonnull final Ref<EntityStore> rider,
                                             @Nonnull final TitanLedgeComponent ledgeDef,
                                             @Nonnull final TransformComponent ledgeTransform,
                                             final float t) {

        final float yaw = ledgeTransform.getRotation().yaw();
        final var at = hangWorldPosition(ledgeTransform.getPosition(), yaw, ledgeDef, t);

        final var holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(),
            new TransformComponent(at, new Rotation3f(0, yaw, 0)));
        holder.addComponent(BoundingBox.getComponentType(),
            new BoundingBox(new Box(-0.1, -0.1, -0.1, 0.1, 0.1, 0.1)));
        holder.addComponent(TitanLedgeCartComponent.getComponentType(),
            new TitanLedgeCartComponent(ledge, rider, t));
        holder.addComponent(MovementStatesComponent.getComponentType(), new MovementStatesComponent());
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.ensureComponent(EntityModule.get().getVisibleComponentType());
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());

        return commandBuffer.addEntity(holder, AddReason.SPAWN);
    }

    /**
     * World hang point for rail parameter {@code t}: under and slightly out from the ledge slab.
     */
    @Nonnull
    public static Vector3d hangWorldPosition(@Nonnull final Vector3d ledgePos,
                                             final float yaw,
                                             @Nonnull final TitanLedgeComponent ledge,
                                             final float t) {
        final var right = new Vector3d();
        final var forward = new Vector3d();
        right(yaw, right);
        forward(yaw, forward);
        return new Vector3d(ledgePos)
            .add(right.x * t, 0, right.z * t)
            .add(0, -ledge.getHangDown(), 0)
            .add(forward.x * ledge.getHangOut(), 0, forward.z * ledge.getHangOut());
    }

    /** Player sits on the cart itself; hang pose is baked into the cart's world position. */
    @Nonnull
    public static Vector3f cartSeatOffset() {
        return new Vector3f(0f, 0f, 0f);
    }

    /** World-space forward for a yaw, matching the rest of the titan mod. */
    public static void forward(final float yaw, @Nonnull final Vector3d out) {
        out.set(-Math.sin(yaw), 0, -Math.cos(yaw));
    }

    /** World-space right for a yaw (up × forward). */
    public static void right(final float yaw, @Nonnull final Vector3d out) {
        out.set(-Math.cos(yaw), 0, Math.sin(yaw));
    }
}
