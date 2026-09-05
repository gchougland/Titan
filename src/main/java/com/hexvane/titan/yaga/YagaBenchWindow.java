package com.hexvane.titan.yaga;

import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.window.SimpleCraftingWindow;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * The Baba Yaga's workbench, opened on a bench that is not a block.
 *
 * <p>The engine's bench window is almost usable as it stands. It wants a block position, but only reads it
 * to look for chests worth pulling materials from, to place a sound, and to play the bench's crafting
 * animation; the crafting itself never checks that the position still holds a bench. So the position given
 * is wherever the house's workbench voxel happens to be standing, which makes the first two right, and the
 * third — the only one that writes to the world — is turned off here.
 *
 * <p>The upshot is the real workbench: its own recipe list, its own categories, timed crafts, and
 * materials drawn from chests near the house, all of it the engine's own code. What is lost is the
 * animation on the bench and the tier upgrade, since both are stored on the block.
 */
public final class YagaBenchWindow extends SimpleCraftingWindow {

    /**
     * Bench state of its own, not shared with any block.
     *
     * <p>Only ever tier one, which is what makes the upgrade unavailable: an upgrade is stored on the
     * block's own component and this one is thrown away when the window closes.
     */
    @Nonnull
    private static final BenchBlock DETACHED = new BenchBlock();

    /** The bench voxel this window is open on, which is what the player has to stay next to. */
    @Nonnull
    private final Ref<EntityStore> voxel;

    public YagaBenchWindow(@Nonnull final Ref<EntityStore> voxel,
                           final int x, final int y, final int z,
                           @Nonnull final BlockType blockType) {

        super(x, y, z, 0, blockType, DETACHED);
        this.voxel = voxel;
    }

    /**
     * Keeps the window open while the player is still at the bench.
     *
     * <p>Replaces the block-backed check, which would find grass where it left a workbench and close the
     * window on the player's next step — see {@link YagaWindows}.
     */
    @Override
    public boolean validate(@Nonnull final Ref<EntityStore> ref, @Nonnull final ComponentAccessor<EntityStore> store) {
        return YagaWindows.withinReach(voxel, ref, store, getMaxDistance());
    }

    /**
     * Does nothing, where the block-backed bench would play its crafting animation.
     *
     * <p>The position handed to this window is a spot in the terrain the house is walking over, not a
     * bench. Left in place, a craft would try to put a workbench's animation state onto whatever block is
     * under the house — which is a no-op for most of them and would be wrong for any that happened to
     * share the state name.
     */
    @Override
    public void setBlockInteractionState(@Nonnull final String state, @Nonnull final World world) {
    }
}
