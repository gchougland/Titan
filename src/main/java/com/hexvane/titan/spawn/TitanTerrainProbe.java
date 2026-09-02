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

import java.util.Arrays;

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
     * Columns sampled across a footprint: the centre, the four corners, and the midpoint of each edge.
     *
     * @see #probe
     */
    private static final int SAMPLES = 9;

    /**
     * The eight non-centre sample offsets, in units of the footprint radius: four corners then four edge
     * midpoints. Eight rather than four so that {@link #TRIM_HIGH} has something to spare and is still
     * measuring the whole footprint afterwards rather than a couple of columns of it.
     */
    private static final int[] RING_X = { -1, 1, -1, 1, -1, 1, 0, 0 };
    private static final int[] RING_Z = { -1, -1, 1, 1, 0, 0, -1, 1 };

    /**
     * How many of the highest samples a footprint is allowed to throw away before measuring its relief.
     *
     * <p>The heightmap counts anything that is not {@link com.hypixel.hytale.protocol.Opacity#Transparent},
     * which includes trunks and leaves, so a column with a tree in it reads as ground fifteen-odd blocks
     * above the dirt it grows out of. That error is entirely one-sided — vegetation can only ever raise a
     * reading, never lower it — so discarding the top few samples costs nothing on bare terrain and stops
     * a scattering of trees from making flat ground look like a cliff. A slope leans on every sample at
     * once and so survives the trim.
     */
    private static final int TRIM_HIGH = 3;

    /**
     * Y of the highest solid block in a column, read straight off the chunk's heightmap rather than by
     * scanning blocks. A titan's feet stand one above this.
     *
     * <p>Includes vegetation, so this is the top of the canopy in a wooded column rather than the ground.
     * Use {@link Ground#groundY} from {@link #probe} for anything that needs the terrain itself.
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

    /** Why a spot will or will not hold a titan. */
    public enum Verdict {
        OK,
        /** A footprint corner is outside the loaded chunks, so the ground there cannot be measured yet. */
        CORNER_UNLOADED,
        TOO_STEEP,
        /** Standing in water, which for a heightmap that ignores fluid means the bed of a lake or ocean. */
        SUBMERGED,
        /** Something solid overhead within the titan's own height. */
        OBSTRUCTED
    }

    /**
     * A verdict and the measurements behind it, so a rejection can say which check it failed and by how
     * much rather than only that it failed.
     *
     * @param relief       height difference found across the footprint, or {@code -1} if it was not reached
     * @param groundY      the terrain the titan would stand on, which is not the same as the surface it was
     *                     asked about if that column had a tree in it; {@link #NO_SURFACE} if unmeasured
     * @param lowestY      the lowest ground anywhere in the footprint, for a titan on legs long enough that
     *                     it wants to stand over that rather than the middle
     * @param obstructionY world Y of the block overhead, or {@link #NO_SURFACE} if there was none
     */
    public record Ground(@Nonnull Verdict verdict, int relief, int groundY, int lowestY, int obstructionY) {

        public boolean ok() {
            return verdict == Verdict.OK;
        }
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

        return probe(chunkStore, x, surfaceY, z, radius, maxRelief, headroom).ok();
    }

    /** {@link #isBuildable}, but reporting which check decided it. Same order, same answers. */
    @Nonnull
    public static Ground probe(@Nonnull final ChunkStore chunkStore,
                               final int x,
                               final int surfaceY,
                               final int z,
                               final int radius,
                               final int maxRelief,
                               final int headroom) {

        final int[] samples = new int[SAMPLES];
        samples[0] = surfaceY;

        int count = 1;
        for (int point = 0; point < RING_X.length; point++) {
            final int y = surfaceY(chunkStore, x + RING_X[point] * radius, z + RING_Z[point] * radius);
            if (y == NO_SURFACE) return new Ground(Verdict.CORNER_UNLOADED, -1, NO_SURFACE, NO_SURFACE, NO_SURFACE);
            samples[count++] = y;
        }

        Arrays.sort(samples, 0, count);

        // The median survives up to four wooded columns, so it is the ground even where the centre reading
        // was a treetop. Siting off this rather than the raw surface is what stops a titan being stood on
        // top of a tree, which the headroom check cannot catch because there is open air above a canopy.
        final int groundY = samples[count / 2];
        final int lowestY = samples[0];
        final int relief = samples[count - 1 - TRIM_HIGH] - lowestY;
        if (relief > maxRelief) return new Ground(Verdict.TOO_STEEP, relief, groundY, lowestY, NO_SURFACE);

        // Rejects oceans and lakes. The heightmap ignores water, so without this the surface it reports is
        // the seabed and a titan would be sited on the bottom of the nearest lake.
        if (hasFluid(chunkStore, x, groundY + 1, z)) {
            return new Ground(Verdict.SUBMERGED, relief, groundY, lowestY, NO_SURFACE);
        }

        for (int dy = 1; dy <= headroom; dy++) {
            if (GroundSampler.isSolid(chunkStore, x, groundY + dy, z)) {
                return new Ground(Verdict.OBSTRUCTED, relief, groundY, lowestY, groundY + dy);
            }
        }
        return new Ground(Verdict.OK, relief, groundY, lowestY, NO_SURFACE);
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
