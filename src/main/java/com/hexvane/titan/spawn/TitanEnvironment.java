package com.hexvane.titan.spawn;

import com.google.common.flogger.FluentLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EnvironmentSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Answers whether a point stands in one of a named set of environments.
 *
 * <p>Used to fence a wandering titan into the biome it belongs to. A titan is far wider than the ground it
 * is asked about, so this tests a single spot rather than a footprint: enough to stop one drifting out of
 * the plains over an afternoon, not enough to stop a foot landing in the trees at the edge.
 */
public final class TitanEnvironment {

    @Nonnull
    private static final FluentLogger LOGGER = FluentLogger.forEnclosingClass();

    /**
     * Resolved environment indexes per name set. Resolution walks the whole asset map and the wander check
     * runs on every titan every few seconds.
     */
    @Nonnull
    private static final Map<String, int[]> CACHE = new ConcurrentHashMap<>();

    private TitanEnvironment() {
    }

    /** Drops the cache so a reload picks up reindexed environment assets. */
    public static void invalidate() {
        CACHE.clear();
    }

    /**
     * Whether the ground at {@code position} is in one of {@code environments}.
     *
     * <p>An empty or absent list means the titan is not fenced in and everywhere passes. An unloaded chunk
     * fails instead, so a titan picking somewhere to walk stays inside the generated part of the world.
     */
    public static boolean matches(@Nonnull final ChunkStore chunkStore,
                                  @Nullable final String[] environments,
                                  @Nonnull final Vector3d position) {

        if (environments == null || environments.length == 0) return true;

        final int[] wanted = resolve(environments);
        if (wanted.length == 0) return true;

        final int actual = environmentAt(chunkStore, position);
        if (actual < 0) return false;

        return Arrays.binarySearch(wanted, actual) >= 0;
    }

    /**
     * Environment index of the block at {@code position}, or {@code -1} when the chunk holding it is not
     * loaded.
     */
    public static int environmentAt(@Nonnull final ChunkStore chunkStore, @Nonnull final Vector3d position) {
        final int x = MathUtil.floor(position.x);
        final int y = MathUtil.floor(position.y);
        final int z = MathUtil.floor(position.z);

        final var chunkRef = chunkStore.getChunkReference(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunkRef == null || !chunkRef.isValid()) return -1;

        // Environments are stored per section. A loaded column with no section at this height (above or
        // below the world) has no environment there.
        final var sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
        if (sectionRef == null || !sectionRef.isValid()) return Environment.UNKNOWN_ID;

        final var environments = chunkStore.getStore().getComponent(sectionRef, EnvironmentSection.getComponentType());
        return environments == null ? Environment.UNKNOWN_ID : environments.get(x, y, z);
    }

    /** @return environment id at {@code position} for command output, or {@code null} if the chunk is not loaded. */
    @Nullable
    public static String nameAt(@Nonnull final ChunkStore chunkStore, @Nonnull final Vector3d position) {
        final int index = environmentAt(chunkStore, position);
        if (index < 0) return null;

        final Environment environment = Environment.getAssetMap().getAsset(index);
        return environment == null ? null : environment.getId();
    }

    @Nonnull
    private static int[] resolve(@Nonnull final String[] environments) {
        return CACHE.computeIfAbsent(String.join("\u0000", environments), key -> {
            for (final String name : environments) {
                // A name that resolves to nothing leaves the fence with nothing to check, which in game
                // looks like a titan wandering freely rather than like a typo.
                if (Environment.getAssetMap().getIndex(name) < 0) {
                    LOGGER.at(Level.WARNING).log(
                        "Titan environment '%s' does not exist; it will not fence anything in", name);
                }
            }
            return Arrays.stream(environments)
                .mapToInt(name -> Environment.getAssetMap().getIndex(name))
                .filter(index -> index >= 0)
                .sorted()
                .toArray();
        });
    }
}
