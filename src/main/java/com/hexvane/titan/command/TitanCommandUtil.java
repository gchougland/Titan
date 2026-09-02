package com.hexvane.titan.command;

import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.entity.TitanComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionUtil;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Lookups shared by the {@code /titan} subcommands. */
final class TitanCommandUtil {

    private TitanCommandUtil() {
    }

    /**
     * A string argument that completes from a list looked up when the client asks.
     *
     * <p>An argument type rather than the {@code suggest} hook on the argument itself, which looks like the
     * obvious way to do this and silently does nothing. The client is told each argument's type and how many
     * values that type can suggest, and it only asks the server for completions when that count says there
     * are some. {@link ArgTypes#STRING} is the shared free-text type and reports none, so a per-argument
     * hook hung off it is never called; a type of our own that says otherwise is what puts the list on
     * screen. The shipped commands that complete strings all do it this way.
     */
    @Nonnull
    static SingleArgumentType<String> suggesting(@Nonnull final Supplier<List<String>> candidates) {
        return new SingleArgumentType<>(
            "server.commands.parsing.argtype.string.name", "server.commands.parsing.argtype.string.usage") {

            @Override
            public String parse(final String input, final ParseResult parseResult) {
                return input;
            }

            @Override
            public void suggest(@Nonnull final CommandSender sender,
                                @Nonnull final String textAlreadyEntered,
                                final int numParametersTyped,
                                @Nonnull final SuggestionResult result) {
                SuggestionUtil.suggestFiltered(candidates.get(), textAlreadyEntered, result);
            }

            @Override
            public int getSuggestionValueCount() {
                return candidates.get().size();
            }
        };
    }

    /**
     * The variants worth naming on a command line: everything loaded, minus anything the owner switched off.
     *
     * <p>Read at completion time rather than captured once, so a variant added by another pack, or one the
     * owner has since disabled, is reflected without the command being re-registered. A disabled variant is
     * skipped when a site picks what stands on it, so it is equally pointless to spawn and to search for.
     */
    @Nonnull
    static List<String> enabledVariants() {
        return TitanVariantAsset.ASSET_MAP.getAssetMap().keySet().stream()
            .filter(id -> TitanConfig.get().isVariantEnabled(id))
            .toList();
    }

    /** The titan root nearest to {@code origin} within {@code radius}, or {@code null}. */
    @Nullable
    static Ref<EntityStore> findNearest(@Nonnull final Store<EntityStore> store,
                                        @Nonnull final Vector3d origin,
                                        final double radius) {
        Ref<EntityStore> best = null;
        double bestDistance = Double.MAX_VALUE;

        for (final Ref<EntityStore> candidate : snapshotNearby(store, origin, radius)) {
            if (!candidate.isValid()) continue;
            if (store.getComponent(candidate, TitanComponent.getComponentType()) == null) continue;

            final var transform = store.getComponent(candidate, TransformComponent.getComponentType());
            if (transform == null) continue;

            final double distance = transform.getPosition().distanceSquared(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Spatial queries hand back a shared thread-local list, so anything that keeps iterating while doing
     * other lookups needs its own copy.
     */
    @Nonnull
    static List<Ref<EntityStore>> snapshotNearby(@Nonnull final Store<EntityStore> store,
                                                 @Nonnull final Vector3d origin,
                                                 final double radius) {
        return new ArrayList<>(TargetUtil.getAllEntitiesInSphere(origin, radius, store));
    }
}
