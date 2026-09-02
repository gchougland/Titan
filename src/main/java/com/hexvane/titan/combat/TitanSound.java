package com.hexvane.titan.combat;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Plays a variant's sound by asset id, tolerating an id that is unset or unknown.
 *
 * <p>Every titan sound is named in variant JSON, so any of them can be absent, misspelt, or left pointing
 * at a renamed asset. Resolving through here keeps that from throwing mid-attack.
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

    /** As {@link #play(ComponentAccessor, String, Vector3d)}, for callers holding loose coordinates. */
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
