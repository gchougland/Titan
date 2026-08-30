package com.hexvane.titan.asset;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One spawnable titan: which skeleton it uses, how tough its ore weakpoints are, how it fights, and what
 * it drops. Ore tier is expressed entirely through this asset, so new tiers need no code.
 */
public final class TitanVariantAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, TitanVariantAsset>> {

    @Nonnull
    public static final DefaultAssetMap<String, TitanVariantAsset> ASSET_MAP = new DefaultAssetMap<>();

    @Nonnull
    public static final AssetBuilderCodec<String, TitanVariantAsset> CODEC = AssetBuilderCodec.builder(
        TitanVariantAsset.class,
        TitanVariantAsset::new,
        Codec.STRING,
        (a, id) -> a.id = id,
        a -> a.id,
        (a, data) -> a.data = data,
        a -> a.data
    )
        .append(
            new KeyedCodec<>("Skeleton", Codec.STRING, true),
            (a, v) -> a.skeleton = v,
            a -> a.skeleton
        ).add()
        .append(
            new KeyedCodec<>("DisplayName", Codec.STRING),
            (a, v) -> a.displayName = v,
            a -> a.displayName
        ).add()
        .append(
            new KeyedCodec<>("BodyScale", Codec.FLOAT),
            (a, v) -> a.bodyScale = v,
            a -> a.bodyScale
        ).add()
        .append(
            new KeyedCodec<>("RockType", Codec.STRING),
            (a, v) -> a.rockType = v,
            a -> a.rockType
        ).add()
        .append(
            new KeyedCodec<>("WeakpointModel", Codec.STRING),
            (a, v) -> a.weakpointModel = v,
            a -> a.weakpointModel
        ).add()
        .append(
            new KeyedCodec<>("WeakpointScale", Codec.FLOAT),
            (a, v) -> a.weakpointScale = v,
            a -> a.weakpointScale
        ).add()
        .append(
            new KeyedCodec<>("WeakpointCountMin", Codec.INTEGER),
            (a, v) -> a.weakpointCountMin = v,
            a -> a.weakpointCountMin
        ).add()
        .append(
            new KeyedCodec<>("WeakpointCountMax", Codec.INTEGER),
            (a, v) -> a.weakpointCountMax = v,
            a -> a.weakpointCountMax
        ).add()
        .append(
            new KeyedCodec<>("WeakpointEmbed", Codec.FLOAT),
            (a, v) -> a.weakpointEmbed = v,
            a -> a.weakpointEmbed
        ).add()
        .append(
            new KeyedCodec<>("WeakpointHealth", Codec.FLOAT),
            (a, v) -> a.weakpointHealth = v,
            a -> a.weakpointHealth
        ).add()
        .append(
            new KeyedCodec<>("MoveSpeed", Codec.FLOAT),
            (a, v) -> a.moveSpeed = v,
            a -> a.moveSpeed
        ).add()
        .append(
            new KeyedCodec<>("TurnSpeed", Codec.FLOAT),
            (a, v) -> a.turnSpeed = v,
            a -> a.turnSpeed
        ).add()
        .append(
            new KeyedCodec<>("WakeRadius", Codec.FLOAT),
            (a, v) -> a.wakeRadius = v,
            a -> a.wakeRadius
        ).add()
        .append(
            new KeyedCodec<>("LoseTargetRadius", Codec.FLOAT),
            (a, v) -> a.loseTargetRadius = v,
            a -> a.loseTargetRadius
        ).add()
        .append(
            new KeyedCodec<>("AttackRange", Codec.FLOAT),
            (a, v) -> a.attackRange = v,
            a -> a.attackRange
        ).add()
        .append(
            new KeyedCodec<>("AttackDamage", Codec.FLOAT),
            (a, v) -> a.attackDamage = v,
            a -> a.attackDamage
        ).add()
        .append(
            new KeyedCodec<>("AttackRadius", Codec.FLOAT),
            (a, v) -> a.attackRadius = v,
            a -> a.attackRadius
        ).add()
        .append(
            new KeyedCodec<>("AttackKnockback", Codec.FLOAT),
            (a, v) -> a.attackKnockback = v,
            a -> a.attackKnockback
        ).add()
        .append(
            new KeyedCodec<>("AttackCooldown", Codec.FLOAT),
            (a, v) -> a.attackCooldown = v,
            a -> a.attackCooldown
        ).add()
        .append(
            new KeyedCodec<>("StunSeconds", Codec.FLOAT),
            (a, v) -> a.stunSeconds = v,
            a -> a.stunSeconds
        ).add()
        .append(
            new KeyedCodec<>("SlamChance", Codec.FLOAT),
            (a, v) -> a.slamChance = v,
            a -> a.slamChance
        ).add()
        .append(
            new KeyedCodec<>("SlamRadius", Codec.FLOAT),
            (a, v) -> a.slamRadius = v,
            a -> a.slamRadius
        ).add()
        .append(
            new KeyedCodec<>("SlamProneSeconds", Codec.FLOAT),
            (a, v) -> a.slamProneSeconds = v,
            a -> a.slamProneSeconds
        ).add()
        .append(
            new KeyedCodec<>("DropList", Codec.STRING),
            (a, v) -> a.dropList = v,
            a -> a.dropList
        ).add()
        .append(
            new KeyedCodec<>("DropItem", Codec.STRING),
            (a, v) -> a.dropItem = v,
            a -> a.dropItem
        ).add()
        .append(
            new KeyedCodec<>("DropCountMin", Codec.INTEGER),
            (a, v) -> a.dropCountMin = v,
            a -> a.dropCountMin
        ).add()
        .append(
            new KeyedCodec<>("DropCountMax", Codec.INTEGER),
            (a, v) -> a.dropCountMax = v,
            a -> a.dropCountMax
        ).add()
        .append(
            new KeyedCodec<>("ImpactParticle", Codec.STRING),
            (a, v) -> a.impactParticle = v,
            a -> a.impactParticle
        ).add()
        .append(
            new KeyedCodec<>("ImpactSound", Codec.STRING),
            (a, v) -> a.impactSound = v,
            a -> a.impactSound
        ).add()
        .append(
            new KeyedCodec<>("WakeSound", Codec.STRING),
            (a, v) -> a.wakeSound = v,
            a -> a.wakeSound
        ).add()
        .append(
            new KeyedCodec<>("DeathSound", Codec.STRING),
            (a, v) -> a.deathSound = v,
            a -> a.deathSound
        ).add()
        .build();

