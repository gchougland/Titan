package com.hexvane.titan.combat;

import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.config.TitanConfig;
import com.hypixel.hytale.builtin.audio.components.ForcedMusicTracker;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.musiccontainer.config.MusicContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Level;

/**
 * Takes over a player's music for the duration of a titan fight.
 *
 * <p>Forced music is a single number per player, set on a component that a stock system watches and turns
 * into a packet when it changes. So there is nothing here to start or stop — only a value to hold while the
 * fight is on and put back to zero when it is over, and zero is what hands the player back to whatever the
 * zone would have been playing.
 *
 * <p>Because it is one number, two titans agree by construction: they write the same index and neither can
 * tell it was not the only one. What they cannot do is disagree about when to stop, which is why clearing
 * only ever happens when the player is still on the index this titan put there.
 */
public final class TitanBattleMusic {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** The index that means "no forced music", and so hands the player back to the zone's own soundtrack. */
    public static final int NONE = 0;

    /**
     * Resolved once and remembered, including the failure.
     *
     * <p>The component belongs to a built-in plugin rather than to the server core, and asking for its type
     * before that plugin has loaded — or on a server running without it — throws. A titan should still be a
     * perfectly good fight on such a server, just a quiet one.
     */
    @Nullable
    private static volatile ComponentType<EntityStore, ForcedMusicTracker> trackerType;
    private static volatile boolean trackerUnavailable;

    /**
     * The last track id that failed to resolve, so a variant naming a container that is not there is
     * complained about once rather than on every tick of every fight.
     */
    @Nullable
    private static volatile String unknownTrack;

    private TitanBattleMusic() {
    }

    /**
     * Looks up the music index a variant fights to.
     *
     * @return {@link #NONE} if the variant names no track, the track does not exist, or the server owner
     *         has turned battle music off
     */
    public static int resolve(@Nullable final TitanVariantAsset variant) {
        if (variant == null || !TitanConfig.get().isBattleMusicEnabled()) return NONE;

        final String id = variant.getBattleMusic();
        if (id == null || id.isEmpty()) return NONE;

        final int index = MusicContainer.getAssetMap().getIndex(id);
        if (index > NONE) return index;

        // Worth saying out loud. A misspelled or removed track is indistinguishable in play from the music
        // simply not working, and there is nothing else anywhere that would mention it.
        if (!id.equals(unknownTrack)) {
            unknownTrack = id;
            LOGGER.at(Level.WARNING).log(
                "Titan variant %s asks for battle music '%s', which is not a loaded MusicContainer, so its fight will be quiet",
                variant.getId(), id);
        }
        return NONE;
    }

    /** Puts a player on the fight's track. Cheap enough to call every tick; the value only travels once. */
    public static void apply(@Nonnull final ComponentAccessor<EntityStore> accessor,
                             @Nonnull final Ref<EntityStore> player,
                             final int music) {
        if (music == NONE) return;

        final ForcedMusicTracker tracker = trackerOf(accessor, player);
        if (tracker == null) return;

        tracker.setCurrentContainerIndex(music);
    }

    /**
     * Hands a player back to the zone's music, but only if they are still on the track this titan set.
     *
     * <p>The guard is what stops a titan clearing music it did not start — a player who walked out of one
     * fight and into a trigger volume that set its own track should keep the new one.
     */
    public static void clear(@Nonnull final ComponentAccessor<EntityStore> accessor,
                             @Nonnull final Ref<EntityStore> player,
                             final int music) {
        if (music == NONE) return;

        final ForcedMusicTracker tracker = trackerOf(accessor, player);
        if (tracker == null || tracker.getCurrentContainerIndex() != music) return;

        tracker.setCurrentContainerIndex(NONE);
    }

    @Nullable
    private static ForcedMusicTracker trackerOf(@Nonnull final ComponentAccessor<EntityStore> accessor,
                                                @Nonnull final Ref<EntityStore> player) {
        if (!player.isValid()) return null;

        final ComponentType<EntityStore, ForcedMusicTracker> type = trackerType();
        return type == null ? null : accessor.getComponent(player, type);
    }

    @Nullable
    private static ComponentType<EntityStore, ForcedMusicTracker> trackerType() {
        if (trackerUnavailable) return null;

        ComponentType<EntityStore, ForcedMusicTracker> type = trackerType;
        if (type != null) return type;

        try {
            type = ForcedMusicTracker.getComponentType();
        } catch (final Throwable t) {
            trackerUnavailable = true;
            LOGGER.at(Level.WARNING).withCause(t).log(
                "Forced music is unavailable on this server, so titans will fight without their battle track");
            return null;
        }

        trackerType = type;
        return type;
    }
}
