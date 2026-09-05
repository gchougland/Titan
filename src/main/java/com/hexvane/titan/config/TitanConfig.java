package com.hexvane.titan.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Server-owner tuning knobs, read from {@code mods/Hexvane_Titan/config.json}.
 *
 * <p>Combat tuning is expressed as multipliers rather than absolute values, since the per-variant assets
 * already define each tier. Scaling them together keeps the balance between tiers intact. A list of
 * disabled variant ids sits alongside them.
 *
 * <p>Values are read at spawn or hit time, so a reload applies to tool bonuses and to any titan spawned
 * afterwards.
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
        .append(
            new KeyedCodec<>("WandLog", Codec.BOOLEAN),
            (c, v) -> c.wandLog = v,
            c -> c.wandLog
        ).add()
        .build();

    /** Used before the plugin has handed over the loaded file, and if loading fails. */
    @Nonnull
    private static volatile TitanConfig active = new TitanConfig();

    /** @return the config currently in force */
    @Nonnull
    public static TitanConfig get() {
        return active;
    }

    /** Installs the config loaded from disk. Called once during plugin setup. */
    public static void setActive(@Nonnull final TitanConfig config) {
        active = config;
    }

    private double weakpointHealthMultiplier = 1.5;
    private double attackDamageMultiplier = 1.5;
    private double attackKnockbackMultiplier = 0.8;
    private double pickaxeDamageMultiplier = 18;
    private double maceDamageMultiplier = 1.4;
    private boolean battleMusic = true;
    private boolean telegraphs = true;
    @Nonnull
    private String[] disabledVariants = new String[0];
    private double partSyncEpsilon = 0.1;
    private double partSyncRotationEpsilon = 0.25;
    private double partSyncInterval;
    private double entityLodRatio;
    private boolean parallelPartSync;
    private boolean wandLog = true;

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
     * <p>Large by default because every pickaxe overrides its damage against entities to a flat 1
     * regardless of material, which would otherwise leave even a mithril pickaxe needing a hundred swings
     * per node.
     */
    public float getPickaxeDamageMultiplier() {
        return clamp(pickaxeDamageMultiplier);
    }

    /**
     * Scales mace damage against ore nodes. Kept small because maces already hit for 29 to 93, so only a
     * nudge is needed to make them the best weapon for the job.
     */
    public float getMaceDamageMultiplier() {
        return clamp(maceDamageMultiplier);
    }

    /**
     * Whether fighting a titan takes over the music.
     *
     * <p>Forced music overrides the zone soundtrack for as long as the boss bar is up, so servers with a
     * curated soundtrack of their own may want it off.
     */
    public boolean isBattleMusicEnabled() {
        return battleMusic;
    }

    /**
     * Whether attacks are marked on the ground before they land.
     *
     * <p>On by default, since a titan is large enough that its windup animations are easy to miss from
     * underneath it and every attack is meant to be dodgeable. Turning this off leaves the animations as
     * the only warning and makes the fight considerably harder.
     */
    public boolean areTelegraphsEnabled() {
        return telegraphs;
    }

    /**
     * Whether a titan variant is allowed to exist on this server.
     *
     * <p>Listing a variant id under {@code DisabledVariants} removes it from natural spawning and rejects
     * it from the spawn command, without any change to the spawn rule assets. The shipped ids are
     * {@code Stone_Talus_Copper}, {@code Stone_Talus_Iron},
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
     * <p>This is the main lever on the bandwidth a walking titan costs. Every voxel transform rewritten
     * from the bones becomes an update in that tick's packet, which the engine does not split, so a large
     * titan walking produces thousands of them at once. Beyond what the connection carries, updates arrive
     * late or not at all and the blocks visibly flicker.
     *
     * <p>The resulting error is bounded rather than cumulative, because each part is compared against what
     * was last sent rather than its last computed position. The default of a tenth of a block is not
     * visible on a titan tens of blocks tall and lets most parts skip two ticks in three at walking pace.
     *
     * <p>Zero sends every part every tick, which is the baseline to measure against with {@code /titan perf}.
     */
    public double getPartSyncEpsilon() {
        return nonNegative(partSyncEpsilon);
    }

    /**
     * The same deadband for a voxel's own orientation, configured in degrees and returned in radians.
     *
     * <p>Held separately from the positional deadband because a part's own rotation only spins one block in
     * place, making a quarter of a degree invisible. The displacement that rotation causes further out
     * along a limb shows up as position and is caught by {@link #getPartSyncEpsilon}. An update is skipped
     * only when both deadbands are satisfied.
     */
    public double getPartSyncRotationEpsilon() {
        return Math.toRadians(nonNegative(partSyncRotationEpsilon));
    }

    /**
     * Shortest gap between two updates for the same voxel, in seconds. Zero means every tick.
     *
     * <p>Unlike the deadbands, this drops updates that did have something to report, so it costs real
     * smoothness and is off by default. Parts are staggered across the interval rather than resent
     * together, which lowers the peak packet as well as the average; resending the whole body every fourth
     * tick would leave the spike that causes the flicker untouched.
     *
     * <p>Visibility of a longer gap depends on client-side interpolation, which the server cannot observe,
     * so this is best measured in practice. {@code 0.1} halves the traffic.
     */
    public double getPartSyncInterval() {
        return nonNegative(partSyncInterval);
    }

    /**
     * Overrides the engine's entity level-of-detail ratio, or zero to leave it alone.
     *
     * <p>The engine drops any entity satisfying {@code thickness < ratio * distance²}, which for a
     * one-block voxel at the shipped {@code 0.000035} is about 169 blocks. That falls inside the default
     * view distance, so a distant titan dissolves from the outside in while its silhouette is still
     * readable. Lowering the ratio pushes the boundary out, and {@code 0.000015} reaches roughly 258
     * blocks.
     *
     * <p>The setting is global, so it also keeps every other small entity visible for longer, dropped items
     * included, at a corresponding replication cost. It is opt-in for that reason.
     */
    public double getEntityLodRatio() {
        return nonNegative(entityLodRatio);
    }

    /**
     * Whether a titan's voxels are re-posed across the tick thread pool rather than on the world thread.
     *
     * <p>Worthwhile on very large titans, where the work is thousands of independent matrix multiplications
     * in a single archetype chunk. The mod side is safe either way: each part touches only its own
     * components, scratch state is per thread, and the owning titan is read through the command buffer so
     * the world thread assertion is not tripped.
     *
     * <p>Off by default because of the engine side. Parallel entity ticking is implemented, but of the
     * shipped systems only {@code FluidSystems} requests it; the rest route through
     * {@code maybeUseParallel}, which is stubbed to return false. Enable this when a titan is costing
     * world-thread time rather than bandwidth.
     */
    public boolean isParallelPartSync() {
        return parallelPartSync;
    }

    /**
     * Whether each press and release of the wand is logged.
     *
     * <p>On by default, because a wand that is not working and a wand that is not being pointed at anything
     * look the same from here: the house stands still either way. A line says the wand was heard and the
     * house had its own reasons for staying put; no line says the press never reached the server at all.
     */
    public boolean isWandLogEnabled() {
        return wandLog;
    }

    /**
     * Falls back to {@code 1} for any value a hand-edited file should not contain, such as a negative
     * multiplier that would heal on hit.
     *
     * <p>The fields are doubles rather than floats so the file survives the rewrite on every boot. A float
     * widened back out would turn {@code 1.2} into {@code 1.2000000476837158}.
     */
    private static float clamp(final double value) {
        return Double.isFinite(value) && value > 0 ? (float) value : 1f;
    }

    /** As {@link #clamp}, for tolerances, where zero is a meaningful setting and so is the fallback. */
    private static double nonNegative(final double value) {
        return Double.isFinite(value) && value > 0 ? value : 0;
    }
}
