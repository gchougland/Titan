package com.hexvane.titan.spawn;

import com.hexvane.titan.ik.GroundSampler;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.environment.EnvironmentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Asks the world whether a spot could hold a titan: is it loaded, what terrain is it, is it flat and dry
 * and open enough for something this big to be standing there.
 *
 * <p>Everything here reads already-loaded chunks only and never forces a load. An unloaded column simply
 * reports "no", and the spawn pass tries again on its next sweep once the player has streamed it in.
 */
public final class TitanTerrainProbe {

    /** Returned by {@link #surfaceY} when the column is unloaded or has no ground in it. */
    public static final int NO_SURFACE = Integer.MIN_VALUE;

    private TitanTerrainProbe() {
    }

    /**
     * Y of the highest solid block in a column, read straight off the chunk's heightmap rather than by
     * scanning blocks. A titan's feet stand one above this.
     */
    public static int surfaceY(@Nonnull final ChunkStore chunkStore, final int x, final int z) {
        final WorldChunk chunk = columnAt(chunkStore, x, z);
        if (chunk == null) return NO_SURFACE;

        final short height = chunk.getHeight(x, z);
        return height < ChunkUtil.MIN_Y ? NO_SURFACE : height;
    }

    /**
     * Environment id covering a surface point, e.g. {@code Env_Zone1_Plains}.
     *
     * <p>Read at the block a creature would stand in, falling back to the ground block itself: the column
     * is authored per terrain segment and the boundary between "surface" and "the air above it" is not
     * always where a single sample lands.
     */
    @Nullable
    public static String environmentAt(@Nonnull final ChunkStore chunkStore, final int x, final int surfaceY, final int z) {
        final Ref<ChunkStore> columnRef = chunkStore.getChunkReference(ChunkUtil.indexChunkFromBlock(x, z));
        if (columnRef == null) return null;

        final EnvironmentChunk environments = chunkStore.getStore().getComponent(columnRef, EnvironmentChunk.getComponentType());
        if (environments == null) return null;

        final String standing = environmentId(environments, x, surfaceY + 1, z);
        return standing != null ? standing : environmentId(environments, x, surfaceY, z);
    }

    /**
     * Whether a titan of the given footprint would stand on flat, dry, open ground.
     *
     * @param radius    half-width of the footprint to test, in blocks
     * @param maxRelief the greatest height difference tolerated across that footprint
     * @param headroom  clear blocks required above the surface
     */
    public static boolean isBuildable(@Nonnull final ChunkStore chunkStore,
                                      final int x,
                                      final int surfaceY,
                                      final int z,
                                      final int radius,
                                      final int maxRelief,
                                      final int headroom) {

        int lowest = surfaceY;
        int highest = surfaceY;
        for (int corner = 0; corner < 4; corner++) {
            final int dx = (corner & 1) == 0 ? -radius : radius;
            final int dz = (corner & 2) == 0 ? -radius : radius;

            final int y = surfaceY(chunkStore, x + dx, z + dz);
            if (y == NO_SURFACE) return false;
            lowest = Math.min(lowest, y);
            highest = Math.max(highest, y);
        }
        if (highest - lowest > maxRelief) return false;

        // Rejects oceans and lakes. The heightmap ignores water, so without this the surface it reports is
        // the seabed and a titan would be sited on the bottom of the nearest lake.
        if (hasFluid(chunkStore, x, surfaceY + 1, z)) return false;

        for (int dy = 1; dy <= headroom; dy++) {
            if (GroundSampler.isSolid(chunkStore, x, surfaceY + dy, z)) return false;
        }
        return true;
    }

    public static boolean hasFluid(@Nonnull final ChunkStore chunkStore, final int x, final int y, final int z) {
        final Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
        if (sectionRef == null) return false;

        final FluidSection fluids = chunkStore.getStore().getComponent(sectionRef, FluidSection.getComponentType());
        return fluids != null && fluids.getFluidId(x, y, z) != Fluid.EMPTY_ID;
    }

    @Nullable
    private static WorldChunk columnAt(@Nonnull final ChunkStore chunkStore, final int x, final int z) {
        final Ref<ChunkStore> columnRef = chunkStore.getChunkReference(ChunkUtil.indexChunkFromBlock(x, z));
        return columnRef == null ? null : chunkStore.getStore().getComponent(columnRef, WorldChunk.getComponentType());
    }

    @Nullable
    private static String environmentId(@Nonnull final EnvironmentChunk environments, final int x, final int y, final int z) {
        final int clamped = Math.max(ChunkUtil.MIN_Y, Math.min(ChunkUtil.HEIGHT_MINUS_1, y));
        final Environment environment = Environment.getAssetMap().getAsset(environments.get(x, clamped, z));
        return environment == null ? null : environment.getId();
    }
}
