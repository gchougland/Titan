package com.hexvane.titan.dunewyrm;

import com.hexvane.titan.TitanRegistry;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Invisible root of one Dunewyrm snake instance.
 *
 * <p>Owns the ordered segment chain, path buffer, combat state, and encounter identity shared with any
 * snakes created by splitting.
 */
public final class DunewyrmComponent implements Component<EntityStore> {

    @Nonnull
    public static ComponentType<EntityStore, DunewyrmComponent> getComponentType() {
        return TitanRegistry.getDunewyrmComponentType();
    }

    @Nullable
    private TitanVariantAsset variant;
    @Nonnull
    private UUID encounterId = UUID.randomUUID();
    /** True for the first snake of an encounter; only that one owns tunnel-phase flags historically — all
     * share encounterId and the encounter registry tracks phases. */
    private boolean primary = true;

    @Nonnull
    private DunewyrmState state = DunewyrmState.IDLE;
    @Nonnull
    private final List<DunewyrmSegment> segments = new ArrayList<>();
    @Nonnull
    private final DunewyrmPathBuffer path = new DunewyrmPathBuffer(DunewyrmTuning.PATH_CAPACITY);
    @Nonnull
    private final Vector3d home = new Vector3d();
    @Nonnull
    private final Vector3d headPosition = new Vector3d();

    private float yaw;
    private float chargeYaw;
    private float stateTimer;
    private float attackCooldown;
    private float contactTimer;
    private float tongueTimer = DunewyrmTuning.TONGUE_INTERVAL_MIN;
    private float tongueActive;
    private float jawOpen;
    private float cobraRise;
    private float tunnelDepth;
    private float digParticleTimer;
    private float scorpionBudget;
    private float sinePhase;
    private float orbitAngle;
    private float orbitRadius = DunewyrmTuning.ORBIT_RADIUS;
    private float fleeTimer;
    private float cobraLean;
    private boolean segmentsDirty = true;
    private boolean pendingStructural;
    @Nullable
    private Ref<EntityStore> target;
    @Nullable
    private Vector3d poisonCloud;
    private float poisonTimer;
    private float telegraphTimer;

    /** Players currently shown this snake's boss bar / battle music. */
    @Nonnull
    private final List<Ref<EntityStore>> barViewers = new ArrayList<>();

    /** Encounter-wide remaining HP fraction tracking is on {@link DunewyrmEncounter}. */
    private float initialBodyHealth;

    public DunewyrmComponent() {
    }

    public DunewyrmComponent(@Nonnull final TitanVariantAsset variant) {
        this.variant = variant;
    }

    @Nullable
    public TitanVariantAsset getVariant() {
        return variant;
    }

    public void setVariant(@Nonnull final TitanVariantAsset variant) {
        this.variant = variant;
    }

    @Nonnull
    public UUID getEncounterId() {
        return encounterId;
    }

    public void setEncounterId(@Nonnull final UUID encounterId) {
        this.encounterId = encounterId;
    }

    public boolean isPrimary() {
        return primary;
    }

    public void setPrimary(final boolean primary) {
        this.primary = primary;
    }

    @Nonnull
    public List<Ref<EntityStore>> getBarViewers() {
        return barViewers;
    }

    @Nonnull
    public DunewyrmState getState() {
        return state;
    }

    public void setState(@Nonnull final DunewyrmState state) {
        this.state = state;
        this.stateTimer = 0f;
        this.telegraphTimer = 0f;
    }

    @Nonnull
    public List<DunewyrmSegment> getSegments() {
        return segments;
    }

    @Nonnull
    public DunewyrmPathBuffer getPath() {
        return path;
    }

    @Nonnull
    public Vector3d getHome() {
        return home;
    }

    @Nonnull
    public Vector3d getHeadPosition() {
        return headPosition;
    }

    public float getYaw() {
        return yaw;
    }

    public void setYaw(final float yaw) {
        this.yaw = yaw;
    }

    public float getChargeYaw() {
        return chargeYaw;
    }

    public void setChargeYaw(final float chargeYaw) {
        this.chargeYaw = chargeYaw;
    }

    public float getStateTimer() {
        return stateTimer;
    }

    public void addStateTimer(final float dt) {
        stateTimer += dt;
    }

    public float getAttackCooldown() {
        return attackCooldown;
    }

    public void setAttackCooldown(final float attackCooldown) {
        this.attackCooldown = attackCooldown;
    }

    public void tickAttackCooldown(final float dt) {
        if (attackCooldown > 0f) attackCooldown = Math.max(0f, attackCooldown - dt);
    }

    public float getContactTimer() {
        return contactTimer;
    }

    public void setContactTimer(final float contactTimer) {
        this.contactTimer = contactTimer;
    }

    public float getTongueTimer() {
        return tongueTimer;
    }

    public void setTongueTimer(final float tongueTimer) {
        this.tongueTimer = tongueTimer;
    }

    public float getTongueActive() {
        return tongueActive;
    }

    public void setTongueActive(final float tongueActive) {
        this.tongueActive = tongueActive;
    }

    public float getJawOpen() {
        return jawOpen;
    }

    public void setJawOpen(final float jawOpen) {
        this.jawOpen = jawOpen;
    }

    public float getCobraRise() {
        return cobraRise;
    }

    public void setCobraRise(final float cobraRise) {
        this.cobraRise = cobraRise;
    }

    public float getTunnelDepth() {
        return tunnelDepth;
    }

    public void setTunnelDepth(final float tunnelDepth) {
        this.tunnelDepth = tunnelDepth;
    }

