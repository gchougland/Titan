package com.hexvane.titan.yaga;

import com.hexvane.titan.asset.TitanFixtureDef;
import com.hexvane.titan.combat.TitanSound;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.spawn.ColliderMode;
import com.hexvane.titan.spawn.TitanSpawner;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The one way a Baba Yaga house comes into the world.
 *
 * <p>Building the titan is only half of it: a house is also whose it is and what is in its cupboards, and
 * those live in a {@link YagaComponent} that has to be attached and sized before anything can use it. Every
 * path that produces one — the egg hatching, the upgrade, the commands, and the record being restored after
 * a restart — goes through here, so there is nowhere a house can appear half-made.
 *
 * <p>The egg is the exception and takes no component: it has no owner, no mode and nothing in it.
 *
 * <p>Must run on the world thread outside of ticking, since it spawns entities and attaches components.
 * Callers inside a system wrap it in {@code world.execute(...)}.
 */
public final class YagaSpawn {

    private YagaSpawn() {
    }

    /** What came of a spawn: the new root, or why there is not one. */
    public record Result(@Nullable Ref<EntityStore> root, @Nullable String error) {
        public boolean ok() {
            return root != null;
        }
    }

    /**
     * Builds {@code stage} at {@code position} facing {@code yaw} radians, owned by {@code ownerUuid}.
     *
     * @param ownerUuid whoever it belongs to, or {@code null} for an egg
     */
    @Nonnull
    public static Result spawn(@Nonnull final Store<EntityStore> store,
                               @Nonnull final YagaComponent.Stage stage,
                               @Nonnull final Vector3d position,
                               final float yaw,
                               @Nullable final UUID ownerUuid) {

        return spawn(store, stage, position, yaw, ownerUuid, null);
    }

    @Nonnull
    private static Result spawn(@Nonnull final Store<EntityStore> store,
                                @Nonnull final YagaComponent.Stage stage,
                                @Nonnull final Vector3d position,
                                final float yaw,
                                @Nullable final UUID ownerUuid,
                                @Nullable final ProcessingBenchBlock savedFurnace) {

        // ColliderMode.DEFAULT, so the skeleton's own per-bone collider settings decide: the house and its
        // mound are climbable and the legs and tail are not.
        final TitanSpawner.Result spawned =
            TitanSpawner.spawn(store, stage.variantId(), position, yaw, ColliderMode.DEFAULT);
        if (!spawned.ok()) return new Result(null, spawned.error());

        final Ref<EntityStore> root = spawned.root();
        assert root != null;

        final var titan = store.getComponent(root, TitanComponent.getComponentType());
        final var variant = titan == null ? null : titan.getVariant();
        if (variant != null && variant.getSpawnFxRadius() > 0f) {
            TitanSound.play(store, variant.getSpawnSound(), position);
        }

        if (stage != YagaComponent.Stage.EGG) {
            final var yaga = new YagaComponent(stage, ownerUuid);
            yaga.resize(spawned.inventoryCapacities());
            light(store, root, yaga, savedFurnace, position);
            store.putComponent(root, YagaComponent.getComponentType(), yaga);
        }

        return new Result(root, null);
    }

    /**
     * Gives the house its furnace, if this stage has one.
     *
     * <p>Built here rather than when somebody first opens it, because a furnace does its work whether or
     * not anyone is watching: a house restored with ore and coal already in it should be smelting before
     * its owner thinks to look, and a fire lit last night should have burnt down by morning.
     *
     * @param saved the furnace's state as it was written down, or {@code null} for a new house
     */
    private static void light(@Nonnull final Store<EntityStore> store,
                              @Nonnull final Ref<EntityStore> root,
                              @Nonnull final YagaComponent yaga,
                              @Nullable final ProcessingBenchBlock saved,
                              @Nonnull final Vector3d position) {

        final var titan = store.getComponent(root, TitanComponent.getComponentType());
        final var variant = titan == null ? null : titan.getVariant();
        if (variant == null) return;

        // Which block is the furnace is the variant's business. A hatchling has no furnace at all, and a
        // later stage could be built out of a different one.
        final TitanFixtureDef furnace = variant.findFixture(TitanFixtureDef.Kind.FURNACE);
        if (furnace == null) return;

        yaga.setFurnace(YagaFurnace.create(store, saved, furnace.getBlock(), position));
    }

    /**
     * Rebuilds the house a {@link YagaRecord} describes.
     *
     * <p>Called when the owner comes back within reach of where they left it, which is the only time the
     * house exists at all: the cluster is hundreds of entities and the engine discards those with the
     * chunks, so between visits the record is the house.
     *
     * <p>Contents are moved slot for slot rather than the record's containers being adopted whole, so a
     * house saved by a version whose chests were a different size still comes back with what fits. The
     * furnace is the exception and is adopted whole: its containers are the engine's, laid out and
     * filtered to match the block the house is built from, and it is handed back the state it was saved
     * from so that it can pick the smelt up where it left off.
     */
    @Nonnull
    public static Result restore(@Nonnull final Store<EntityStore> store, @Nonnull final YagaRecord record) {

        final UUID houseId = record.id();
        final UUID owner = record.ownerUuid();
        final YagaComponent.Stage stage = record.stage();
        if (houseId == null || owner == null || stage == null) return new Result(null, "the record is unreadable");

        final Result result = spawn(store, stage, record.position(), record.yaw(), owner, record.getFurnace());
        if (!result.ok()) return result;

        final Ref<EntityStore> root = result.root();
        assert root != null;

        final var yaga = store.getComponent(root, YagaComponent.getComponentType());
        if (yaga == null) return result;

        // Adopted rather than left as the fresh one the component generated, so this goes on being the same
        // house: the next sweep updates the record it came from instead of writing a second one beside it.
        yaga.setHouseId(houseId);
        yaga.setMode(record.isResting() ? YagaComponent.Mode.RESTING : YagaComponent.Mode.FOLLOW);
        // Straight to fully folded rather than ramped. The ramp is there so a house lowers itself where the
        // player can watch; one that was already resting when they left should be resting when they return.
        yaga.setCrouch(record.isResting() ? 1f : 0f);

        final SimpleItemContainer[] saved = record.getInventories();
        final SimpleItemContainer[] live = yaga.getInventories();
        for (int i = 0; i < saved.length && i < live.length; i++) {
            YagaInventory.transfer(saved[i], live[i]);
        }

        return result;
    }

    /**
     * Gives a house that was spawned by the generic {@code /titan spawn} the component it needs to work.
     *
     * <p>Without this a baby or baba built that way would stand inert: nothing would walk it, the wand
     * would have nobody to answer to, and its chests would open onto containers that do not exist.
     * Left as a separate call rather than folded into the spawner so the spawner stays ignorant of what any
     * particular titan is.
     */
    public static void adopt(@Nonnull final Store<EntityStore> store,
                             @Nonnull final Ref<EntityStore> root,
                             @Nonnull final int[] inventoryCapacities,
                             @Nullable final UUID ownerUuid) {

        final var titan = store.getComponent(root, TitanComponent.getComponentType());
        if (titan == null) return;

        final YagaComponent.Stage stage = YagaComponent.Stage.of(titan.getVariantId());
        if (stage == null || stage == YagaComponent.Stage.EGG) return;
        if (store.getComponent(root, YagaComponent.getComponentType()) != null) return;

        final var yaga = new YagaComponent(stage, ownerUuid);
        yaga.resize(inventoryCapacities);
        store.putComponent(root, YagaComponent.getComponentType(), yaga);
    }
}
