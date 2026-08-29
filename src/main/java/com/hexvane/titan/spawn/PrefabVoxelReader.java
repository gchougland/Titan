package com.hexvane.titan.spawn;

import com.hypixel.hytale.logger.HytaleLogger;
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
        if (prefabKey == null || prefabKey.isEmpty()) return EMPTY;
        return CACHE.computeIfAbsent(prefabKey, PrefabVoxelReader::load);
    }

    /** Drops the cache so edited prefabs are picked up on the next spawn. */
    public static void invalidate() {
        CACHE.clear();
    }

    @Nonnull
    private static PrefabVoxels load(@Nonnull final String prefabKey) {
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
        final var occupied = new LongOpenHashSet();

        buffer.forEach(
            IPrefabBuffer.iterateAllColumns(),
            (x, y, z, blockId, holder, supportValue, rotation, filler, call, fluidId, fluidLevel) -> {
                if (blockId == BlockType.EMPTY_ID) return;
                final BlockType type = BlockType.getAssetMap().getAsset(blockId);
                if (type == null || type.isUnknown()) return;

                collected.add(new int[]{x, y, z});
                keys.add(type.getId());
                occupied.add(pack(x, y, z));
            },
            null,
            null,
            new PrefabBufferCall(new Random(0), PrefabRotation.ROTATION_0)
        );

        if (collected.isEmpty()) {
            LOGGER.at(Level.WARNING).log("Titan prefab '%s' contains no blocks", prefabKey);
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
        for (int i = 0; i < collected.size(); i++) {
            final int[] p = collected.get(i);
            voxels.add(new PrefabVoxels.Voxel(p[0], p[1], p[2], keys.get(i),
                isSurface(occupied, p[0], p[1], p[2]),
                !occupied.contains(pack(p[0], p[1] + 1, p[2]))));
        }

        LOGGER.at(Level.INFO).log("Loaded Titan prefab '%s': %d blocks", prefabKey, voxels.size());
        return new PrefabVoxels(voxels, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** A block with at least one exposed face; interior blocks never get a collider. */
    private static boolean isSurface(@Nonnull final LongOpenHashSet occupied, final int x, final int y, final int z) {
        return !occupied.contains(pack(x + 1, y, z))
            || !occupied.contains(pack(x - 1, y, z))
            || !occupied.contains(pack(x, y + 1, z))
            || !occupied.contains(pack(x, y - 1, z))
            || !occupied.contains(pack(x, y, z + 1))
            || !occupied.contains(pack(x, y, z - 1));
    }

    /** Packs a prefab-local coordinate into a long; prefab extents are far below the 21-bit range. */
    private static long pack(final int x, final int y, final int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }
}
