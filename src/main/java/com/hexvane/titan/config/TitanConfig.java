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
        .append(
            new KeyedCodec<>("PartSyncEpsilon", Codec.DOUBLE),
            (c, v) -> c.partSyncEpsilon = v,
            c -> c.partSyncEpsilon
        ).add()
        .append(
            new KeyedCodec<>("PartSyncRotationEpsilon", Codec.DOUBLE),
            (c, v) -> c.partSyncRotationEpsilon = v,
            c -> c.partSyncRotationEpsilon
        ).add()
        .append(
            new KeyedCodec<>("PartSyncInterval", Codec.DOUBLE),
            (c, v) -> c.partSyncInterval = v,
            c -> c.partSyncInterval
        ).add()
        .append(
            new KeyedCodec<>("EntityLodRatio", Codec.DOUBLE),
            (c, v) -> c.entityLodRatio = v,
            c -> c.entityLodRatio
        ).add()
        .append(
            new KeyedCodec<>("ParallelPartSync", Codec.BOOLEAN),
            (c, v) -> c.parallelPartSync = v,
            c -> c.parallelPartSync
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
    private double partSyncEpsilon = 0.1;
    private double partSyncRotationEpsilon = 0.25;
    private double partSyncInterval;
    private double entityLodRatio;
    private boolean parallelPartSync;

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
     * How far one of a titan's voxels may drift from where the clients think it is before the server
     * bothers to correct them, in blocks.
     *
     * <p>This is the main lever on how much bandwidth a walking titan costs. A titan is held together by
     * rewriting every voxel's transform from its bones, and the engine turns each rewritten transform into
     * an update in that tick's packet — a large one walking is thousands of them, in a single packet the
     * engine does not split. Past what a connection can carry the updates start arriving late or not at
     * all, which is what makes a titan's blocks flicker as it moves.
     *
     * <p>Raising this trades accuracy for room. The error it buys is bounded rather than cumulative,
     * because a part is measured against what was last sent instead of against its last computed position,
     * so a voxel is never more than this far from the truth however long the titan walks. The default is a
     * tenth of a block, which on a titan tens of blocks tall is not something you can see, and which at a
     * walking pace lets most parts sit out two ticks in three.
     *
     * <p>Zero sends every part every tick, which is the old behaviour and the honest baseline to measure
     * against with {@code /titan perf}.
     */
    public double getPartSyncEpsilon() {
        return nonNegative(partSyncEpsilon);
    }

    /**
     * The same deadband for a voxel's own orientation, configured in degrees and returned in radians.
     *
     * <p>Separate from the positional one because it means something much smaller: a part's rotation only
     * spins the single block in place, so a quarter of a degree is invisible, whereas the swing that
     * rotation causes further out along a limb already shows up in that part's position and is caught by
     * {@link #getPartSyncEpsilon}. Both have to be inside their deadband for an update to be skipped.
     */
    public double getPartSyncRotationEpsilon() {
        return Math.toRadians(nonNegative(partSyncRotationEpsilon));
    }

    /**
     * Shortest gap between two updates for the same voxel, in seconds. Zero means every tick.
     *
     * <p>Where the epsilon drops updates that say nothing, this one drops updates that would have said
     * something, so it costs real smoothness and is off by default. Parts are staggered across the
     * interval rather than all resent together, which keeps the peak packet down as well as the average —
     * resending the whole body on every fourth tick would leave the spike that causes the flicker exactly
     * where it was.
     *
     * <p>Whether a longer gap is visible depends on how the client interpolates between updates, which is
     * not something this end can find out, so it is a knob to try rather than a setting with a right
     * answer. {@code 0.1} halves the traffic outright.
     */
    public double getPartSyncInterval() {
        return nonNegative(partSyncInterval);
    }

    /**
     * Overrides the engine's entity level-of-detail ratio, or zero to leave it alone.
     *
     * <p>The engine stops sending a client any entity whose thickness is small next to its distance —
     * {@code thickness < ratio * distance²} — which for a one-block voxel at the shipped {@code 0.000035}
     * is about 169 blocks. That is inside the default view distance, so a titan seen from across a valley
     * dissolves from the outside in while its silhouette is still perfectly readable. Lowering the ratio
     * pushes that boundary out: {@code 0.000015} reaches roughly 258 blocks, past the default view radius.
     *
     * <p>It is a global, so this affects every small entity, dropped items included, and every one of them
     * that stays visible for longer is more to replicate. That is why it is an opt-in number here rather
     * than a value the mod quietly changes on the engine's behalf.
     */
    public double getEntityLodRatio() {
        return nonNegative(entityLodRatio);
    }

    /**
     * Whether a titan's voxels are re-posed across the tick thread pool rather than on the world thread.
     *
     * <p>Worth having on a titan the size of the Roaming Temple, where the work is thousands of independent
     * matrix multiplications in one archetype chunk and fans out perfectly. The mod's side of it is written
     * to be safe either way: every part touches only its own components, the scratch it works in is per
     * thread, and the owning titan is read through the command buffer so it does not trip the world thread
     * assertion.
     *
     * <p>Off by default because of the engine's side rather than ours. Parallel entity ticking is
     * implemented and live, but of everything that ships only {@code FluidSystems} actually asks for it —
     * every other system routes through {@code maybeUseParallel}, which is stubbed to return false with the
     * real condition commented out. That is a lightly travelled road to put a titan on without being asked,
     * so it is a switch to reach for when a titan is costing world-thread time rather than the default.
     */
    public boolean isParallelPartSync() {
        return parallelPartSync;
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

    /** As {@link #clamp}, for the tolerances, where zero is a meaningful setting and the fallback is off. */
    private static double nonNegative(final double value) {
        return Double.isFinite(value) && value > 0 ? value : 0;
    }
}
