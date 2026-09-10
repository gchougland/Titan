package com.hexvane.titan.crypt;

import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.worldgen.chunk.ChunkGenerator;
import com.hypixel.hytale.server.worldgen.loader.WorldGenPrefabSupplier;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/** Rejects the whole crypt before any block or marker entity is pasted at an exposed site. */
final class CryptTerrainPrefabSupplier extends WorldGenPrefabSupplier {
    private final WorldGenPrefabSupplier source;
    private final Map<Site, Boolean> suitability = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Site, Boolean> entry) {
            return size() > 2048;
        }
    };

    CryptTerrainPrefabSupplier(WorldGenPrefabSupplier source) {
        super(source.getLoader(), source.getPrefabKey(), source.getPath());
        this.source = source;
    }

    @Nullable @Override public IPrefabBuffer get() {
        // Keep the engine's source-buffer cache independent from location suitability:
        // one rejected location must never cache null for every subsequent location.
        var prefab = source.get();
        if (prefab == null) return null;
        var paste = ChunkGenerator.getResource().prefabBuffer;
        if (paste.execution == null || paste.supplier != this) return prefab;
        // The authored entrance is asymmetric; the modifier deliberately fixes rotation 0.
        if (paste.rotation != PrefabRotation.ROTATION_0) return null;

        var generator = paste.execution.getChunkGenerator();
        var site = new Site(generator, paste.seed, paste.posWorld.x, paste.posWorld.y, paste.posWorld.z);
        final boolean valid;
        synchronized (suitability) {
            valid = suitability.computeIfAbsent(site, key -> CryptRoofProfile.AUTHORED.fits(
                key.x, key.y, key.z, (x, z) -> key.generator.getHeight(key.seed, x, z)));
        }
        // PrefabPasteUtil.generate0 calls supplier.get() after publishing this context,
        // on every chunk, even if source.get() returned a cached buffer. Returning null
        // exits before the first block/fluid/entity, so all chunks share one verdict.
        return valid ? prefab : null;
    }

    private record Site(ChunkGenerator generator, int seed, int x, int y, int z) {}
}
