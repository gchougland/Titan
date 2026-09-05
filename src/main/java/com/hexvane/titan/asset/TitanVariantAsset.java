package com.hexvane.titan.asset;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;

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
            new KeyedCodec<>("WeakpointsToKill", Codec.INTEGER),
            (a, v) -> a.weakpointsToKill = v,
            a -> a.weakpointsToKill
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
            new KeyedCodec<>("ShellHealth", Codec.FLOAT),
            (a, v) -> a.shellHealth = v,
            a -> a.shellHealth
        ).add()
        .append(
            new KeyedCodec<>("SpawnFootprintRadius", Codec.INTEGER),
            (a, v) -> a.spawnFootprintRadius = v,
            a -> a.spawnFootprintRadius
        ).add()
        .append(
            new KeyedCodec<>("SpawnFootprintRelief", Codec.INTEGER),
            (a, v) -> a.spawnFootprintRelief = v,
            a -> a.spawnFootprintRelief
        ).add()
        .append(
            new KeyedCodec<>("SpawnHeadroom", Codec.INTEGER),
            (a, v) -> a.spawnHeadroom = v,
            a -> a.spawnHeadroom
        ).add()
        .append(
            new KeyedCodec<>("SpawnLevelToLowest", Codec.BOOLEAN),
            (a, v) -> a.spawnLevelToLowest = v,
            a -> a.spawnLevelToLowest
        ).add()
        .append(
            new KeyedCodec<>("GroundPrefab", Codec.STRING),
            (a, v) -> a.groundPrefab = v,
            a -> a.groundPrefab
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
            new KeyedCodec<>("LeashRadius", Codec.FLOAT),
            (a, v) -> a.leashRadius = v,
            a -> a.leashRadius
        ).add()
        .append(
            new KeyedCodec<>("StartAwake", Codec.BOOLEAN),
            (a, v) -> a.startAwake = v,
            a -> a.startAwake
        ).add()
        .append(
            new KeyedCodec<>("Passive", Codec.BOOLEAN),
            (a, v) -> a.passive = v,
            a -> a.passive
        ).add()
        .append(
            new KeyedCodec<>("Pet", Codec.BOOLEAN),
            (a, v) -> a.pet = v,
            a -> a.pet
        ).add()
        .append(
            new KeyedCodec<>("SpawnFxRadius", Codec.FLOAT),
            (a, v) -> a.spawnFxRadius = v,
            a -> a.spawnFxRadius
        ).add()
        .append(
            new KeyedCodec<>("SpawnFxDuration", Codec.FLOAT),
            (a, v) -> a.spawnFxDuration = v,
            a -> a.spawnFxDuration
        ).add()
        .append(
            new KeyedCodec<>("SpawnFxStagger", Codec.FLOAT),
            (a, v) -> a.spawnFxStagger = v,
            a -> a.spawnFxStagger
        ).add()
        .append(
            new KeyedCodec<>("Fixtures", new ArrayCodec<>(TitanFixtureDef.CODEC, TitanFixtureDef[]::new)),
            (a, v) -> a.fixtures = v,
            a -> a.fixtures
        ).add()
        .append(
            new KeyedCodec<>("FollowDistance", Codec.DOUBLE),
            (a, v) -> a.followDistance = v,
            a -> a.followDistance
        ).add()
        .append(
            new KeyedCodec<>("CrouchDepth", Codec.DOUBLE),
            (a, v) -> a.crouchDepth = v,
            a -> a.crouchDepth
        ).add()
        .append(
            new KeyedCodec<>("RestSink", Codec.DOUBLE),
            (a, v) -> a.restSink = v,
            a -> a.restSink
        ).add()
        .append(
            new KeyedCodec<>("AboardRadius", Codec.DOUBLE),
            (a, v) -> a.aboardRadius = v,
            a -> a.aboardRadius
        ).add()
        .append(
            new KeyedCodec<>("WandSpeed", Codec.DOUBLE),
            (a, v) -> a.wandSpeed = v,
            a -> a.wandSpeed
        ).add()
        .append(
            new KeyedCodec<>("WandTurnSpeed", Codec.FLOAT),
            (a, v) -> a.wandTurnSpeed = v,
            a -> a.wandTurnSpeed
        ).add()
        .append(
            new KeyedCodec<>("LeapHeight", Codec.DOUBLE),
            (a, v) -> a.leapHeight = v,
            a -> a.leapHeight
        ).add()
        .append(
            new KeyedCodec<>("LeapSpeed", Codec.DOUBLE),
            (a, v) -> a.leapSpeed = v,
            a -> a.leapSpeed
        ).add()
        .append(
            new KeyedCodec<>("Chase", Codec.BOOLEAN),
            (a, v) -> a.chase = v,
            a -> a.chase
        ).add()
        .append(
            new KeyedCodec<>("WanderRadius", Codec.FLOAT),
            (a, v) -> a.wanderRadius = v,
            a -> a.wanderRadius
        ).add()
        .append(
            new KeyedCodec<>("WanderPauseMin", Codec.FLOAT),
            (a, v) -> a.wanderPauseMin = v,
            a -> a.wanderPauseMin
        ).add()
        .append(
            new KeyedCodec<>("WanderPauseMax", Codec.FLOAT),
            (a, v) -> a.wanderPauseMax = v,
            a -> a.wanderPauseMax
        ).add()
        .append(
            new KeyedCodec<>("Environments", Codec.STRING_ARRAY),
            (a, v) -> a.environments = v,
            a -> a.environments
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
            new KeyedCodec<>("SmashChance", Codec.FLOAT),
            (a, v) -> a.smashChance = v,
            a -> a.smashChance
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
            new KeyedCodec<>("PoundChance", Codec.FLOAT),
            (a, v) -> a.poundChance = v,
            a -> a.poundChance
        ).add()
        .append(
            new KeyedCodec<>("PoundRadius", Codec.FLOAT),
            (a, v) -> a.poundRadius = v,
            a -> a.poundRadius
        ).add()
        .append(
            new KeyedCodec<>("PoundDamage", Codec.FLOAT),
            (a, v) -> a.poundDamage = v,
            a -> a.poundDamage
        ).add()
        .append(
            new KeyedCodec<>("PoundLaunch", Codec.FLOAT),
            (a, v) -> a.poundLaunch = v,
            a -> a.poundLaunch
        ).add()
        .append(
            new KeyedCodec<>("PoundStunSeconds", Codec.FLOAT),
            (a, v) -> a.poundStunSeconds = v,
            a -> a.poundStunSeconds
        ).add()
        .append(
            new KeyedCodec<>("HurlChance", Codec.FLOAT),
            (a, v) -> a.hurlChance = v,
            a -> a.hurlChance
        ).add()
        .append(
            new KeyedCodec<>("HurlMinRange", Codec.FLOAT),
            (a, v) -> a.hurlMinRange = v,
            a -> a.hurlMinRange
        ).add()
        .append(
            new KeyedCodec<>("HurlMaxRange", Codec.FLOAT),
            (a, v) -> a.hurlMaxRange = v,
            a -> a.hurlMaxRange
        ).add()
        .append(
            new KeyedCodec<>("HurlSpeed", Codec.FLOAT),
            (a, v) -> a.hurlSpeed = v,
            a -> a.hurlSpeed
        ).add()
        .append(
            new KeyedCodec<>("HurlDamage", Codec.FLOAT),
            (a, v) -> a.hurlDamage = v,
            a -> a.hurlDamage
        ).add()
        .append(
            new KeyedCodec<>("HurlRadius", Codec.FLOAT),
            (a, v) -> a.hurlRadius = v,
            a -> a.hurlRadius
        ).add()
        .append(
            new KeyedCodec<>("HurlKnockback", Codec.FLOAT),
            (a, v) -> a.hurlKnockback = v,
            a -> a.hurlKnockback
        ).add()
        .append(
            new KeyedCodec<>("HurlPrefab", Codec.STRING),
            (a, v) -> a.hurlPrefab = v,
            a -> a.hurlPrefab
        ).add()
        .append(
            new KeyedCodec<>("PlowChance", Codec.FLOAT),
            (a, v) -> a.plowChance = v,
            a -> a.plowChance
        ).add()
        .append(
            new KeyedCodec<>("PlowCooldown", Codec.FLOAT),
            (a, v) -> a.plowCooldown = v,
            a -> a.plowCooldown
        ).add()
        .append(
            new KeyedCodec<>("PlowSpeed", Codec.FLOAT),
            (a, v) -> a.plowSpeed = v,
            a -> a.plowSpeed
        ).add()
        .append(
            new KeyedCodec<>("PlowSeconds", Codec.FLOAT),
            (a, v) -> a.plowSeconds = v,
            a -> a.plowSeconds
        ).add()
        .append(
            new KeyedCodec<>("PlowDamage", Codec.FLOAT),
            (a, v) -> a.plowDamage = v,
            a -> a.plowDamage
        ).add()
        .append(
            new KeyedCodec<>("PlowRadius", Codec.FLOAT),
            (a, v) -> a.plowRadius = v,
            a -> a.plowRadius
        ).add()
        .append(
            new KeyedCodec<>("PlowRiderKnockback", Codec.FLOAT),
            (a, v) -> a.plowRiderKnockback = v,
            a -> a.plowRiderKnockback
        ).add()
        .append(
            new KeyedCodec<>("PlowBeachedSeconds", Codec.FLOAT),
            (a, v) -> a.plowBeachedSeconds = v,
            a -> a.plowBeachedSeconds
        ).add()
        .append(
            new KeyedCodec<>("StompChance", Codec.FLOAT),
            (a, v) -> a.stompChance = v,
            a -> a.stompChance
        ).add()
        .append(
            new KeyedCodec<>("StompRadius", Codec.FLOAT),
            (a, v) -> a.stompRadius = v,
            a -> a.stompRadius
        ).add()
        .append(
            new KeyedCodec<>("StompDamage", Codec.FLOAT),
            (a, v) -> a.stompDamage = v,
            a -> a.stompDamage
        ).add()
        .append(
            new KeyedCodec<>("StompKnockback", Codec.FLOAT),
            (a, v) -> a.stompKnockback = v,
            a -> a.stompKnockback
        ).add()
        .append(
            new KeyedCodec<>("StompLift", Codec.FLOAT),
            (a, v) -> a.stompLift = v,
            a -> a.stompLift
        ).add()
        .append(
            new KeyedCodec<>("StompWindupSeconds", Codec.FLOAT),
            (a, v) -> a.stompWindupSeconds = v,
            a -> a.stompWindupSeconds
        ).add()
        .append(
            new KeyedCodec<>("StompSeconds", Codec.FLOAT),
            (a, v) -> a.stompSeconds = v,
            a -> a.stompSeconds
        ).add()
        .append(
            new KeyedCodec<>("StompRecoverSeconds", Codec.FLOAT),
            (a, v) -> a.stompRecoverSeconds = v,
            a -> a.stompRecoverSeconds
        ).add()
        .append(
            new KeyedCodec<>("StompSound", Codec.STRING),
            (a, v) -> a.stompSound = v,
            a -> a.stompSound
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
        .append(
            new KeyedCodec<>("StepSound", Codec.STRING),
            (a, v) -> a.stepSound = v,
            a -> a.stepSound
        ).add()
        .append(
            new KeyedCodec<>("CrouchSound", Codec.STRING),
            (a, v) -> a.crouchSound = v,
            a -> a.crouchSound
        ).add()
        .append(
            new KeyedCodec<>("SpawnSound", Codec.STRING),
            (a, v) -> a.spawnSound = v,
            a -> a.spawnSound
        ).add()
        .append(
            new KeyedCodec<>("LeapSound", Codec.STRING),
            (a, v) -> a.leapSound = v,
            a -> a.leapSound
        ).add()
        .append(
            new KeyedCodec<>("TelegraphRingParticle", Codec.STRING),
            (a, v) -> a.telegraphRingParticle = v,
            a -> a.telegraphRingParticle
        ).add()
        .append(
            new KeyedCodec<>("TelegraphFillParticle", Codec.STRING),
            (a, v) -> a.telegraphFillParticle = v,
            a -> a.telegraphFillParticle
        ).add()
        .append(
            new KeyedCodec<>("TelegraphLineParticle", Codec.STRING),
            (a, v) -> a.telegraphLineParticle = v,
            a -> a.telegraphLineParticle
        ).add()
        .append(
            new KeyedCodec<>("TelegraphCrackParticle", Codec.STRING),
            (a, v) -> a.telegraphCrackParticle = v,
            a -> a.telegraphCrackParticle
        ).add()
        .append(
            new KeyedCodec<>("TelegraphSound", Codec.STRING),
            (a, v) -> a.telegraphSound = v,
            a -> a.telegraphSound
        ).add()
        .append(
            new KeyedCodec<>("PoundSound", Codec.STRING),
            (a, v) -> a.poundSound = v,
            a -> a.poundSound
        ).add()
        .append(
            new KeyedCodec<>("HurlRipSound", Codec.STRING),
            (a, v) -> a.hurlRipSound = v,
            a -> a.hurlRipSound
        ).add()
        .append(
            new KeyedCodec<>("HurlThrowSound", Codec.STRING),
            (a, v) -> a.hurlThrowSound = v,
            a -> a.hurlThrowSound
        ).add()
        .append(
            new KeyedCodec<>("PlowSound", Codec.STRING),
            (a, v) -> a.plowSound = v,
            a -> a.plowSound
        ).add()
        .append(
            new KeyedCodec<>("BattleMusic", Codec.STRING),
            (a, v) -> a.battleMusic = v,
            a -> a.battleMusic
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
    private int weakpointsToKill;
    private float weakpointEmbed = 0.25f;
    private float weakpointHealth = 100f;
    private float shellHealth;
    private int spawnFootprintRadius;
    private int spawnFootprintRelief;
    private int spawnHeadroom;
    private boolean spawnLevelToLowest;
    @Nullable
    private String groundPrefab;
    private float moveSpeed = 1.6f;
    private float turnSpeed = 0.9f;
    private float wakeRadius = 14f;
    private float loseTargetRadius = 48f;
    private float leashRadius = 40f;
    private boolean startAwake;
    private boolean passive;
    private boolean pet;
    private float spawnFxRadius;
    private float spawnFxDuration = 1.2f;
    private float spawnFxStagger = 0.5f;
    @Nonnull
    private TitanFixtureDef[] fixtures = new TitanFixtureDef[0];
    private double followDistance = 8.0;
    private double crouchDepth = 2.0;
    private double restSink;
    private double aboardRadius;
    private double wandSpeed = 3.0;
    private float wandTurnSpeed = 2.0f;
    private double leapHeight;
    private double leapSpeed = 6.0;
    private boolean chase = true;
    private float wanderRadius;
    private float wanderPauseMin = 6f;
    private float wanderPauseMax = 18f;
    @Nullable
    private String[] environments;
    private float attackRange = 9f;
    private float attackDamage = 18f;
    private float attackRadius = 5f;
    private float attackKnockback = 14f;
    private float attackCooldown = 4.5f;
    private float stunSeconds = 3.5f;
    private float smashChance = 1f;
    private float slamChance = 0.35f;
    private float slamRadius = 6f;
    private float slamProneSeconds = 6f;
    private float poundChance = 0.3f;
    private float poundRadius = 7f;
    private float poundDamage = 12f;
    private float poundLaunch = 22f;
    private float poundStunSeconds = 4.5f;
    private float hurlChance = 0.75f;
    private float hurlMinRange = 12f;
    private float hurlMaxRange = 42f;
    private float hurlSpeed = 33f;
    private float hurlDamage = 22f;
    private float hurlRadius = 4f;
    private float hurlKnockback = 18f;
    @Nullable
    private String hurlPrefab;
    private float plowChance = 0.4f;
    private float plowCooldown = 18f;
    private float plowSpeed = 7f;
    private float plowSeconds = 2.2f;
    private float plowDamage = 16f;
    private float plowRadius = 4.5f;
    private float plowRiderKnockback = 26f;
    private float plowBeachedSeconds = 5f;
    private float stompChance;
    private float stompRadius = 8f;
    private float stompDamage = 30f;
    private float stompKnockback = 16f;
    private float stompLift = 0.6f;
    private float stompWindupSeconds = 1.8f;
    private float stompSeconds = 0.7f;
    private float stompRecoverSeconds = 1.6f;
    @Nullable
    private String stompSound;
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
    @Nullable
    private String stepSound;
    @Nullable
    private String crouchSound;
    @Nullable
    private String spawnSound;
    @Nullable
    private String leapSound;
    @Nullable
    private String telegraphRingParticle = "Titan_Telegraph_Ring";
    @Nullable
    private String telegraphFillParticle = "Titan_Telegraph_Fill";
    @Nullable
    private String telegraphLineParticle = "Titan_Telegraph_Line";
    @Nullable
    private String telegraphCrackParticle = "Titan_Telegraph_Crack";
    @Nullable
    private String telegraphSound;
    @Nullable
    private String poundSound;
    @Nullable
    private String hurlRipSound;
    @Nullable
    private String hurlThrowSound;
    @Nullable
    private String plowSound;
    @Nullable
    private String battleMusic = "Track_Z1D_Goblin_Boss_Battle";

    @Nonnull
    @Override
    public String getId() {
        return id;
    }

    /** {@code TitanSkeletonAsset} id this variant is built from. Required. */
    @Nonnull
    public String getSkeleton() {
        return skeleton;
    }

    /** Name shown for this titan, falling back to {@link #getId()} when unset. */
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
     * <p>Unset uses the skeleton's own prefabs as authored, the plain stone look. A missing suffixed prefab
     * falls back to the unsuffixed one, so a rock type need only ship the parts it changes.
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

    /** Multiplies the size of each ore node's model. */
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
     * How many nodes must be broken to kill this titan, or {@code 0} for all of them.
     *
     * <p>Setting this below the node count leaves spares, so a node on a limb that never faces the player
     * cannot stall the fight. The boss bar counts only the nodes still needed.
     */
    public int getWeakpointsToKill() {
        return weakpointsToKill;
    }

    /**
     * How far past the body surface an ore node's centre is pushed, in model units.
     *
     * <p>Nodes are centred on sockets authored on the surface, so {@code 0} already buries half of one.
     * Positive values sink it further; negative values leave it standing proud of the rock.
     */
    public float getWeakpointEmbed() {
        return weakpointEmbed;
    }

    /** Damage a single ore node absorbs before it breaks, before the server config multiplier. */
    public float getWeakpointHealth() {
        return weakpointHealth;
    }

    /**
     * Damage the whole of a shell absorbs before it breaks open, before the server config multiplier.
     *
     * <p>Only means anything to a skeleton with shell bones, where the creature's own blocks are what gets
     * hit. Unlike ore nodes this is one figure for the lot of them: the shell drains as a single target and
     * no voxel of it comes off on its own, so this is the whole cost of breaking in rather than the cost of
     * one block.
     */
    public float getShellHealth() {
        return shellHealth;
    }

    /**
     * How much ground a natural spawn needs, in blocks either side of the site, or {@code 0} to take the
     * spawner's default.
     *
     * <p>The default is sized for a titan a few blocks across. A wider variant has to say so, or it can be
     * sited with its middle on a knoll and two legs reaching for a ravine.
     */
    public int getSpawnFootprintRadius() {
        return spawnFootprintRadius;
    }

    /** How much the ground may rise and fall across that footprint, in blocks, or {@code 0} for the default. */
    public int getSpawnFootprintRelief() {
        return spawnFootprintRelief;
    }

    /** Clear air needed above the site, in blocks, or {@code 0} for the default. */
    public int getSpawnHeadroom() {
        return spawnHeadroom;
    }

    /**
     * Whether to stand the body over the lowest ground in its footprint instead of the middle of it.
     *
     * <p>A leg reaches only a couple of blocks past its rest length before the foot hangs in the air, but
     * it folds a long way, so levelling to the lowest corner leaves every other foot merely bent. Off by
     * default, since a titan that sits directly on the ground would end up partly buried on a slope.
     */
    public boolean isSpawnLevelToLowest() {
        return spawnLevelToLowest;
    }

    /**
     * Prefab stamped into the world as ordinary blocks under this titan when it is built, or {@code null}
     * for a titan that arrives on bare ground.
     *
     * <p>For the scenery a titan comes with rather than any part of the titan itself: the nest an egg sits
     * in belongs to the clearing, not to the egg, and has to still be there after the egg is gone. Painted
     * as real blocks precisely so it outlives the entity, which means it is also a permanent change to the
     * world that nothing takes back.
     *
     * <p>Laid down centred on the root's block column with its lowest layer at the root's feet, so the
     * prefab is authored around the titan rather than offset to one side of it. A titan rebuilt at the same
     * spot after an unload paints the same blocks into the same cells, so this is idempotent rather than
     * cumulative — at the cost of undoing anything a player changed about it in the meantime.
     */
    @Nullable
    public String getGroundPrefab() {
        return groundPrefab;
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
     * <p>Kept short: titans placed by world generation spend most of their life curled up looking like
     * scenery, and the disguise only holds if a player can walk past one at a distance.
     */
    public float getWakeRadius() {
        return wakeRadius;
    }

    /** How far a target may get before the titan loses interest, in blocks. Also its search radius awake. */
    public float getLoseTargetRadius() {
        return loseTargetRadius;
    }

    /**
     * How far from its spawn spot a titan will follow anyone, in blocks, or {@code 0} for no limit.
     * Measured from where it was built rather than where it stands, so no chase can walk it away one step
     * at a time; a target outside the circle is dropped and the titan heads back.
     */
    public float getLeashRadius() {
        return leashRadius;
    }

    /**
     * Whether the titan is on its feet the moment it is built, instead of curled up waiting to be walked
     * near. Off by default, so a titan small enough to pass for a boulder keeps that disguise.
     */
    public boolean isStartAwake() {
        return startAwake;
    }

    /**
     * Whether the titan ignores anyone who has not hit it yet. Proximity alone will not give it a target;
     * damage to a weakpoint provokes it, and it loses interest again once that wears off.
     */
    public boolean isPassive() {
        return passive;
    }

    /**
     * Whether this titan belongs to a player rather than fighting them.
     *
     * <p>A pet takes no part in the combat pipeline: it gets no boss bar, no battle music, no Encounter and
     * no brain NPC, and the combat state machine skips it entirely in favour of whatever system owns its
     * behaviour. Everything below the neck is shared — the same pose, IK, gait and voxel sync.
     */
    public boolean isPet() {
        return pet;
    }

    /**
     * How far out the voxels of a spawn effect start from, in blocks, or {@code 0} for no effect.
     *
     * <p>The titan assembles itself out of blocks that fly in from a shell of this radius. Sized to the
     * creature: too small and the blocks are already inside the silhouette when they appear, too large and
     * they arrive from outside the player's view.
     */
    public float getSpawnFxRadius() {
        return spawnFxRadius;
    }

    /** Seconds one voxel takes to travel in from its starting point. */
    public float getSpawnFxDuration() {
        return spawnFxDuration;
    }

    /**
     * Extra seconds spread across the body, so the voxels nearest the middle arrive first and the build
     * reads outwards from the core instead of every block landing at once.
     */
    public float getSpawnFxStagger() {
        return spawnFxStagger;
    }

    /** Blocks of this titan's geometry that a player can use. Empty for a titan with none. */
    @Nonnull
    public TitanFixtureDef[] getFixtures() {
        return fixtures;
    }

    /**
     * How close a pet follows its owner, in blocks.
     *
     * <p>Wants to be wider than the titan itself. Anything less and it walks into the player, and because
     * it is far heavier than one it would push them around rather than stop.
     */
    public double getFollowDistance() {
        return followDistance;
    }

    /**
     * How far the hips drop when a pet is told to rest, in blocks.
     *
     * <p>Applied to the root, which sits at the feet plane. The IK holds the feet where they were planted,
     * so pulling the root down folds the legs under the body instead of sinking it through the ground, and
     * the depth is really a statement about how far the legs can fold before the body meets them.
     */
    public double getCrouchDepth() {
        return crouchDepth;
    }

    /**
     * How much further a resting pet settles into the ground, in blocks, once its legs are folded as far
     * as they go.
     *
     * <p>Legs stop being the answer well before the floor of a house on legs is somewhere a player can
     * step onto: fold an eleven-block leg completely and the floor above it is still four blocks up, which
     * is a wall rather than a step. Past that point the whole creature goes down instead, feet and all, so
     * the mound it stands on beds into the earth and the floor comes level with it. Nothing is buried that
     * a player wants to see — the legs and the underside of the mound — and the shape stays right because
     * hips and feet descend together.
     *
     * <p>Zero for a pet whose legs fold far enough on their own, and for every titan that never rests.
     */
    public double getRestSink() {
        return restSink;
    }

    /**
     * How far out from the root a player counts as standing on this titan, in blocks.
     *
     * <p>A pet carrying its owner must not also chase them. Following is a heading and a speed worked out
     * from where the owner is, and an owner who is standing on the thing doing the working out moves with
     * every correction it makes: the house turns towards them, which carries them round with it, which
     * leaves them still off to the same side. It spins on the spot for as long as they stand there.
     *
     * <p>Wants to be about half the width of the titan. Zero means it is never carrying anybody, which is
     * true of everything but a house.
     */
    public double getAboardRadius() {
        return aboardRadius;
    }

    /**
     * How fast a titan walks while it is being pointed somewhere with the wand, in blocks per second.
     *
     * <p>Kept apart from {@code MoveSpeed}, which is the amble of a pet catching up with an owner who is
     * probably standing still by now. A house that has been told to go somewhere is going somewhere, and
     * the player is usually watching it cross ground they are not crossing with it, so it can afford to be
     * quicker than it is when it is trailing them about.
     */
    public double getWandSpeed() {
        return wandSpeed;
    }

    /**
     * How fast a titan turns onto the heading the wand is pointing, in radians per second.
     *
     * <p>Faster than {@code TurnSpeed} for the same reason as above: a pet noticing where its owner has got
     * to can take its time, but every fraction of a second between swinging the wand and the house coming
     * round is felt as the wand being unresponsive.
     */
    public float getWandTurnSpeed() {
        return wandTurnSpeed;
    }

    /**
     * How high a titan leaps when the wand tells it to, in blocks, before scale.
     *
     * <p>The apex of the arc rather than a launch speed, so what is written here is what a player sees.
     * Zero for anything that does not leap, which is every titan that is not a Baba Yaga house.
     */
    public double getLeapHeight() {
        return leapHeight;
    }

    /**
     * How fast a titan travels forward through a leap, in blocks per second.
     *
     * <p>Held for the whole arc, so the distance covered is this multiplied by the time in the air, which
     * is set by {@link #getLeapHeight()}. Wants to be quicker than the walk — a leap that covers no more
     * ground than walking would have is a hop on the spot — without outrunning the gait, whose legs are
     * still stepping while the house is airborne.
     */
    public double getLeapSpeed() {
        return leapSpeed;
    }

    /** @return the fixture declared for {@code blockKey}, or {@code null} if that block is ordinary geometry. */
    @Nullable
    public TitanFixtureDef findFixture(@Nonnull final String blockKey) {
        for (final TitanFixtureDef fixture : fixtures) {
            if (blockKey.equals(fixture.getBlock())) return fixture;
        }
        return null;
    }

    /**
     * The first fixture of {@code kind}, or {@code null} if this titan has none.
     *
     * <p>For the systems that drive a particular fixture rather than answer a click on one: a smelter has
     * to find its own container every tick without being told which block it came from.
     */
    @Nullable
    public TitanFixtureDef findFixture(@Nonnull final TitanFixtureDef.Kind kind) {
        for (final TitanFixtureDef fixture : fixtures) {
            if (fixture.getKind() == kind) return fixture;
        }
        return null;
    }

    /** Whether the titan closes the distance to its target; with this off it only answers what comes to it. */
    public boolean isChase() {
        return chase;
    }

    /** How far the titan drifts from its spawn spot while idle, in blocks. {@code 0} leaves it where built. */
    public float getWanderRadius() {
        return wanderRadius;
    }

    /** Shortest pause between wander legs, in seconds. */
    public float getWanderPauseMin() {
        return wanderPauseMin;
    }

    /** Longest pause between wander legs, in seconds. */
    public float getWanderPauseMax() {
        return wanderPauseMax;
    }

    /**
     * Environments the titan will not wander out of. Empty lets it go anywhere its leash reaches. Checked
     * against the ground at the far end of a wander leg before it sets off, so it turns back at the border.
     */
    @Nullable
    public String[] getEnvironments() {
        return environments;
    }

    /** How close the titan closes before it stops and attacks, in blocks. */
    public float getAttackRange() {
        return attackRange;
    }

    /** Damage an arm smash or body slam deals at the impact point. */
    public float getAttackDamage() {
        return attackDamage;
    }

    /** Blast radius of an arm smash, in blocks. */
    public float getAttackRadius() {
        return attackRadius;
    }

    /** How hard a smash throws whoever it catches; the plough sweep uses it too. */
    public float getAttackKnockback() {
        return attackKnockback;
    }

    /** Seconds between attacks, timed from the end of the previous one. */
    public float getAttackCooldown() {
        return attackCooldown;
    }

    /** Seconds the hand stays embedded after a smash, the window in which the arm is climbable. */
    public float getStunSeconds() {
        return stunSeconds;
    }

    /**
     * Relative weight of the plain arm smash, which every other melee chance is measured against. Left at
     * {@code 1} it is the fallback the other rolls fall through to; {@code 0} on a titan with no arms.
     */
    public float getSmashChance() {
        return smashChance;
    }

    /** Odds of answering an attack opportunity with a body slam instead of an arm smash, {@code 0} to {@code 1}. */
    public float getSlamChance() {
        return slamChance;
    }

    /** Blast radius of a body slam, in blocks. Wider than the arm smash, since the whole creature lands. */
    public float getSlamRadius() {
        return slamRadius;
    }

    /** Seconds the titan lies face down after a slam, with its back within jumping range. */
    public float getSlamProneSeconds() {
        return slamProneSeconds;
    }

    /** Relative weight of the ground pound among the melee answers, against {@link #getSlamChance()}. */
    public float getPoundChance() {
        return poundChance;
    }

    /** Blast radius of a ground pound, in blocks. The widest melee attack: both fists land at once. */
    public float getPoundRadius() {
        return poundRadius;
    }

    /** Damage a pound deals directly; low, since most of its threat is the {@link #getPoundLaunch()} fall. */
    public float getPoundDamage() {
        return poundDamage;
    }

    /** How hard a pound throws whoever it catches, almost all of it straight up. */
    public float getPoundLaunch() {
        return poundLaunch;
    }

    /** Seconds both fists stay embedded after a pound, the longest window in which the arms are climbable. */
    public float getPoundStunSeconds() {
        return poundStunSeconds;
    }

    /** Odds of answering an out-of-reach target with a thrown boulder rather than closing the distance. */
    public float getHurlChance() {
        return hurlChance;
    }

    /** How far away a target has to be before the titan will throw at it, in blocks. Outside melee range. */
    public float getHurlMinRange() {
        return hurlMinRange;
    }

    /**
     * How far the titan will throw, in blocks. Beyond this it walks instead.
     *
     * <p>Must stay inside what {@link #getHurlSpeed()} can reach, which is {@code speed²/20}. Set beyond
     * that, the throw silently falls back to forty-five degrees and lands short every time.
     */
    public float getHurlMaxRange() {
        return hurlMaxRange;
    }

    /** Launch speed of a thrown boulder, in blocks per second. Sets how flat the arc is. */
    public float getHurlSpeed() {
        return hurlSpeed;
    }

    /** Damage a thrown boulder deals where it lands. */
    public float getHurlDamage() {
        return hurlDamage;
    }

    /** Blast radius where a thrown boulder lands, in blocks. */
    public float getHurlRadius() {
        return hurlRadius;
    }

    /** How hard a landing boulder throws whoever it catches. */
    public float getHurlKnockback() {
        return hurlKnockback;
    }

    /** Prefab the thrown boulder is built from. Unset uses the skeleton's own hand prefab. */
    @Nullable
    public String getHurlPrefab() {
        return hurlPrefab;
    }

    /** Odds of answering a rider on its back with a plough, rolled whenever the titan is ready to attack. */
    public float getPlowChance() {
        return plowChance;
    }

    /**
     * Seconds before the titan will plough again, on top of {@link #getAttackCooldown()}. Needed because a
     * titan being climbed counts as one being hit, which keeps that cooldown short.
     */
    public float getPlowCooldown() {
        return plowCooldown;
    }

    /** Blocks per second while ploughing, several times walking speed. */
    public float getPlowSpeed() {
        return plowSpeed;
    }

    /** How long a plough charge runs, in seconds. */
    public float getPlowSeconds() {
        return plowSeconds;
    }

    /** Damage each sweep of a plough deals. */
    public float getPlowDamage() {
        return plowDamage;
    }

    /** Half-width of the corridor a plough shovels, in blocks. */
    public float getPlowRadius() {
        return plowRadius;
    }

    /** How hard a plough throws whoever was riding the back. Much harder than anything else the titan does. */
    public float getPlowRiderKnockback() {
        return plowRiderKnockback;
    }

    /** Seconds the titan lies beached at the end of a plough, its longest opening. */
    public float getPlowBeachedSeconds() {
        return plowBeachedSeconds;
    }

    /** Relative weight of the leg stomp among the melee answers, against {@link #getSmashChance()}. */
    public float getStompChance() {
        return stompChance;
    }

    /** Blast radius of a leg stomp, in blocks. */
    public float getStompRadius() {
        return stompRadius;
    }

    /** Damage a stomp deals where the foot lands. */
    public float getStompDamage() {
        return stompDamage;
    }

    /** How hard a stomp throws whoever it catches. */
    public float getStompKnockback() {
        return stompKnockback;
    }

    /** How far the foot is hauled up during the windup, as a fraction of the titan's hip height. */
    public float getStompLift() {
        return stompLift;
    }

    /** Seconds the leg spends in the air with the ground marked before it comes down. */
    public float getStompWindupSeconds() {
        return stompWindupSeconds;
    }

    /** Seconds the leg takes to travel down; the blow lands at the end of it. */
    public float getStompSeconds() {
        return stompSeconds;
    }

    /** Seconds the leg stays planted afterwards before the gait picks it back up. */
    public float getStompRecoverSeconds() {
        return stompRecoverSeconds;
    }

    /** Sound played when a stomp lands. Falls back to {@link #getImpactSound()}. */
    @Nullable
    public String getStompSound() {
        return stompSound;
    }

    /** Item drop table rolled when the titan dies. */
    @Nullable
    public String getDropList() {
        return dropList;
    }

    /** Fallback item id used when {@link #getDropList()} is unset or resolves to nothing. */
    @Nullable
    public String getDropItem() {
        return dropItem;
    }

    /** Fewest {@link #getDropItem()} a kill may drop. */
    public int getDropCountMin() {
        return dropCountMin;
    }

    /** Most {@link #getDropItem()} a kill may drop; the count is rolled between the two. */
    public int getDropCountMax() {
        return dropCountMax;
    }

    /** Particle effect spawned where a blow lands, and where a broken ore node was. */
    @Nullable
    public String getImpactParticle() {
        return impactParticle;
    }

    /** Sound played where a blow lands, and the fallback for the pound and stomp sounds. */
    @Nullable
    public String getImpactSound() {
        return impactSound;
    }

    /** Sound played as the titan starts to wake. */
    @Nullable
    public String getWakeSound() {
        return wakeSound;
    }

    /** Sound played when the titan dies. */
    @Nullable
    public String getDeathSound() {
        return deathSound;
    }

    /** Sound played when a foot plants after a step. Unused by combat titans. */
    @Nullable
    public String getStepSound() {
        return stepSound;
    }

    /** Sound played when a pet kneels or stands back up. */
    @Nullable
    public String getCrouchSound() {
        return crouchSound;
    }

    /** Sound played once as the spawn fly-in starts. */
    @Nullable
    public String getSpawnSound() {
        return spawnSound;
    }

    /** Sound played as a titan leaves the ground on a leap. The landing uses {@code ImpactSound}. */
    @Nullable
    public String getLeapSound() {
        return leapSound;
    }

    /**
     * Flat ring laid on the ground to show where an attack is about to land.
     *
     * <p>The engine has no notion of an attack indicator, so this is a particle system shipped with the
     * mod: one ring-shaped quad lying face up, sized in blocks at spawn time so one asset marks any attack.
     */
    @Nullable
    public String getTelegraphRingParticle() {
        return telegraphRingParticle;
    }

    /** Disc that fills the ring in as the windup runs out, so the last moment before impact reads clearly. */
    @Nullable
    public String getTelegraphFillParticle() {
        return telegraphFillParticle;
    }

    /** Narrow ring that lays out the corridor a plough is about to come down. */
    @Nullable
    public String getTelegraphLineParticle() {
        return telegraphLineParticle;
    }

    /** Split ground where a boulder is being torn out. */
    @Nullable
    public String getTelegraphCrackParticle() {
        return telegraphCrackParticle;
    }

    /** Played once when a telegraph first appears, so an attack aimed from behind is still noticed. */
    @Nullable
    public String getTelegraphSound() {
        return telegraphSound;
    }

    /** Sound played when a pound lands. Falls back to {@link #getImpactSound()}. */
    @Nullable
    public String getPoundSound() {
        return poundSound;
    }

    /** Played as the hand closes on the boulder and drags it out of the ground. */
    @Nullable
    public String getHurlRipSound() {
        return hurlRipSound;
    }

    /** Played as the boulder leaves the hand. */
    @Nullable
    public String getHurlThrowSound() {
        return hurlThrowSound;
    }

    /** Played as a plough charge sets off, once the windup is over. */
    @Nullable
    public String getPlowSound() {
        return plowSound;
    }

    /**
     * {@code MusicContainer} forced on players while they are engaged with this titan, replacing whatever
     * the zone would otherwise be playing. Cleared again when they walk away or it dies.
     */
    @Nullable
    public String getBattleMusic() {
        return battleMusic;
    }

    /** The variant with this id, or {@code null} if there is none. */
    @Nullable
    public static TitanVariantAsset find(@Nullable final String id) {
        return id == null ? null : ASSET_MAP.getAsset(id);
    }
}
