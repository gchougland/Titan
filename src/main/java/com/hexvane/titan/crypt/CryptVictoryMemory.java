package com.hexvane.titan.crypt;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Set;

/** Permanent per-world, per-coffin victory records, independent of marker and reward entities. */
public final class CryptVictoryMemory implements Resource<EntityStore> {
    public static final String ID = "CryptVictoryMemory";
    public static final BuilderCodec<CryptVictoryMemory> CODEC = BuilderCodec
        .builder(CryptVictoryMemory.class, CryptVictoryMemory::new)
        .append(new KeyedCodec<>("Victories", Codec.STRING_ARRAY), (m,v) -> m.load(m.victories,v),
            m -> m.entries(m.victories)).add()
        .append(new KeyedCodec<>("Deposited", Codec.STRING_ARRAY), (m,v) -> m.load(m.deposited,v),
            m -> m.entries(m.deposited)).add().build();
    private final Set<String> victories = new HashSet<>();
    private final Set<String> deposited = new HashSet<>();

    private static String key(Vector3i coffin) { return coffin.x + ":" + coffin.y + ":" + coffin.z; }
    synchronized boolean hasVictory(Vector3i coffin) {
        // A legacy/stale journal may retain only its delivery receipt. It still proves victory.
        String key = key(coffin);
        return victories.contains(key) || deposited.contains(key);
    }
    synchronized boolean hasDeposit(Vector3i coffin) { return deposited.contains(key(coffin)); }
    synchronized boolean recordVictory(Vector3i coffin) { return victories.add(key(coffin)); }
    synchronized boolean recordDeposit(Vector3i coffin) {
        boolean changed = victories.add(key(coffin));
        return deposited.add(key(coffin)) || changed;
    }
    private synchronized String[] entries(Set<String> values) { return values.stream().sorted().toArray(String[]::new); }
    private synchronized void load(Set<String> target, String[] values) {
        target.clear();
        if (values != null) java.util.Collections.addAll(target,values);
    }
    @Nonnull @Override public synchronized CryptVictoryMemory clone() {
        var copy = new CryptVictoryMemory();
        copy.victories.addAll(victories);
        copy.deposited.addAll(deposited);
        return copy;
    }
}
