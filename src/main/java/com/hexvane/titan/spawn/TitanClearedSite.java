package com.hexvane.titan.spawn;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import javax.annotation.Nonnull;

/**
 * One site whose titan has been brought down, and how much longer it stays empty.
 */
public final class TitanClearedSite {

    @Nonnull
    public static final BuilderCodec<TitanClearedSite> CODEC = BuilderCodec.builder(TitanClearedSite.class, TitanClearedSite::new)
        .append(
            new KeyedCodec<>("Cell", Codec.LONG, true),
            (o, v) -> o.cell = v,
            o -> o.cell
        ).add()
        .append(
            new KeyedCodec<>("Seconds", Codec.FLOAT, true),
            (o, v) -> o.seconds = v,
            o -> o.seconds
        ).add()
        .build();

    private long cell;
    private float seconds;

    public TitanClearedSite() {
    }

    public TitanClearedSite(final long cell, final float seconds) {
        this.cell = cell;
        this.seconds = seconds;
    }

    /** Packed grid cell, as produced by {@link TitanSite#cellKey}. */
    public long getCell() {
        return cell;
    }

    /** Seconds left before the site may be occupied again. */
    public float getSeconds() {
        return seconds;
    }
}