    public float getDigParticleTimer() {
        return digParticleTimer;
    }

    public void setDigParticleTimer(final float digParticleTimer) {
        this.digParticleTimer = digParticleTimer;
    }

    public float getScorpionBudget() {
        return scorpionBudget;
    }

    public void setScorpionBudget(final float scorpionBudget) {
        this.scorpionBudget = scorpionBudget;
    }

    public float getSinePhase() {
        return sinePhase;
    }

    public void addSinePhase(final float dt) {
        sinePhase += dt;
    }

    public float getOrbitAngle() {
        return orbitAngle;
    }

    public void setOrbitAngle(final float orbitAngle) {
        this.orbitAngle = orbitAngle;
    }

    public void addOrbitAngle(final float delta) {
        orbitAngle += delta;
    }

    public float getOrbitRadius() {
        return orbitRadius;
    }

    public void setOrbitRadius(final float orbitRadius) {
        this.orbitRadius = orbitRadius;
    }

    public float getFleeTimer() {
        return fleeTimer;
    }

    public void setFleeTimer(final float fleeTimer) {
        this.fleeTimer = fleeTimer;
    }

    public void tickFleeTimer(final float dt) {
        if (fleeTimer > 0f) fleeTimer = Math.max(0f, fleeTimer - dt);
    }

    public float getCobraLean() {
        return cobraLean;
    }

    public void setCobraLean(final float cobraLean) {
        this.cobraLean = cobraLean;
    }

    /** Clears rearing / spray pose — used after splits so halves don't inherit a mid-air cobra. */
    public void clearCombatPose() {
        cobraRise = 0f;
        cobraLean = 0f;
        jawOpen = 0f;
        tunnelDepth = 0f;
        poisonCloud = null;
        poisonTimer = 0f;
        digParticleTimer = 0f;
    }

    /**
     * Telegraph pulse gate — first call after {@link #setState} returns true immediately.
     *
     * @return {@code true} when the interval has elapsed
     */
    public boolean consumePulse(final float dt, final float interval) {
        telegraphTimer -= dt;
        if (telegraphTimer > 0f) return false;
        telegraphTimer = interval;
        return true;
    }

    public boolean isSegmentsDirty() {
        return segmentsDirty;
    }

    public void setSegmentsDirty(final boolean segmentsDirty) {
        this.segmentsDirty = segmentsDirty;
    }

    public boolean isPendingStructural() {
        return pendingStructural;
    }

    public void setPendingStructural(final boolean pendingStructural) {
        this.pendingStructural = pendingStructural;
    }

    @Nullable
    public Ref<EntityStore> getTarget() {
        return target;
    }

    public void setTarget(@Nullable final Ref<EntityStore> target) {
        this.target = target;
    }

    @Nullable
    public Vector3d getPoisonCloud() {
        return poisonCloud;
    }

    public void setPoisonCloud(@Nullable final Vector3d poisonCloud) {
        this.poisonCloud = poisonCloud;
    }

    public float getPoisonTimer() {
        return poisonTimer;
    }

    public void setPoisonTimer(final float poisonTimer) {
        this.poisonTimer = poisonTimer;
    }

    public float getInitialBodyHealth() {
        return initialBodyHealth;
    }

    public void setInitialBodyHealth(final float initialBodyHealth) {
        this.initialBodyHealth = initialBodyHealth;
    }

    public int bodyCount() {
        int n = 0;
        for (final DunewyrmSegment segment : segments) {
            if (segment.getRole() == DunewyrmSegmentRole.BODY) n++;
        }
        return n;
    }

    public float bodyHealth() {
        float sum = 0f;
        for (final DunewyrmSegment segment : segments) {
            if (segment.getRole() == DunewyrmSegmentRole.BODY) sum += segment.getHealth();
        }
        return sum;
    }

    /** Index of the Nth BODY segment in {@link #segments}, or -1. */
    public int bodyIndex(final int bodyOrdinal) {
        int seen = 0;
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).getRole() != DunewyrmSegmentRole.BODY) continue;
            if (seen == bodyOrdinal) return i;
            seen++;
        }
        return -1;
    }

    /** Body ordinal for a segment list index, or -1 if not a body. */
    public int bodyOrdinal(final int segmentIndex) {
        if (segmentIndex < 0 || segmentIndex >= segments.size()) return -1;
        if (segments.get(segmentIndex).getRole() != DunewyrmSegmentRole.BODY) return -1;
        int seen = 0;
        for (int i = 0; i <= segmentIndex; i++) {
            if (segments.get(i).getRole() == DunewyrmSegmentRole.BODY) {
                if (i == segmentIndex) return seen;
                seen++;
            }
        }
        return -1;
    }

    @Nullable
    public DunewyrmSegment findRole(@Nonnull final DunewyrmSegmentRole role) {
        for (final DunewyrmSegment segment : segments) {
            if (segment.getRole() == role) return segment;
        }
        return null;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        final var copy = new DunewyrmComponent();
        copy.variant = variant;
        copy.encounterId = encounterId;
        copy.primary = primary;
        copy.state = state;
        for (final DunewyrmSegment segment : segments) {
            copy.segments.add(segment.copyMeta());
        }
        copy.home.set(home);
        copy.headPosition.set(headPosition);
        copy.yaw = yaw;
        copy.chargeYaw = chargeYaw;
        copy.stateTimer = stateTimer;
        copy.attackCooldown = attackCooldown;
        copy.initialBodyHealth = initialBodyHealth;
        return copy;
    }
}
