package com.hexvane.titan.combat;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Plays a variant's sound by asset id, tolerating the id being unset or unknown.
 *
 * <p>Every sound a titan makes is named in its variant JSON, which means every one of them can be absent,
 * misspelt or left over from an asset that has since been renamed. Resolving through here keeps that from
 * being an exception in the middle of an attack.
 */
public final class TitanSound {

    private TitanSound() {
    }

    /** Plays {@code sound} in the world at {@code position}. Does nothing if the id is unset or unknown. */
    public static void play(@Nonnull final ComponentAccessor<EntityStore> accessor,
                            @Nullable final String sound,
                            @Nonnull final Vector3d position) {
        play(accessor, sound, position.x, position.y, position.z);
    }

    public static void play(@Nonnull final ComponentAccessor<EntityStore> accessor,
                            @Nullable final String sound,
                            final double x,
                            final double y,
                            final double z) {

        if (sound == null || sound.isEmpty()) return;

        final int index = SoundEvent.getAssetMap().getIndex(sound);
        if (index == SoundEvent.EMPTY_ID) return;

        SoundUtil.playSoundEvent3d(null, index, x, y, z, accessor);
    }
}
