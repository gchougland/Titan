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
 * <p>Everything else is recoverable without saving anything. Where titans stand, which variant each one is
 * and how it is built all fall out of the world seed, so a restart puts the same titan back in the same
 * place on its own. What the seed cannot know is that somebody already came and killed one, and without
 * that the boss a player just spent ten minutes on would be waiting for them again after the next restart.
 *
 * <p>The engine loads registered resources from the world's save directory at startup, so this arrives
 * populated; the spawn system only has to save it when a record changes.
 *
 * <p>Damage short of a kill is deliberately not kept. A titan you wounded and walked away from is whole
 * again next time, which is the same rule the rest of the world's bosses follow.
 */
public final class TitanSiteMemory implements Resource<EntityStore> {

    @Nonnull
    public static final String ID = "TitanSiteMemory";

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
     * Records that a site's titan has been killed and must stay gone for {@code seconds}.
     *
     * <p>Synchronised because the kill is reported by the AI, which ticks titans in parallel, while the
     * countdown and the save run on the world thread. Kills are rare enough that the lock never matters.
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
     * Whether a record has changed since the last save.
     *
     * <p>The ticking countdown alone does not count. Rewriting the file every tick to shave a fraction of a
     * second off a fifteen minute timer would be absurd, and a record that expires unsaved simply reappears
     * as an expired record on the next load and is dropped there instead.
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
            if (record.getSeconds() > 0f) cleared.put(record.getCell(), record.getSeconds());
        }
    }

    @Nonnull
    @Override
    public Resource<EntityStore> clone() {
        return new TitanSiteMemory(this);
    }
}
