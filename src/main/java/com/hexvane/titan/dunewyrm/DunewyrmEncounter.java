package com.hexvane.titan.dunewyrm;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared encounter bookkeeping for a Dunewyrm and any snakes produced by splitting it.
 *
 * <p>Tunnel phases and loot-once are per encounter id, not per snake root.
 */
public final class DunewyrmEncounter {

    private static final Map<UUID, DunewyrmEncounter> ENCOUNTERS = new ConcurrentHashMap<>();

    @Nonnull
    private final UUID id;
    private float initialBodyHealth;
    private float remainingBodyHealth;
    private boolean tunnelHighUsed;
    private boolean tunnelLowUsed;
    private boolean lootDropped;
    private int livingSnakes;
    public boolean levelScalingCaptured;
    private com.hexvane.titan.compat.LevelingCompatibility.Scaling leveling = com.hexvane.titan.compat.LevelingCompatibility.Scaling.NONE;
    public com.hexvane.titan.compat.LevelingCompatibility.Scaling getLeveling() { return leveling; }
    public void setLeveling(com.hexvane.titan.compat.LevelingCompatibility.Scaling value) { leveling = value; }

    private DunewyrmEncounter(@Nonnull final UUID id) {
        this.id = id;
    }

    @Nonnull
    public static DunewyrmEncounter getOrCreate(@Nonnull final UUID id) {
        return ENCOUNTERS.computeIfAbsent(id, DunewyrmEncounter::new);
    }

    public static void remove(@Nonnull final UUID id) {
        ENCOUNTERS.remove(id);
    }

    @Nonnull
    public UUID getId() {
        return id;
    }

    public float getInitialBodyHealth() {
        return initialBodyHealth;
    }

    public void setInitialBodyHealth(final float initialBodyHealth) {
        this.initialBodyHealth = initialBodyHealth;
        this.remainingBodyHealth = initialBodyHealth;
    }

    public float getRemainingBodyHealth() {
        return remainingBodyHealth;
    }

    public void setRemainingBodyHealth(final float remainingBodyHealth) {
        this.remainingBodyHealth = Math.max(0f, remainingBodyHealth);
    }

    public float remainingFraction() {
        return initialBodyHealth <= 0f ? 0f : remainingBodyHealth / initialBodyHealth;
    }

    public boolean isTunnelHighUsed() {
        return tunnelHighUsed;
    }

    public void setTunnelHighUsed(final boolean tunnelHighUsed) {
        this.tunnelHighUsed = tunnelHighUsed;
    }

    public boolean isTunnelLowUsed() {
        return tunnelLowUsed;
    }

    public void setTunnelLowUsed(final boolean tunnelLowUsed) {
        this.tunnelLowUsed = tunnelLowUsed;
    }

    public boolean isLootDropped() {
        return lootDropped;
    }

    public void setLootDropped(final boolean lootDropped) {
        this.lootDropped = lootDropped;
    }

    public int getLivingSnakes() {
        return livingSnakes;
    }

    public void addSnake() {
        livingSnakes++;
    }

    public void removeSnake() {
        livingSnakes = Math.max(0, livingSnakes - 1);
        if (livingSnakes == 0 && remainingBodyHealth <= 0f) {
            ENCOUNTERS.remove(id);
        }
    }
}
