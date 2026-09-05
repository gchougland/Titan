package com.hexvane.titan.yaga;

import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.builtin.crafting.window.ProcessingBenchWindow;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The furnace window, opened on a furnace that is not a block.
 *
 * <p>Nothing about the engine's window needs changing except this: it takes a block position and, on the
 * way in and out, writes an animation state back to whatever is standing there. Everything else it does
 * with the position it only reads — placing a sound, and looking for chests nearby worth drawing
 * ingredients from — and the smelting itself never asks whether the position still holds a furnace.
 *
 * @see YagaFurnace
 */
public final class YagaFurnaceWindow extends ProcessingBenchWindow {

    /** The furnace voxel this window is open on, which is what the player has to stay next to. */
    @Nonnull
    private final Ref<EntityStore> voxel;

    public YagaFurnaceWindow(@Nonnull final ProcessingBenchBlock state,
                             @Nonnull final BenchBlock bench,
                             @Nullable final BlockModule.BlockStateInfo blockStateInfo,
                             @Nonnull final Ref<EntityStore> voxel,
                             final int x, final int y, final int z, final int rotationIndex,
                             @Nonnull final BlockType blockType) {

        super(state, bench, blockStateInfo, x, y, z, rotationIndex, blockType);
        this.voxel = voxel;
    }

    /**
     * Keeps the window open while the player is still at the furnace.
     *
     * <p>Replaces the block-backed check, which would find grass where it left a furnace and close the
     * window on the player's next step — see {@link YagaWindows}.
     */
    @Override
    public boolean validate(@Nonnull final Ref<EntityStore> ref, @Nonnull final ComponentAccessor<EntityStore> store) {
        return YagaWindows.withinReach(voxel, ref, store, getMaxDistance());
    }

    /**
     * Does nothing, where the block-backed furnace would start or stop looking lit.
     *
     * <p>The position this window was given is a spot in the terrain the house is walking over, not a
     * furnace. Left in place, closing the window would try to put a furnace's idle state onto whatever
     * block is under the house — a no-op for most of them, and wrong for any that happened to share the
     * state name.
     */
    @Override
    public void setBlockInteractionState(@Nonnull final String state, @Nonnull final World world) {
    }
}
