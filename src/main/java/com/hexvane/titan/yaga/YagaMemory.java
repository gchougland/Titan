package com.hexvane.titan.yaga;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every Baba Yaga house in the world, written down.
 *
 * <p>Unlike the combat titans, a house cannot be recovered from the world seed. Where it is, what stage it
 * is at and what is in its chests are all the result of what a player did, so they have to be kept, and the
 * house is rebuilt from this on the next visit rather than respawned from a site.
 *
 * <p>The engine loads registered resources from the world's save directory at startup, so this arrives
 * populated. Saved when something in it has changed, on the same {@link #consumeDirty} pattern as
 * {@link com.hexvane.titan.spawn.TitanSiteMemory}.
 */
public final class YagaMemory implements Resource<EntityStore> {

    @Nonnull
    public static final String ID = "TitanYagaMemory";

    @Nonnull
    public static final BuilderCodec<YagaMemory> CODEC = BuilderCodec.builder(YagaMemory.class, YagaMemory::new)
        .append(
            new KeyedCodec<>("Houses", new ArrayCodec<>(YagaRecord.CODEC, YagaRecord[]::new)),
            YagaMemory::setHouses,
            YagaMemory::getHouses
        ).add()
        .build();

    /**
     * The houses, by house id, in the order they were written.
     *
     * <p>By house rather than by owner because a player can crack a second egg, and two houses sharing a
     * key would mean the first one silently ceasing to exist.
     */
    @Nonnull
    private final Map<UUID, YagaRecord> houses = new LinkedHashMap<>();

    private transient boolean dirty;

    public YagaMemory() {
    }

    private YagaMemory(@Nonnull final YagaMemory other) {
        synchronized (other) {
            houses.putAll(other.houses);
        }
    }

    /**
     * Writes down where a house is and what is in it, replacing whatever was there for that house.
     *
     * <p>Synchronised for the same reason the site memory is: the writes come from wherever a house
     * changed, and the save runs on the world thread.
     */
    public synchronized void remember(@Nonnull final UUID houseId, @Nonnull final YagaRecord record) {
        houses.put(houseId, record);
        dirty = true;
    }

    @Nullable
    public synchronized YagaRecord get(@Nonnull final UUID houseId) {
        return houses.get(houseId);
    }

    /** Throws away a house for good. What {@code /titan yaga forget} is for. */
    public synchronized void forget(@Nonnull final UUID houseId) {
        if (houses.remove(houseId) != null) dirty = true;
    }

    public synchronized int size() {
        return houses.size();
    }

    /**
     * A copy of every house written down, for the sweep that decides which ones need rebuilding.
     *
     * <p>Copied rather than exposed, because the sweep spawns houses as it goes and each spawn writes back
     * into this map.
     */
    @Nonnull
    public synchronized List<YagaRecord> snapshot() {
        return new ArrayList<>(houses.values());
    }

    public synchronized boolean consumeDirty() {
        if (!dirty) return false;
        dirty = false;
        return true;
    }

    @Nonnull
    private synchronized YagaRecord[] getHouses() {
        return houses.values().toArray(new YagaRecord[0]);
    }

    private synchronized void setHouses(@Nonnull final YagaRecord[] records) {
        houses.clear();
        for (final YagaRecord record : records) {
            final UUID houseId = record.id();
            // Dropped rather than kept: a record with no readable id or owner, or a stage this version no
            // longer has, can never be matched to a house or rebuilt, so keeping it would only make it
            // outlive the bug.
            if (houseId == null || record.ownerUuid() == null || record.stage() == null) continue;
            houses.put(houseId, record);
        }
    }

    @Nonnull
    @Override
    public Resource<EntityStore> clone() {
        return new YagaMemory(this);
    }
}