    private String id;
    private AssetExtraInfo.Data data;

    private String skeleton;
    @Nullable
    private String displayName;
    private float bodyScale = 1f;
    @Nullable
    private String rockType;
    @Nullable
    private String weakpointModel;
    private float weakpointScale = 1f;
    private int weakpointCountMin = 2;
    private int weakpointCountMax = 4;
    private float weakpointEmbed = 0.25f;
    private float weakpointHealth = 100f;
    private float moveSpeed = 1.6f;
    private float turnSpeed = 0.9f;
    private float wakeRadius = 14f;
    private float loseTargetRadius = 48f;
    private float attackRange = 9f;
    private float attackDamage = 18f;
    private float attackRadius = 5f;
    private float attackKnockback = 14f;
    private float attackCooldown = 4.5f;
    private float stunSeconds = 3.5f;
    private float slamChance = 0.35f;
    private float slamRadius = 6f;
    private float slamProneSeconds = 6f;
    @Nullable
    private String dropList;
    @Nullable
    private String dropItem;
    private int dropCountMin = 8;
    private int dropCountMax = 16;
    @Nullable
    private String impactParticle;
    @Nullable
    private String impactSound;
    @Nullable
    private String wakeSound;
    @Nullable
    private String deathSound;

    @Nonnull
    @Override
    public String getId() {
        return id;
    }

    @Nonnull
    public String getSkeleton() {
        return skeleton;
    }

    @Nonnull
    public String getDisplayName() {
        return displayName == null ? id : displayName;
    }

    /** Multiplies the skeleton's unit scale; the whole rig, colliders included, grows with it. */
    public float getBodyScale() {
        return bodyScale;
    }

