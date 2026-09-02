package com.hexvane.titan.asset;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hexvane.titan.config.TitanConfig;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Which titans turn up in which terrain, and how thickly.
 *
 * <p>Rules are keyed on the engine's {@code Environment} ids rather than biome tiles, because that is the
 * same granularity the vanilla ambient spawner works at and it already distinguishes a zone's plains from
 * its caves and shores. One rule owns an environment outright; a second rule naming the same environment
 * is ignored so the mapping stays unambiguous.
 */
public final class TitanSpawnRuleAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, TitanSpawnRuleAsset>> {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    @Nonnull
    public static final DefaultAssetMap<String, TitanSpawnRuleAsset> ASSET_MAP = new DefaultAssetMap<>();

    /** Environment id to rule, rebuilt on demand and thrown away whenever the assets reload. */
    @Nullable
    private static volatile Map<String, TitanSpawnRuleAsset> byEnvironment;

    @Nonnull
    public static final AssetBuilderCodec<String, TitanSpawnRuleAsset> CODEC = AssetBuilderCodec.builder(
        TitanSpawnRuleAsset.class,
        TitanSpawnRuleAsset::new,
        Codec.STRING,
        (a, id) -> a.id = id,
        a -> a.id,
        (a, data) -> a.data = data,
        a -> a.data
    )
        .append(
            new KeyedCodec<>("Environments", Codec.STRING_ARRAY, true),
            (a, v) -> a.environments = v,
            a -> a.environments
        ).add()
        .append(
            new KeyedCodec<>("Variants", new ArrayCodec<>(TitanSpawnEntry.CODEC, TitanSpawnEntry[]::new), true),
            (a, v) -> a.variants = v,
            a -> a.variants
        ).add()
        .append(
            new KeyedCodec<>("Chance", Codec.FLOAT),
            (a, v) -> a.chance = v,
            a -> a.chance
        ).add()
        .append(
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            (a, v) -> a.enabled = v,
            a -> a.enabled
        ).add()
        .build();

    private String id;
    private AssetExtraInfo.Data data;

    private String[] environments = new String[0];
    private TitanSpawnEntry[] variants = new TitanSpawnEntry[0];
    private float chance = 0.25f;
    private boolean enabled = true;

    @Nonnull
    @Override
    public String getId() {
        return id;
    }

    @Nonnull
    public String[] getEnvironments() {
        return environments;
    }

    @Nonnull
    public TitanSpawnEntry[] getVariants() {
        return variants;
    }

    /**
     * Odds that any one candidate site in this environment actually hosts a titan, {@code 0} to {@code 1}.
     *
     * <p>Sites sit on a fixed grid of {@link com.hexvane.titan.spawn.TitanSite#CELL_BLOCKS} blocks, so this
     * reads directly as spacing: at {@code 0.25} roughly one cell in four is occupied, which across
     * unbroken matching terrain averages one titan every five hundred blocks or so. Most terrain is not
     * unbroken, and a site still has to survive the flatness check, so the number seen in play is lower.
     */
    public float getChance() {
        return chance;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Picks a variant by weight, skipping any the server owner has switched off.
     *
     * <p>A disabled variant is treated as weightless rather than as an empty result, so turning off one tier
     * hands its share of the sites to the tiers left standing instead of thinning the zone out.
     *
     * @param roll a value in {@code [0,1)}; the same roll always yields the same variant
     * @return {@code null} if the rule has nothing left it is allowed to spawn
     */
    @Nullable
    public String pickVariant(final double roll) {
        final TitanConfig config = TitanConfig.get();

        float total = 0f;
        for (final TitanSpawnEntry entry : variants) {
            if (entry.getWeight() > 0f && config.isVariantEnabled(entry.getVariant())) total += entry.getWeight();
        }
        if (total <= 0f) return null;

        double remaining = roll * total;
        String last = null;
        for (final TitanSpawnEntry entry : variants) {
            if (entry.getWeight() <= 0f || !config.isVariantEnabled(entry.getVariant())) continue;
            last = entry.getVariant();
            remaining -= entry.getWeight();
            if (remaining < 0) return last;
        }
        return last;
    }

    /** Whether any rule is loaded at all, so the spawn system can skip its scan entirely. */
    public static boolean hasRules() {
        return !index().isEmpty();
    }

    @Nullable
    public static TitanSpawnRuleAsset findForEnvironment(@Nullable final String environmentId) {
        return environmentId == null ? null : index().get(environmentId);
    }

    /** Drops the environment index so an asset reload is picked up on the next scan. */
    public static void invalidate() {
        byEnvironment = null;
    }

    @Nonnull
    private static Map<String, TitanSpawnRuleAsset> index() {
        Map<String, TitanSpawnRuleAsset> built = byEnvironment;
        if (built != null) return built;

        built = new HashMap<>();
        for (final TitanSpawnRuleAsset rule : ASSET_MAP.getAssetMap().values()) {
            if (!rule.enabled) continue;
            for (final String environment : rule.environments) {
                final TitanSpawnRuleAsset previous = built.putIfAbsent(environment, rule);
                if (previous != null) {
                    LOGGER.at(Level.WARNING).log(
                        "Titan spawn rules '%s' and '%s' both claim environment '%s'; keeping '%s'",
                        previous.id, rule.id, environment, previous.id);
                }
            }
        }

        byEnvironment = built;
        return built;
    }
}
