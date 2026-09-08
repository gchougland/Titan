package com.hexvane.titan.dunewyrm;

import com.hexvane.titan.ik.GroundSampler;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Clears solid blocks ahead of / around the Dunewyrm head when motion is blocked.
 *
 * <p>Runs synchronously on the world thread (AI already ticks there) so the same step can move into the
 * cleared space instead of waiting a tick and staying stuck.
 */
public final class DunewyrmTerrainSmash {

    private DunewyrmTerrainSmash() {
    }

    /**
     * Breaks a volume of solid blocks in front of the head so trees, walls, and short cliffs stop trapping
     * the snake.
     */
    public static void smashAhead(@Nonnull final Store<EntityStore> store,
                                  @Nonnull final Vector3d head,
                                  final float yaw) {
        final ChunkStore chunks = store.getExternalData().getWorld().getChunkStore();
        final double fx = DunewyrmAiSystem.forwardX(yaw);
        final double fz = DunewyrmAiSystem.forwardZ(yaw);
        final int hx = (int) Math.floor(head.x + fx * 1.5);
        final int hy = (int) Math.floor(head.y);
        final int hz = (int) Math.floor(head.z + fz * 1.5);
        final int radius = DunewyrmTuning.SMASH_RADIUS;
        final int height = DunewyrmTuning.SMASH_HEIGHT;

        // dy starts at 0 on purpose — never break the floor under the head.
        for (int dy = 0; dy <= height; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    breakAt(chunks, hx + dx, hy + dy, hz + dz);
                }
            }
        }
        // Extra column further ahead for thick trees / walls.
        final int hx2 = (int) Math.floor(head.x + fx * 3.5);
        final int hz2 = (int) Math.floor(head.z + fz * 3.5);
        for (int dy = 0; dy <= height; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    breakAt(chunks, hx2 + dx, hy + dy, hz2 + dz);
                }
            }
        }
    }

    /** Clears only the blocks overlapping the head — not a vertical shaft through the landscape. */
    public static void clearHeadPocket(@Nonnull final Store<EntityStore> store,
                                       @Nonnull final Vector3d head) {
        final ChunkStore chunks = store.getExternalData().getWorld().getChunkStore();
        final int hx = (int) Math.floor(head.x);
        final int hy = (int) Math.floor(head.y);
        final int hz = (int) Math.floor(head.z);
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    breakAt(chunks, hx + dx, hy + dy, hz + dz);
                }
            }
        }
    }

    /** True when the head is inside solid blocks (caves / dig failure). */
    public static boolean isBuried(@Nonnull final ChunkStore chunks, @Nonnull final Vector3d head) {
        final int x = (int) Math.floor(head.x);
        final int y = (int) Math.floor(head.y + 0.5);
        final int z = (int) Math.floor(head.z);
        return GroundSampler.isSolid(chunks, x, y, z) || GroundSampler.isSolid(chunks, x, y + 1, z);
    }

    private static void breakAt(@Nonnull final ChunkStore chunks, final int x, final int y, final int z) {
        if (!GroundSampler.isSolid(chunks, x, y, z)) return;
        final var columnRef = chunks.getChunkReference(ChunkUtil.indexChunkFromBlock(x, z));
        if (columnRef == null) return;
        final WorldChunk chunk = chunks.getStore().getComponent(columnRef, WorldChunk.getComponentType());
        if (chunk == null) return;
        final int id = GroundSampler.blockId(chunks, x, y, z);
        if (id == BlockType.EMPTY_ID || id == BlockType.UNKNOWN_ID) return;
        chunk.breakBlock(x, y, z, 0);
    }
}