    /**
     * Rock the titan is carved from, as a suffix appended to every bone's prefab: a {@code RockType} of
     * {@code Basalt} turns {@code Titan/Talus/Talus_Body} into {@code Titan/Talus/Talus_Body_Basalt}.
     *
     * <p>Unset means the skeleton's own prefabs are used as authored, which is the plain stone look. A
     * variant whose suffixed prefab is missing falls back to the unsuffixed one, so a rock type only has to
     * ship the parts it actually changes.
     */
    @Nullable
    public String getRockType() {
        return rockType;
    }

    /** {@code ModelAsset} id rendered for each ore weakpoint. */
    @Nullable
    public String getWeakpointModel() {
        return weakpointModel;
    }

    public float getWeakpointScale() {
        return weakpointScale;
    }

    /** Fewest ore nodes a spawn may roll. */
    public int getWeakpointCountMin() {
        return weakpointCountMin;
    }

    /** Most ore nodes a spawn may roll; each titan picks that many sockets at random. */
    public int getWeakpointCountMax() {
        return weakpointCountMax;
    }

    /**
     * How far past the body surface an ore node's centre is pushed, in model units.
     *
     * <p>Sockets are authored on the surface and a node is centred on its socket, so zero already buries
     * half of it. Positive values sink it further; negative values leave it standing proud, which is what
     * the variants use to keep a doubled-size node reading as a cluster growing out of the rock rather than
     * a lump swallowed by it.
     */
    public float getWeakpointEmbed() {
        return weakpointEmbed;
    }

    public float getWeakpointHealth() {
        return weakpointHealth;
    }

    /** Blocks per second while chasing. */
    public float getMoveSpeed() {
        return moveSpeed;
    }

    /** Radians per second of body yaw. */
    public float getTurnSpeed() {
        return turnSpeed;
    }

    /**
     * How close a player must get before a sleeping titan stands up, in blocks from its root.
     *
     * <p>Deliberately short. Titans placed by world generation spend most of their life curled up looking
     * like scenery, and the disguise only works if a player can walk past one at a distance and never learn
     * it was a titan. Widening this turns them back into monsters that are visibly monsters.
     */
    public float getWakeRadius() {
        return wakeRadius;
    }

    public float getLoseTargetRadius() {
        return loseTargetRadius;
    }

    public float getAttackRange() {
        return attackRange;
    }

    public float getAttackDamage() {
        return attackDamage;
    }

    public float getAttackRadius() {
        return attackRadius;
    }

    public float getAttackKnockback() {
        return attackKnockback;
    }

    public float getAttackCooldown() {
        return attackCooldown;
    }

    /** How long the hand stays embedded after a smash — the window in which the arm is climbable. */
    public float getStunSeconds() {
        return stunSeconds;
    }

    /** Odds of answering an attack opportunity with a body slam rather than an arm smash, {@code 0} to {@code 1}. */
    public float getSlamChance() {
        return slamChance;
    }

    /**
     * Blast radius of a body slam. Wider than the arm smash because the whole creature lands, and the
     * price of that reach is how long it then spends face down.
     */
    public float getSlamRadius() {
        return slamRadius;
    }

    /** How long the titan lies face down after a slam — the window in which its back is within jumping range. */
    public float getSlamProneSeconds() {
        return slamProneSeconds;
    }

    @Nullable
    public String getDropList() {
        return dropList;
    }

    /** Fallback item id used when {@link #getDropList()} is unset or resolves to nothing. */
    @Nullable
    public String getDropItem() {
        return dropItem;
    }

    public int getDropCountMin() {
        return dropCountMin;
    }

    public int getDropCountMax() {
        return dropCountMax;
    }

    @Nullable
    public String getImpactParticle() {
        return impactParticle;
    }

    @Nullable
    public String getImpactSound() {
        return impactSound;
    }

    @Nullable
    public String getWakeSound() {
        return wakeSound;
    }

    @Nullable
    public String getDeathSound() {
        return deathSound;
    }

    @Nullable
    public static TitanVariantAsset find(@Nullable final String id) {
        return id == null ? null : ASSET_MAP.getAsset(id);
    }
}
