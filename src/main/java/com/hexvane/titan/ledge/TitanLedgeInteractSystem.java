package com.hexvane.titan.ledge;

import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.event.events.ecs.UseEntityEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Use a ledge slab → spawn an invisible cling-cart on that rail and Minecart-mount the player to the cart.
 *
 * <p>The slab stays put (it is the rail). The cart is what moves.
 */
public final class TitanLedgeInteractSystem extends EntityEventSystem<EntityStore, UseEntityEvent.Pre> {

    public TitanLedgeInteractSystem() {
        super(UseEntityEvent.Pre.class);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(final int index,
                       @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                       @Nonnull final Store<EntityStore> store,
                       @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull final UseEntityEvent.Pre event) {

        if (event.isCancelled() || event.getInteractionType() != InteractionType.Use) return;

        final Ref<EntityStore> target = event.getTargetEntity();
        if (!target.isValid()) return;

        final var ledge = store.getComponent(target, TitanLedgeComponent.getComponentType());
        final var ledgeTransform = store.getComponent(target, TransformComponent.getComponentType());
        if (ledge == null || ledgeTransform == null) return;

        final Ref<EntityStore> player = archetypeChunk.getReferenceTo(index);

        // Already hanging: ignore further grabs until they drop.
        if (commandBuffer.getComponent(player, TitanLedgeHangComponent.getComponentType()) != null
            || store.getComponent(player, TitanLedgeHangComponent.getComponentType()) != null) {
            return;
        }

        if (commandBuffer.getComponent(player, MountedComponent.getComponentType()) != null) {
            commandBuffer.removeComponent(player, MountedComponent.getComponentType());
        }

        final Ref<EntityStore> cart = TitanLedgeSpawner.spawnCart(
            store, commandBuffer, target, player, ledge, ledgeTransform, 0f);

        commandBuffer.addComponent(player, MountedComponent.getComponentType(),
            new MountedComponent(cart, TitanLedgeSpawner.cartSeatOffset(), MountController.Minecart));
        commandBuffer.addComponent(player, TitanLedgeHangComponent.getComponentType(),
            new TitanLedgeHangComponent(cart));

        AnimationUtils.playAnimation(player, AnimationSlot.Movement, "ClimbIdle", true, commandBuffer);
        final var hang = commandBuffer.getComponent(player, TitanLedgeHangComponent.getComponentType());
        if (hang != null) hang.setLastAnim("ClimbIdle");
    }
}
