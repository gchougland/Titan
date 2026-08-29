package com.hexvane.titan.ik;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.protocol.Opacity;

import javax.annotation.Nonnull;

/**
 * Reads the terrain surface under a point so feet can be planted on it.
 *
 * <p>Only touches already-loaded chunk sections. An unloaded column reports {@link #NO_GROUND} and the
 * caller keeps the foot where it was, which is the correct behaviour while a titan walks towards the edge
 * of the loaded area.
 */
public final class GroundSampler {

    /** Returned when no surface could be found in the search window. */
    public static final double NO_GROUND = Double.NaN;

    private GroundSampler() {
    }

    public static boolean isValid(final double y) {
        return !Double.isNaN(y);
    }

    /**
     * Finds the top surface of the highest solid block in a vertical window around {@code startY}.
     *
     * @param above how far above {@code startY} to begin scanning, in blocks
     * @param below how far below {@code startY} to give up, in blocks
     * @return the world Y a foot should rest at, or {@link #NO_GROUND}
     */
    public static double sample(@Nonnull final ChunkStore chunkStore,
                                final double x,
                                final double startY,
                                final double z,
                                final int above,
                                final int below) {

        final int bx = (int) Math.floor(x);
        final int bz = (int) Math.floor(z);
        final int top = (int) Math.floor(startY) + above;
        final int bottom = (int) Math.floor(startY) - below;

        for (int y = top; y >= bottom; y--) {
            if (isSolid(chunkStore, bx, y, bz)) {
                return y + 1.0;
            }
        }
        return NO_GROUND;
    }

    /**
     * Whether a block blocks movement. Unloaded sections and unknown blocks count as empty so the caller
     * treats them as "no ground here" rather than as a wall.
     */
    public static boolean isSolid(@Nonnull final ChunkStore chunkStore, final int x, final int y, final int z) {
        final int id = blockId(chunkStore, x, y, z);
        if (id == BlockType.EMPTY_ID || id == BlockType.UNKNOWN_ID) return false;

        final BlockType type = BlockType.getAssetMap().getAsset(id);
        if (type == null) return false;
        return type.getOpacity() != Opacity.Transparent;
    }

    public static int blockId(@Nonnull final ChunkStore chunkStore, final int x, final int y, final int z) {
        final var ref = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
        if (ref == null) return BlockType.UNKNOWN_ID;

        final var blocks = chunkStore.getStore().getComponent(ref, BlockSection.getComponentType());
        if (blocks == null) return BlockType.UNKNOWN_ID;

        return blocks.get(x, y, z);
    }
}
