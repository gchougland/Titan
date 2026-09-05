package com.hexvane.titan.spawn;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.Opacity;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferCall;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Turns a prefab into the flat voxel list the part builder spawns from.
 *
 * <p>Results are cached per prefab key. A titan re-reads the same three or four prefabs for every bone and
 * every spawn, and prefab decoding is the expensive half of assembling one.
 */
public final class PrefabVoxelReader {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    @Nonnull
    private static final Map<String, PrefabVoxels> CACHE = new ConcurrentHashMap<>();
    @Nonnull
    private static final PrefabVoxels EMPTY = new PrefabVoxels(List.of(), 0, 0, 0, 0, 0, 0);

    private PrefabVoxelReader() {
    }

    /**
     * Reads a prefab by key, relative to any asset pack's {@code Server/Prefabs} directory. The
     * {@code .prefab.json} suffix is optional.
     *
     * @return an empty result when the prefab is missing or fails to decode
     */
    @Nonnull
    public static PrefabVoxels read(@Nullable final String prefabKey) {
        return read(prefabKey, Integer.MIN_VALUE, Integer.MAX_VALUE, PrefabRotation.ROTATION_0);
    }

    /**
     * Reads the layers of a prefab between {@code minY} and {@code maxY} inclusive, in the prefab's own
     * block coordinates, so one authored prefab can be cut up between several bones.
     *
     * <p>The slice is skinned in isolation: a voxel is only interior if its neighbours are inside the same
     * slice, so the faces exposed by the cut are treated as surface and are kept by a hollow bone. Layers
     * outside the window are not read at all, so cutting a prefab three ways costs no more than reading it
     * whole.
     *
     * <p>{@code rotation} turns the prefab about Y as it is decoded, which is the only place a turn can be
     * made without consequences elsewhere: the engine carries each block's own orientation and its
     * multi-block links round with it, and the bone that ends up holding the geometry stays axis-aligned,
     * so nothing downstream — pivots, IK planes, the gait planner — has to know a turn happened. Slicing
     * still cuts along Y, which the turn leaves alone.
     */
    @Nonnull
    public static PrefabVoxels read(@Nullable final String prefabKey,
                                    final int minY,
                                    final int maxY,
                                    @Nonnull final PrefabRotation rotation) {

        if (prefabKey == null || prefabKey.isEmpty() || minY > maxY) return EMPTY;
        return CACHE.computeIfAbsent(cacheKey(prefabKey, minY, maxY, rotation), key -> load(prefabKey, minY, maxY, rotation));
    }

    /**
     * Reads the rock-type variant of a prefab: {@code Talus_Body} with a suffix of {@code Basalt} reads
     * {@code Talus_Body_Basalt}.
     *
     * <p>A rock type only has to ship the parts it changes. Anything it leaves out falls back to the
     * unsuffixed prefab instead of leaving that bone with no geometry.
     */
    @Nonnull
    public static PrefabVoxels read(@Nullable final String prefabKey, @Nullable final String suffix) {
        return read(prefabKey, suffix, Integer.MIN_VALUE, Integer.MAX_VALUE, PrefabRotation.ROTATION_0);
    }

    /** Reads a slice of the rock-type variant of a prefab. See {@link #read(String, int, int, PrefabRotation)}. */
    @Nonnull
    public static PrefabVoxels read(@Nullable final String prefabKey,
                                    @Nullable final String suffix,
                                    final int minY,
                                    final int maxY,
                                    @Nonnull final PrefabRotation rotation) {

        if (prefabKey == null || prefabKey.isEmpty() || suffix == null || suffix.isEmpty()) {
            return read(prefabKey, minY, maxY, rotation);
        }

        final String suffixed = prefabKey + '_' + suffix;
        if (!CACHE.containsKey(cacheKey(suffixed, minY, maxY, rotation))
            && PrefabStore.get().findBrowsablePrefabPath(suffixed) == null) {
            return read(prefabKey, minY, maxY, rotation);
        }
        return read(suffixed, minY, maxY, rotation);
    }

    @Nonnull
    private static String cacheKey(@Nonnull final String prefabKey,
                                   final int minY,
                                   final int maxY,
                                   @Nonnull final PrefabRotation rotation) {

        final String turned = rotation == PrefabRotation.ROTATION_0 ? prefabKey : prefabKey + '@' + rotation;
        if (minY == Integer.MIN_VALUE && maxY == Integer.MAX_VALUE) return turned;
        return turned + '#' + minY + ':' + maxY;
    }

    /** Drops the cache so edited prefabs are picked up on the next spawn. */
    public static void invalidate() {
        CACHE.clear();
    }

