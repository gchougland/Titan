package com.hexvane.titan.spawn;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.util.PrefabUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Random;
import java.util.logging.Level;

/**
 * Stamps a titan's surroundings into the world as ordinary blocks.
 *
 * <p>Not a part of the titan. The blocks go into the chunk like anything a player placed, so they are there
 * before the titan is assembled and still there long after it has gone — which is the point, for scenery a
 * titan is found in rather than made of.
 *
 * @see com.hexvane.titan.asset.TitanVariantAsset#getGroundPrefab
 */
public final class TitanGroundPrefab {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Fixed rather than derived from the titan's yaw.
     *
     * <p>A quarter-turn is the only rotation a prefab paste can express, so following a yaw that is a
     * continuous angle would snap the scenery to a different corner than the titan stood at. Leaving it
     * unturned at least keeps the two lined up the same way every time.
     */
    @Nonnull
    private static final Rotation ROTATION = Rotation.None;

    private TitanGroundPrefab() {
    }

    /**
     * Paints {@code prefabKey} centred on {@code rootPosition}, with its lowest layer at the root's feet.
     *
     * <p>Must run on the world thread outside of ticking: the paste writes blocks and fires prefab events.
     *
     * @return {@code false} when the prefab is missing or will not decode, leaving the world untouched
     */
    public static boolean paint(@Nonnull final World world,
                                @Nonnull final ComponentAccessor<EntityStore> accessor,
                                @Nullable final String prefabKey,
                                @Nonnull final Vector3d rootPosition) {

        if (prefabKey == null || prefabKey.isEmpty()) return false;

        final var path = PrefabStore.get().findBrowsablePrefabPath(prefabKey);
        if (path == null) {
            LOGGER.at(Level.WARNING).log("Titan ground prefab '%s' was not found in any asset pack", prefabKey);
            return false;
        }

        final IPrefabBuffer buffer;
        try {
            buffer = PrefabBufferUtil.getCached(path);
        } catch (final Throwable t) {
            LOGGER.at(Level.WARNING).withCause(t).log("Failed to decode Titan ground prefab '%s'", prefabKey);
            return false;
        }

        // The paste adds the prefab's own coordinates to this, so the origin is the corner rather than the
        // middle: half the prefab's own span comes back off to centre it under the root.
        final var origin = new Vector3i(
            (int) Math.floor(rootPosition.x) - (buffer.getMinX() + buffer.getMaxX()) / 2,
            (int) Math.floor(rootPosition.y) - buffer.getMinY(),
            (int) Math.floor(rootPosition.z) - (buffer.getMinZ() + buffer.getMaxZ()) / 2);

        // FORCE, because the ordinary path tests whether each block may be placed and the ground a titan
        // stands on is already full of the terrain it grew out of. NO_ENTITIES, because whatever the artist
        // left in the prefab is not part of this titan and would be spawned again on every rebuild.
        PrefabUtil.paste(buffer, world, origin, ROTATION, new Random(0),
            PrefabUtil.Flags.FORCE | PrefabUtil.Flags.NO_ENTITIES, SetBlockSettings.NONE, accessor);

        return true;
    }
}
