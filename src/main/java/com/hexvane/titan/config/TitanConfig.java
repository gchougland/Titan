package com.hexvane.titan.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Server-owner tuning knobs, read from {@code mods/Hexvane_Titan/config.json}.
 *
 * <p>The tuning is all multipliers rather than absolute numbers. The per-variant assets already say what a
 * Copper Talus hits for and what a Mithril one is worth; these scale the whole ladder at once so a server
 * can make titans harsher or gentler without editing six files and losing the balance between them. On top
 * of that sits a list of variants to leave out of the world entirely.
 *
 * <p>The values are read at spawn or hit time, so editing the file and reloading is enough for the tool
 * bonuses and any titan spawned afterwards.
 */
public final class TitanConfig {

    @Nonnull
    public static final BuilderCodec<TitanConfig> CODEC = BuilderCodec.builder(TitanConfig.class, TitanConfig::new)
        .append(
            new KeyedCodec<>("WeakpointHealthMultiplier", Codec.DOUBLE),
            (c, v) -> c.weakpointHealthMultiplier = v,
            c -> c.weakpointHealthMultiplier
        ).add()
        .append(
            new KeyedCodec<>("AttackDamageMultiplier", Codec.DOUBLE),
            (c, v) -> c.attackDamageMultiplier = v,
            c -> c.attackDamageMultiplier
        ).add()
        .append(
            new KeyedCodec<>("AttackKnockbackMultiplier", Codec.DOUBLE),
            (c, v) -> c.attackKnockbackMultiplier = v,
            c -> c.attackKnockbackMultiplier
        ).add()
        .append(
            new KeyedCodec<>("PickaxeDamageMultiplier", Codec.DOUBLE),
            (c, v) -> c.pickaxeDamageMultiplier = v,
            c -> c.pickaxeDamageMultiplier
        ).add()
        .append(
            new KeyedCodec<>("MaceDamageMultiplier", Codec.DOUBLE),
            (c, v) -> c.maceDamageMultiplier = v,
            c -> c.maceDamageMultiplier
        ).add()
        .append(
            new KeyedCodec<>("BattleMusic", Codec.BOOLEAN),
            (c, v) -> c.battleMusic = v,
            c -> c.battleMusic
        ).add()
        .append(
            new KeyedCodec<>("Telegraphs", Codec.BOOLEAN),
            (c, v) -> c.telegraphs = v,
            c -> c.telegraphs
        ).add()
        .append(
            new KeyedCodec<>("DisabledVariants", Codec.STRING_ARRAY),
            (c, v) -> c.disabledVariants = v,
            c -> c.disabledVariants
        ).add()
        .build();

    /** Used before the plugin has handed over the loaded file, and if loading fails. */
    @Nonnull
    private static volatile TitanConfig active = new TitanConfig();

    @Nonnull
    public static TitanConfig get() {
        return active;
    }

    public static void setActive(@Nonnull final TitanConfig config) {
        active = config;
    }

    private double weakpointHealthMultiplier = 1;
    private double attackDamageMultiplier = 1;
    private double attackKnockbackMultiplier = 1;
    private double pickaxeDamageMultiplier = 18;
    private double maceDamageMultiplier = 1.6;
    private boolean battleMusic = true;
    private boolean telegraphs = true;
    @Nonnull
    private String[] disabledVariants = new String[0];

    /** Scales how much punishment each ore node takes before it breaks. */
    public float getWeakpointHealthMultiplier() {
        return clamp(weakpointHealthMultiplier);
    }

    /** Scales the damage a smash or body slam deals to whoever it catches. */
    public float getAttackDamageMultiplier() {
        return clamp(attackDamageMultiplier);
    }

    /** Scales how far a smash or body slam throws whoever it catches. */
    public float getAttackKnockbackMultiplier() {
        return clamp(attackKnockbackMultiplier);
    }

    /**
     * Scales pickaxe damage against ore nodes.
     *
     * <p>Large by default, and it has to be: every pickaxe in the game overrides its damage against
     * entities to a flat 1 regardless of what it is made of, so at face value even a mithril one would need
     * a hundred swings per node. This is what makes the obvious tool for prising ore out of rock the right
     * one to bring.
     */
    public float getPickaxeDamageMultiplier() {
        return clamp(pickaxeDamageMultiplier);
    }

    /**
     * Scales mace damage against ore nodes. Modest on purpose: maces already hit for 29 to 93, so they only
     * need a nudge to be the best weapon for the job rather than a rewrite.
     */
    public float getMaceDamageMultiplier() {
        return clamp(maceDamageMultiplier);
    }

    /**
     * Whether fighting a titan takes over the music.
     *
     * <p>Forced music overrides whatever the zone was playing for as long as the bar is up, which is the
     * right call for a boss and the wrong one for a server that has put work into its own soundtrack.
     */
    public boolean isBattleMusicEnabled() {
        return battleMusic;
    }

    /**
     * Whether attacks are marked on the ground before they land.
     *
     * <p>On by default: a titan is large enough that its windups are easy to miss from underneath it, and
     * every one of its attacks is meant to be dodgeable. Turning this off leaves the animations as the only
     * warning, which is how the fight read before and is a genuinely harder version of it.
     */
    public boolean areTelegraphsEnabled() {
        return telegraphs;
    }

    /**
     * Whether a titan variant is allowed to exist on this server.
     *
     * <p>Listing a variant's id under {@code DisabledVariants} takes it out of natural spawning and refuses
     * it to the spawn command, so an owner who does not want, say, the mithril tier can drop it without
     * touching the spawn rules. The shipped ids are {@code Stone_Talus_Copper}, {@code Stone_Talus_Iron},
     * {@code Stone_Talus_Cobalt}, {@code Stone_Talus_Thorium}, {@code Stone_Talus_Adamantite} and
     * {@code Stone_Talus_Mithril}.
     */
    public boolean isVariantEnabled(@Nullable final String variantId) {
        if (variantId == null || disabledVariants.length == 0) return true;

        for (final String disabled : disabledVariants) {
            if (variantId.equalsIgnoreCase(disabled)) return false;
        }
        return true;
    }

    /**
     * A hand-edited file can hold anything; a negative multiplier would heal on hit.
     *
     * <p>Stored as doubles rather than floats only so the file survives being rewritten: a float widened
     * back out turns a tidy {@code 1.2} into {@code 1.2000000476837158}, and the config is written back on
     * every boot to pick up options added since it was created.
     */
    private static float clamp(final double value) {
        return Double.isFinite(value) && value > 0 ? (float) value : 1f;
    }
}
