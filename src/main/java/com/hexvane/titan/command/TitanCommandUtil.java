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
     * <p>The client only requests completions when the argument's type reports a non-zero suggestion count.
     * {@link ArgTypes#STRING} reports zero, so a {@code suggest} hook hung off an individual
     * {@code STRING} argument is never called and the count has to come from a dedicated type. The shipped
     * commands that complete strings are all written this way.
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
     * Every loaded variant except those the server owner has disabled. Disabled variants are skipped when a
     * site picks what stands on it, so there is nothing to spawn or search for.
     *
     * <p>Read at completion time rather than captured once, so a variant added by another pack or disabled
     * since startup is reflected without re-registering the command.
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
     * Copies the entities within {@code radius} of {@code origin}.
     *
     * <p>Spatial queries return a shared thread-local list, so a caller that performs further lookups while
     * iterating needs a copy of its own.
     */
    @Nonnull
    static List<Ref<EntityStore>> snapshotNearby(@Nonnull final Store<EntityStore> store,
                                                 @Nonnull final Vector3d origin,
                                                 final double radius) {
        return new ArrayList<>(TargetUtil.getAllEntitiesInSphere(origin, radius, store));
    }
}
