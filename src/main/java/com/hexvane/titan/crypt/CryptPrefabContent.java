package com.hexvane.titan.crypt;

import com.hypixel.hytale.builtin.worldgen.modifier.content.common.HeightMask;
import com.hypixel.hytale.builtin.worldgen.modifier.content.prefab.BiomePrefabContent;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.common.map.IWeightedMap;
import com.hypixel.hytale.procedurallib.json.SeedString;
import com.hypixel.hytale.server.worldgen.SeedStringResource;
import com.hypixel.hytale.server.worldgen.loader.WorldGenPrefabSupplier;
import com.hypixel.hytale.server.worldgen.prefab.PrefabCategory;
import com.hypixel.hytale.server.worldgen.prefab.PrefabPatternGenerator;

import javax.annotation.Nonnull;

/** A normal biome prefab with enough cross-biome search radius for the 132-block crypt. */
public final class CryptPrefabContent extends BiomePrefabContent {
    public static final String ID = "TitanCryptPrefab";
    public static final BuilderCodec<CryptPrefabContent> CODEC = BuilderCodec
        .builder(CryptPrefabContent.class, CryptPrefabContent::new, BiomePrefabContent.CODEC).build();

    @Override protected IWeightedMap<WorldGenPrefabSupplier> buildPrefabs(@Nonnull SeedString<SeedStringResource> seed) {
        return super.buildPrefabs(seed).resolveKeys(CryptTerrainPrefabSupplier::new, WorldGenPrefabSupplier[]::new);
    }

    @Override protected PrefabPatternGenerator buildPattern(@Nonnull SeedString<SeedStringResource> seed) {
        // BiomePrefabContent hard-codes MaxSize=24. That under-advertises this
        // building's reach to BiomePatternGenerator, clipping it at biome/zone edges.
        return new PrefabPatternGenerator(seed.hashCode(), PrefabCategory.NONE, grid.build(seed),
            heightMask.getCondition(), HeightMask.DEFAULT_HEIGHT_THRESHOLD, blockMask.getBlockMask(),
            noiseMask.build(seed), parentMask.getCondition(), rotations, offset,
            false, false, false, false, 160, 160, baseChecks);
    }
}
