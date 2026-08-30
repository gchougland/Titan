package com.hexvane.titan.asset;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import javax.annotation.Nonnull;

/**
 * One variant a spawn rule may roll, and how often it wins against its siblings.
 */
public final class TitanSpawnEntry {

    @Nonnull
    public static final BuilderCodec<TitanSpawnEntry> CODEC = BuilderCodec.builder(TitanSpawnEntry.class, TitanSpawnEntry::new)
        .append(
            new KeyedCodec<>("Variant", Codec.STRING, true),
            (o, v) -> o.variant = v,
            o -> o.variant
        ).add()
        .append(
            new KeyedCodec<>("Weight", Codec.FLOAT),
            (o, v) -> o.weight = v,
            o -> o.weight
        ).add()
        .build();

    private String variant;
    private float weight = 1f;

    /** {@link TitanVariantAsset} id to spawn. */
    @Nonnull
    public String getVariant() {
        return variant;
    }

    /**
     * Share of the rule's rolls this entry takes, relative to its siblings. Weights are not percentages:
     * a 20 against a 1 means the second variant turns up on roughly one site in twenty.
     */
    public float getWeight() {
        return weight;
    }
}
