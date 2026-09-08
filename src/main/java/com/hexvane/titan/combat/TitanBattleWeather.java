package com.hexvane.titan.combat;

import com.hexvane.titan.asset.TitanVariantAsset;
import com.hypixel.hytale.builtin.weather.components.WeatherTracker;
import com.hypixel.hytale.builtin.weather.resources.WeatherResource;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.weather.config.Weather;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Level;

/**
 * Overrides a player's weather for the duration of a titan fight, the way {@link TitanBattleMusic} does
 * for music.
 *
 * <p>Per-player rather than world-wide: the sandstorm belongs to whoever is inside the fight, and setting
 * the world's forced weather would also persist it into the save. The override sits on the stock
 * {@code WeatherTracker}, so the weather system stops re-sending environment weather while it is set.
 */
public final class TitanBattleWeather {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** No weather override. */
    public static final int NONE = Integer.MIN_VALUE;

    /** Seconds the client blends into the storm; long enough to read as it rolling in. */
    private static final float ROLL_IN_SECONDS = 6f;
    /** Seconds to blend back out after the fight. */
    private static final float CLEAR_SECONDS = 8f;

    @Nullable
    private static volatile ComponentType<EntityStore, WeatherTracker> trackerType;
    private static volatile boolean trackerUnavailable;
    @Nullable
    private static volatile String unknownWeather;

    private TitanBattleWeather() {
    }

    /** @return the weather index for the variant's fight, or {@link #NONE} */
    public static int resolve(@Nullable final TitanVariantAsset variant) {
        if (variant == null) return NONE;
        final String id = variant.getBattleWeather();
        if (id == null || id.isEmpty()) return NONE;

        final int index = Weather.getAssetMap().getIndex(id);
        if (index >= 0) return index;

        if (!id.equals(unknownWeather)) {
            unknownWeather = id;
            LOGGER.at(Level.WARNING).log(
                "Titan variant %s asks for battle weather '%s', which is not a loaded Weather, so its fight keeps the zone weather",
                variant.getId(), id);
        }
        return NONE;
    }

    /** Puts the storm over one player. Idempotent; the packet only travels when the index changes. */
    public static void apply(@Nonnull final ComponentAccessor<EntityStore> accessor,
                             @Nonnull final Ref<EntityStore> player,
                             final int weather) {
        if (weather == NONE || !player.isValid()) return;

        final WeatherTracker tracker = trackerOf(accessor, player);
        final PlayerRef playerRef = accessor.getComponent(player, PlayerRef.getComponentType());
        if (tracker == null || playerRef == null) return;
        if (tracker.getWeatherIndex() == weather) {
            tracker.setOverrideWeatherIndex(weather);
            return;
        }

        tracker.setOverrideWeatherIndex(weather);
        tracker.sendWeatherIndex(playerRef, weather, ROLL_IN_SECONDS);
    }

    /** Hands the player back to whatever the world / environment says the weather is. */
    public static void clear(@Nonnull final ComponentAccessor<EntityStore> accessor,
                             @Nonnull final Ref<EntityStore> player,
                             final int weather) {
        if (weather == NONE || !player.isValid()) return;

        final WeatherTracker tracker = trackerOf(accessor, player);
        final PlayerRef playerRef = accessor.getComponent(player, PlayerRef.getComponentType());
        if (tracker == null || playerRef == null) return;
        // Only undo what this fight set.
        if (tracker.getWeatherIndex() != weather) return;

        tracker.clearOverrideWeatherIndex();
        tracker.sendWeatherIndex(playerRef, resolveResetIndex(accessor, player, tracker), CLEAR_SECONDS);
    }

    private static int resolveResetIndex(@Nonnull final ComponentAccessor<EntityStore> accessor,
                                         @Nonnull final Ref<EntityStore> player,
                                         @Nonnull final WeatherTracker tracker) {
        final WeatherResource resource = accessor.getResource(WeatherResource.getResourceType());
        if (resource == null) return NONE;
        final int forced = resource.getForcedWeatherIndex();
        if (forced != Integer.MIN_VALUE) return forced;

        final TransformComponent transform = accessor.getComponent(player, TransformComponent.getComponentType());
        if (transform != null) {
            tracker.updateEnvironment(transform, accessor);
        }
        return resource.getWeatherIndexForEnvironment(tracker.getEnvironmentId());
    }

    @Nullable
    private static WeatherTracker trackerOf(@Nonnull final ComponentAccessor<EntityStore> accessor,
                                            @Nonnull final Ref<EntityStore> player) {
        final ComponentType<EntityStore, WeatherTracker> type = trackerType();
        return type == null ? null : accessor.getComponent(player, type);
    }

    @Nullable
    private static ComponentType<EntityStore, WeatherTracker> trackerType() {
        if (trackerUnavailable) return null;
        ComponentType<EntityStore, WeatherTracker> type = trackerType;
        if (type != null) return type;
        try {
            type = WeatherTracker.getComponentType();
        } catch (final Throwable t) {
            trackerUnavailable = true;
            LOGGER.at(Level.WARNING).withCause(t).log(
                "Weather override is unavailable on this server, so titans will fight in the zone's weather");
            return null;
        }
        trackerType = type;
        return type;
    }
}
