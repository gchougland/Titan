package com.hexvane.titan.spawn;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.longs.Long2FloatMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;

import javax.annotation.Nonnull;

/**
 * The only thing about a titan worth writing to disk: which sites the players have already cleared.
 *
 * <p>Where titans stand, which variant each one is and how it is built all fall out of the world seed, so a
 * restart puts the same titan back in the same place unaided. A kill is the one fact the seed cannot
 * supply. Damage short of a kill is not kept, so a wounded titan is whole again next time, as with the rest
 * of the world's bosses.
 *
 * <p>The engine loads registered resources from the world's save directory at startup, so this arrives
 * populated and the spawn system only has to save it when a record changes.
 */
public final class TitanSiteMemory implements Resource<EntityStore> {

    @Nonnull
    public static final String ID = "TitanSiteMemory";

    /**
     * Duration for {@link #markCleared} that never counts down.
     *
     * <p>For sites whose titan is not coming back at all rather than not for a while: a Baba Yaga egg
     * hatches into something the player keeps, so a nest that produced another one after a cooldown would
     * turn a one-off find into a farm.
     */
    public static final float FOREVER = -1f;

    @Nonnull
    public static final BuilderCodec<TitanSiteMemory> CODEC = BuilderCodec.builder(TitanSiteMemory.class, TitanSiteMemory::new)
        .append(
            new KeyedCodec<>("Cleared", new ArrayCodec<>(TitanClearedSite.CODEC, TitanClearedSite[]::new)),
            TitanSiteMemory::setCleared,
            TitanSiteMemory::getCleared
        ).add()
        .build();

    @Nonnull
    private final Long2FloatOpenHashMap cleared = new Long2FloatOpenHashMap();

    private transient boolean dirty;

    public TitanSiteMemory() {
    }

    private TitanSiteMemory(@Nonnull final TitanSiteMemory other) {
        synchronized (other) {
            cleared.putAll(other.cleared);
        }
    }

    /**
     * Records that a site's titan has been killed and must stay gone for {@code seconds}, or for good if
     * that is {@link #FOREVER}.
     *
     * <p>Synchronised because the kill is reported by the AI, which ticks titans in parallel, while the
     * countdown and the save run on the world thread. Kills are rare enough for the lock to cost nothing.
     */
    public synchronized void markCleared(final long cell, final float seconds) {
        cleared.put(cell, seconds);
        dirty = true;
    }

    public synchronized boolean isCleared(final long cell) {
        return cleared.containsKey(cell);
    }

    /** Counts a slice of time off every record, dropping the ones that have run out. */
    public synchronized void expire(final float dt) {
        if (cleared.isEmpty()) return;

        final var iterator = cleared.long2FloatEntrySet().iterator();
        while (iterator.hasNext()) {
            final Long2FloatMap.Entry entry = iterator.next();
            if (entry.getFloatValue() == FOREVER) continue;

            final float remaining = entry.getFloatValue() - dt;
            if (remaining <= 0f) {
                iterator.remove();
                dirty = true;
            } else {
                entry.setValue(remaining);
            }
        }
    }

    public synchronized int size() {
        return cleared.size();
    }

    /**
     * Whether a record has been added or dropped since the last save.
     *
     * <p>The ticking countdown alone does not count. A record that expires unsaved comes back as an expired
     * record on the next load and is dropped there instead.
     */
    public synchronized boolean consumeDirty() {
        if (!dirty) return false;
        dirty = false;
        return true;
    }

    @Nonnull
    private synchronized TitanClearedSite[] getCleared() {
        final var out = new TitanClearedSite[cleared.size()];
        int index = 0;
        for (final Long2FloatMap.Entry entry : cleared.long2FloatEntrySet()) {
            out[index++] = new TitanClearedSite(entry.getLongKey(), entry.getFloatValue());
        }
        return out;
    }

    private synchronized void setCleared(@Nonnull final TitanClearedSite[] records) {
        cleared.clear();
        for (final TitanClearedSite record : records) {
            if (record.getSeconds() > 0f || record.getSeconds() == FOREVER) {
                cleared.put(record.getCell(), record.getSeconds());
            }
        }
    }

    @Nonnull
    @Override
    public Resource<EntityStore> clone() {
        return new TitanSiteMemory(this);
    }
}
