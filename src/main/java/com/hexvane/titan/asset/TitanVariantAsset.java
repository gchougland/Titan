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
    private int spawnFootprintRadius;
    private int spawnFootprintRelief;
    private int spawnHeadroom;
    private float moveSpeed = 1.6f;
    private float turnSpeed = 0.9f;
    private float wakeRadius = 14f;
    private float loseTargetRadius = 48f;
    private float leashRadius = 40f;
    private boolean startAwake;
    private boolean passive;
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
     * How many nodes have to be broken to kill this titan, or {@code 0} for all of them.
     *
     * <p>Set below the node count to leave spares. The fight stops being a checklist of every crystal on
     * the creature and becomes a matter of picking off enough of them, so a node on a leg that never turns
     * towards the player is an inconvenience rather than a wall. The boss bar follows the same rule and
     * measures only the nodes still needed, never the spares.
     */
    public int getWeakpointsToKill() {
        return weakpointsToKill;
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

    /**
     * How much ground a natural spawn needs, in blocks either side of the site, or {@code 0} to take the
     * spawner's default.
     *
     * <p>The default is sized for a titan a few blocks across, which is most of them. Something the size of
     * a walking island is not: checked over four blocks it will happily be sited with its middle on a knoll
     * and two legs reaching for a ravine, because nothing ever looked as far out as its legs go. A variant
     * that wide has to say so, and pay for it by being much fussier about where it will stand.
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

    /**
     * How far from its spawn spot a titan will follow anyone, in blocks. Measured from where it was built,
     * not from where it currently is, so no chase can walk it away one step at a time. Step outside the
     * circle and it stops caring and heads back. {@code 0} lets it roam without limit.
     */
    public float getLeashRadius() {
        return leashRadius;
    }

    /**
     * Whether the titan is on its feet the moment it is built, rather than curled up waiting to be walked
     * near. A talus is a boulder until it is not, and the surprise is the point; something the size of a
     * hill that is visibly walking around cannot pretend to be scenery, so it skips the trick.
     */
    public boolean isStartAwake() {
        return startAwake;
    }

    /**
     * Whether the titan ignores anyone who has not hit it yet.
     *
     * <p>Proximity alone will not give it a target, so it can be walked under and stood on indefinitely.
     * Damage to a weakpoint provokes it, and it stays angry for as long as that lasts before losing
     * interest again.
     */
    public boolean isPassive() {
        return passive;
    }

    /**
     * Whether the titan closes the distance to its target. With this off it holds its ground and only
     * answers whoever comes within reach, which is what separates something defending itself from
     * something hunting.
     */
    public boolean isChase() {
        return chase;
    }

    /**
     * How far from its spawn spot the titan drifts while it has nothing to fight, in blocks. {@code 0}
     * leaves it standing where it was built, which is what every talus does.
     */
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
     * Environments the titan will not wander out of. Empty lets it go anywhere its leash reaches.
     *
     * <p>Checked against the ground at the far end of a wander leg before it sets off, so it turns back at
     * the treeline rather than walking out of its biome and having to be dragged home.
     */
    @Nullable
    public String[] getEnvironments() {
        return environments;
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

    /**
     * Relative weight of the plain arm smash, which every other melee chance is measured against.
     *
     * <p>Left at 1 this is the fallback the other rolls fall through to. Set to 0 on a titan with no arms,
     * where the roll has to land on something it can actually perform.
     */
    public float getSmashChance() {
        return smashChance;
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

    /** Relative weight of the ground pound among the melee answers, against {@link #getSlamChance()}. */
    public float getPoundChance() {
        return poundChance;
    }

    /**
     * Blast radius of a ground pound. The widest of the three melee attacks: both fists land at once and
     * the shock goes out from between them, so getting out of the circle is the only answer to it.
     */
    public float getPoundRadius() {
        return poundRadius;
    }

    /**
     * Damage a pound deals directly. Low next to the other attacks on purpose — the pound is not meant to
     * kill you, it is meant to put you somewhere high up and let the fall do it.
     */
    public float getPoundDamage() {
        return poundDamage;
    }

    /** How hard a pound throws you, almost all of it straight up. */
    public float getPoundLaunch() {
        return poundLaunch;
    }

    /** How long both fists stay embedded after a pound — the widest climbing window in the fight. */
    public float getPoundStunSeconds() {
        return poundStunSeconds;
    }

    /** Odds of answering an out-of-reach target with a thrown boulder rather than closing the distance. */
    public float getHurlChance() {
        return hurlChance;
    }

    /**
     * How far away a target has to be before the titan will throw at it, in blocks.
     *
     * <p>Comfortably outside melee range. Inside it the titan has better answers, and a boulder lobbed at
     * something standing between its feet would be thrown almost straight down.
     */
    public float getHurlMinRange() {
        return hurlMinRange;
    }

    /**
     * How far the titan will throw, in blocks. Beyond this it walks instead.
     *
     * <p>Must be kept inside what {@link #getHurlSpeed()} can actually reach, which is {@code speed²/20}.
     * Set beyond it and the throw silently falls back to forty-five degrees and lands short every time.
     */
    public float getHurlMaxRange() {
        return hurlMaxRange;
    }

    /** Launch speed of a thrown boulder, in blocks per second. Sets how flat the arc is. */
    public float getHurlSpeed() {
        return hurlSpeed;
    }

    public float getHurlDamage() {
        return hurlDamage;
    }

    /** Blast radius where a thrown boulder lands. */
    public float getHurlRadius() {
        return hurlRadius;
    }

    public float getHurlKnockback() {
        return hurlKnockback;
    }

    /**
     * Prefab the thrown boulder is built from. Defaults to the skeleton's own hand, which is exactly the
     * lump of rock the titan just tore out of the ground.
     */
    @Nullable
    public String getHurlPrefab() {
        return hurlPrefab;
    }

    /**
     * Odds of answering a player riding the back with a plough, checked each time the titan is ready to
     * attack. Well under one, so climbing on is not immediately punished every time.
     */
    public float getPlowChance() {
        return plowChance;
    }

    /**
     * Seconds before the titan will plough again, on top of its ordinary attack cooldown.
     *
     * <p>Its own clock, because the plough is the answer to being climbed and a titan being climbed is
     * being hit, which keeps the ordinary cooldown short. Without this a good climber would be ploughed off
     * over and over and never reach the ore.
     */
    public float getPlowCooldown() {
        return plowCooldown;
    }

    /** Blocks per second while ploughing. Several times walking speed: this is a charge. */
    public float getPlowSpeed() {
        return plowSpeed;
    }

    public float getPlowSeconds() {
        return plowSeconds;
    }

    public float getPlowDamage() {
        return plowDamage;
    }

    /** Half-width of the corridor a plough shovels, in blocks. */
    public float getPlowRadius() {
        return plowRadius;
    }

    /**
     * How hard a plough throws whoever was riding the back.
     *
     * <p>Much harder than anything else the titan does. It has to be: the whole point of the move is to get
     * a player off, and a rider who lands back on the slab has not been removed from anything.
     */
    public float getPlowRiderKnockback() {
        return plowRiderKnockback;
    }

    /** How long the titan lies beached at the end of a plough. Its longest opening. */
    public float getPlowBeachedSeconds() {
        return plowBeachedSeconds;
    }

    /**
     * Relative weight of the leg stomp among the melee answers, against {@link #getSmashChance()}.
     *
     * <p>The attack of something with legs and no arms: it picks up whichever one is nearest whoever hit
     * it and puts it back down on them.
     */
    public float getStompChance() {
        return stompChance;
    }

    /** Blast radius of a leg stomp, in blocks. */
    public float getStompRadius() {
        return stompRadius;
    }

    public float getStompDamage() {
        return stompDamage;
    }

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

    /** Seconds the leg takes to travel down. The blow lands at the end of it. */
    public float getStompSeconds() {
        return stompSeconds;
    }

    /** Seconds the leg stays planted afterwards before the gait picks it back up. */
    public float getStompRecoverSeconds() {
        return stompRecoverSeconds;
    }

    @Nullable
    public String getStompSound() {
        return stompSound;
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

    /**
     * Flat ring laid on the ground to show where an attack is about to land.
     *
     * <p>The engine has no notion of an attack indicator, so this is a particle system shipped with the mod
     * that happens to be one ring-shaped quad lying face up. Sized in blocks at spawn time, which is what
     * lets the same asset mark a fist-sized smash and a pound that covers the whole clearing.
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

    /** Narrow ring used to lay out the corridor a plough is about to come down. */
    @Nullable
    public String getTelegraphLineParticle() {
        return telegraphLineParticle;
    }

    /** Split ground where a boulder is being torn out. */
    @Nullable
    public String getTelegraphCrackParticle() {
        return telegraphCrackParticle;
    }

    /** Played once when a telegraph first appears, so an attack aimed behind you is still noticed. */
    @Nullable
    public String getTelegraphSound() {
        return telegraphSound;
    }

    @Nullable
    public String getPoundSound() {
        return poundSound;
    }

    /** Played as the hand closes on the boulder and drags it out of the ground. */
    @Nullable
    public String getHurlRipSound() {
        return hurlRipSound;
    }

    @Nullable
    public String getHurlThrowSound() {
        return hurlThrowSound;
    }

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

    @Nullable
    public static TitanVariantAsset find(@Nullable final String id) {
        return id == null ? null : ASSET_MAP.getAsset(id);
    }
}