    @Nonnull
    private static PrefabVoxels load(@Nonnull final String prefabKey,
                                     final int sliceMinY,
                                     final int sliceMaxY,
                                     @Nonnull final PrefabRotation rotation) {
        final var path = PrefabStore.get().findBrowsablePrefabPath(prefabKey);
        if (path == null) {
            LOGGER.at(Level.WARNING).log("Titan prefab '%s' was not found in any asset pack", prefabKey);
            return EMPTY;
        }

        final IPrefabBuffer buffer;
        try {
            buffer = PrefabBufferUtil.getCached(path);
        } catch (final Throwable t) {
            LOGGER.at(Level.WARNING).withCause(t).log("Failed to decode Titan prefab '%s'", prefabKey);
            return EMPTY;
        }

        final var collected = new ArrayList<int[]>();
        final var keys = new ArrayList<String>();
        // Every cell that holds anything; decides whether a player could land on top of one.
        final var occupied = new LongOpenHashSet();
        // Only the cells that hide what is behind them. See isOccluder.
        final var occluders = new LongOpenHashSet();

        buffer.forEach(
            IPrefabBuffer.iterateAllColumns(),
            (x, y, z, blockId, holder, supportValue, blockRotation, filler, call, fluidId, fluidLevel) -> {
                if (y < sliceMinY || y > sliceMaxY) return;
                if (blockId == BlockType.EMPTY_ID) return;
                final BlockType type = BlockType.getAssetMap().getAsset(blockId);
                if (type == null || type.isUnknown()) return;

                // Recorded before the filler test, so the space a whole multi-block takes up is accounted
                // for and not just its anchor.
                occupied.add(pack(x, y, z));
                if (isOccluder(type)) occluders.add(pack(x, y, z));

                // A multi-block covers several cells but is one object: the anchor carries the model and
                // every other cell is a filler reference back to it. Spawning a voxel per cell would render
                // one overlapping copy of the model for each.
                if (filler != 0) return;

                collected.add(new int[]{x, y, z, blockRotation});
                keys.add(type.getId());
            },
            null,
            null,
            new PrefabBufferCall(new Random(0), rotation)
        );

        if (collected.isEmpty()) {
            LOGGER.at(Level.WARNING).log("Titan prefab '%s' contains no blocks", cacheKey(prefabKey, sliceMinY, sliceMaxY, rotation));
            return EMPTY;
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (final int[] p : collected) {
            if (p[0] < minX) minX = p[0];
            if (p[1] < minY) minY = p[1];
            if (p[2] < minZ) minZ = p[2];
            if (p[0] > maxX) maxX = p[0];
            if (p[1] > maxY) maxY = p[1];
            if (p[2] > maxZ) maxZ = p[2];
        }

        final var voxels = new ArrayList<PrefabVoxels.Voxel>(collected.size());
        int surfaceCount = 0;
        for (int i = 0; i < collected.size(); i++) {
            final int[] p = collected.get(i);
            final boolean surface = isSurface(occluders, p[0], p[1], p[2]);
            if (surface) surfaceCount++;
            voxels.add(new PrefabVoxels.Voxel(p[0], p[1], p[2], keys.get(i), p[3],
                surface,
                !occupied.contains(pack(p[0], p[1] + 1, p[2]))));
        }

        LOGGER.at(Level.INFO).log("Loaded Titan prefab '%s': %d blocks (%d shell)",
            cacheKey(prefabKey, sliceMinY, sliceMaxY, rotation), voxels.size(), surfaceCount);
        return new PrefabVoxels(voxels, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Whether a block fills its cell densely enough to hide whatever is behind it.
     *
     * <p>Only a solid full cube does. A slab, stair, vine or bench leaves most of its cell empty, so the
     * rock behind one stays visible and culling it punches a hole through the titan. This is the same test
     * the engine uses to decide whether a block is a solid cube.
     */
    private static boolean isOccluder(@Nonnull final BlockType type) {
        return type.isCubeDrawType() && type.getOpacity() == Opacity.Solid;
    }

    /** A block with at least one face not hidden by a solid cube; interior blocks never get a collider. */
    private static boolean isSurface(@Nonnull final LongOpenHashSet occluders, final int x, final int y, final int z) {
        return !occluders.contains(pack(x + 1, y, z))
            || !occluders.contains(pack(x - 1, y, z))
            || !occluders.contains(pack(x, y + 1, z))
            || !occluders.contains(pack(x, y - 1, z))
            || !occluders.contains(pack(x, y, z + 1))
            || !occluders.contains(pack(x, y, z - 1));
    }

    /** Packs a prefab-local coordinate into a long; prefab extents are far below the 21-bit range. */
    private static long pack(final int x, final int y, final int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }
}
