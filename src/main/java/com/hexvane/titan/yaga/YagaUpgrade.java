package com.hexvane.titan.yaga;

import com.hexvane.titan.entity.TitanComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Growing a Baba Yaga house into its next stage.
 *
 * <p>Not really a change to the house so much as a replacement of it: the stages are separate variants with
 * their own prefabs, rig, fixtures and cupboards, and there is nothing about a hatchling's four-block legs
 * that can be stretched into an eleven-block pair. So the old one is taken down and a new one built in its
 * place, and what carries across is only what belongs to the player rather than to the body — who owns it,
 * what it had been told to do, and what was in its chests.
 *
 * <p>Separate from the command that calls it so the upgrade item planned for later has the same entry
 * point, and so the whole swap is one call the caller cannot get half-right.
 *
 * <p>Must run on the world thread outside of ticking. Callers inside a system wrap it in
 * {@code world.execute(...)}.
 */
public final class YagaUpgrade {

    private YagaUpgrade() {
    }

    /**
     * What came of an upgrade.
     *
     * @param moved   stacks that made it into the new chests
     * @param total   stacks that were in the old ones
     * @param dropped stacks that had nowhere to go, which is {@code 0} unless a variant is mis-authored
     *                with fewer or smaller containers than the stage it grows from
     */
    public record Result(@Nullable Ref<EntityStore> root,
                         int moved,
                         int total,
                         int dropped,
                         @Nullable String error) {

        public boolean ok() {
            return root != null;
        }

        @Nonnull
        static Result failure(@Nonnull final String error) {
            return new Result(null, 0, 0, 0, error);
        }
    }

    /**
     * Replaces the house at {@code root} with the next stage up, in the same spot facing the same way.
     *
     * @return the new root, or why there is not one. The old house is left standing on any failure.
     */
    @Nonnull
    public static Result apply(@Nonnull final Store<EntityStore> store, @Nonnull final Ref<EntityStore> root) {

        if (!root.isValid()) return Result.failure("it is no longer there");

        final var titan = store.getComponent(root, TitanComponent.getComponentType());
        final var yaga = store.getComponent(root, YagaComponent.getComponentType());
        final var transform = store.getComponent(root, TransformComponent.getComponentType());
        if (titan == null || yaga == null || transform == null) return Result.failure("it is not a Baba Yaga");

        final YagaComponent.Stage next = yaga.getStage().next();
        if (next == null) return Result.failure("it is already fully grown");

        final var position = new Vector3d(transform.getPosition());
        final float yaw = titan.getYaw();
        final SimpleItemContainer[] before = yaga.getInventories();

        int total = 0;
        for (final SimpleItemContainer container : before) total += YagaInventory.count(container);

        // Taken down before the new one goes up, so the two are never standing in each other. The old
        // body's blocks notice their owner has gone on their own next tick and follow it.
        store.removeEntity(root, RemoveReason.REMOVE);

        final YagaSpawn.Result spawned = YagaSpawn.spawn(store, next, position, yaw, yaga.getOwnerUuid());
        if (!spawned.ok()) return Result.failure(String.valueOf(spawned.error()));

        final Ref<EntityStore> grown = spawned.root();
        assert grown != null;

        final var grownYaga = store.getComponent(grown, YagaComponent.getComponentType());
        if (grownYaga == null) return new Result(grown, 0, total, total, null);

        // The grown house is the same house, so it keeps the old one's id and its record: what changed is
        // its stage, not which house it is. Without this the baby's record would be left behind with
        // nothing standing on it, and the next visit would rebuild a baby beside the baba.
        grownYaga.setHouseId(yaga.getHouseId());

        // Whatever it had been told to do it is still being told: a house that was sitting down stays
        // sitting down, rather than standing up because it grew.
        grownYaga.setMode(yaga.getMode());
        grownYaga.setCrouch(yaga.getCrouch());

        int dropped = 0;
        final SimpleItemContainer[] after = grownYaga.getInventories();
        for (int i = 0; i < before.length; i++) {
            if (i >= after.length) {
                // The new stage has fewer chests than the old one. Only reachable from a mis-authored
                // variant, and counted rather than silently swallowed so it shows up in the command's reply.
                dropped += YagaInventory.count(before[i]);
                continue;
            }
            dropped += YagaInventory.transfer(before[i], after[i]);
        }

        return new Result(grown, total - dropped, total, dropped, null);
    }
}
