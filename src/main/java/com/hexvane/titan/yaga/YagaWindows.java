package com.hexvane.titan.yaga;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * The check that keeps a window open on a piece of furniture that walks.
 *
 * <p>{@code BlockWindow} closes itself whenever the block it was opened on stops being that block, and it
 * asks the world about the position it was given to find out. A house's furnace and workbench are given
 * the spot of ground the house is standing over, which holds grass, so left alone that check closes the
 * window on the next thing the player does — which is what a furnace that shuts the moment it is switched
 * on looks like.
 *
 * <p>What replaces it keeps the part of the rule that was worth having. A window is open on something in
 * front of the player and should close when they walk away from it, so the distance is measured to the
 * furniture voxel itself, which moves with the house rather than staying where the house was standing when
 * the window opened.
 */
final class YagaWindows {

    private YagaWindows() {
    }

    /**
     * Whether {@code player} is still standing at {@code fixture}.
     *
     * @param fixture     the voxel the window was opened on, which is where the furniture is now
     * @param maxDistance the window's own reach, in blocks
     */
    static boolean withinReach(@Nonnull final Ref<EntityStore> fixture,
                               @Nonnull final Ref<EntityStore> player,
                               @Nonnull final ComponentAccessor<EntityStore> store,
                               final double maxDistance) {

        // Gone means the house has unloaded or been sat back into an egg with the window still up.
        if (!fixture.isValid()) return false;

        final var here = store.getComponent(player, TransformComponent.getComponentType());
        final var there = store.getComponent(fixture, TransformComponent.getComponentType());
        if (here == null || there == null) return false;

        return here.getPosition().distanceSquared(there.getPosition()) <= maxDistance * maxDistance;
    }
}
