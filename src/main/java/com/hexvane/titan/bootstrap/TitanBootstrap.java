package com.hexvane.titan.bootstrap;

import com.hexvane.titan.TitanRegistry;
import com.hexvane.titan.anim.TitanClipLibrary;
import com.hexvane.titan.asset.TitanClipSetAsset;
import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hexvane.titan.asset.TitanSpawnRuleAsset;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.command.TitanCommand;
import com.hexvane.titan.spawn.PrefabVoxelReader;
import com.hexvane.titan.spawn.TitanSiteMemory;
import com.hexvane.titan.system.TitanAiSystem;
import com.hexvane.titan.system.TitanAnimationSystem;
import com.hexvane.titan.system.TitanPartSyncSystem;
import com.hexvane.titan.system.TitanRagdollSystem;
import com.hexvane.titan.system.TitanWeakpointDeathSystem;
import com.hexvane.titan.system.TitanWeakpointSystem;
import com.hexvane.titan.system.TitanWorldSpawnSystem;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Single place where the mod hands itself to the engine: asset types, components, systems and commands.
 *
 * <p>Called from {@code TitanPlugin.setup()}. Order matters — components must exist before the systems
 * that query them are constructed, because a system builds its query in its constructor.
 */
public final class TitanBootstrap {

    /**
     * Held so an asset reload can tell it to re-examine cells it had written off. Built during registration
     * rather than at class load, because it needs the resource type its site memory is registered under.
     */
    @Nullable
    private static TitanWorldSpawnSystem worldSpawnSystem;

    /** Exposed so {@code /titan sites} can report which sites have already been cleared. */
    @Nullable
    private static ResourceType<EntityStore, TitanSiteMemory> siteMemoryType;

    @Nullable
    public static ResourceType<EntityStore, TitanSiteMemory> getSiteMemoryType() {
        return siteMemoryType;
    }

    private TitanBootstrap() {
    }

    public static void install(@Nonnull final PluginBase plugin) {
        registerAssets(plugin);
        registerComponentsAndSystems(plugin);
        plugin.getCommandRegistry().registerCommand(new TitanCommand());
    }

    private static void registerAssets(@Nonnull final PluginBase plugin) {
        final var assets = plugin.getAssetRegistry();

        assets.register(HytaleAssetStore.builder(TitanClipSetAsset.class, TitanClipSetAsset.ASSET_MAP)
            .setPath("Titan/Clips")
            .setCodec(TitanClipSetAsset.CODEC)
            .setKeyFunction(TitanClipSetAsset::getId)
            .build());

        assets.register(HytaleAssetStore.builder(TitanSkeletonAsset.class, TitanSkeletonAsset.ASSET_MAP)
            .setPath("Titan/Skeletons")
            .setCodec(TitanSkeletonAsset.CODEC)
            .setKeyFunction(TitanSkeletonAsset::getId)
            .loadsAfter(TitanClipSetAsset.class)
            .build());

        assets.register(HytaleAssetStore.builder(TitanVariantAsset.class, TitanVariantAsset.ASSET_MAP)
            .setPath("Titan/Variants")
            .setCodec(TitanVariantAsset.CODEC)
            .setKeyFunction(TitanVariantAsset::getId)
            .loadsAfter(TitanSkeletonAsset.class)
            .build());

        assets.register(HytaleAssetStore.builder(TitanSpawnRuleAsset.class, TitanSpawnRuleAsset.ASSET_MAP)
            .setPath("Titan/Spawns")
            .setCodec(TitanSpawnRuleAsset.CODEC)
            .setKeyFunction(TitanSpawnRuleAsset::getId)
            .loadsAfter(TitanVariantAsset.class)
            .build());

        // Parsed clips and prefab voxels are derived from assets, so they have to be thrown away whenever
        // the assets behind them are reloaded.
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, TitanClipSetAsset.class, TitanBootstrap::onClipSetsLoaded);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, TitanSkeletonAsset.class, TitanBootstrap::onSkeletonsLoaded);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, TitanSpawnRuleAsset.class, TitanBootstrap::onSpawnRulesLoaded);
    }

    private static void onClipSetsLoaded(@Nonnull final LoadedAssetsEvent<String, TitanClipSetAsset, DefaultAssetMap<String, TitanClipSetAsset>> event) {
        TitanClipLibrary.invalidate();
    }

    private static void onSkeletonsLoaded(@Nonnull final LoadedAssetsEvent<String, TitanSkeletonAsset, DefaultAssetMap<String, TitanSkeletonAsset>> event) {
        TitanClipLibrary.invalidate();
        PrefabVoxelReader.invalidate();
    }

    private static void onSpawnRulesLoaded(@Nonnull final LoadedAssetsEvent<String, TitanSpawnRuleAsset, DefaultAssetMap<String, TitanSpawnRuleAsset>> event) {
        TitanSpawnRuleAsset.invalidate();
        if (worldSpawnSystem != null) worldSpawnSystem.onRulesReloaded();
    }

    private static void registerComponentsAndSystems(@Nonnull final PluginBase plugin) {
        final var registry = plugin.getEntityStoreRegistry();
        TitanRegistry.register(registry);

        // Which sites the players have already cleared is the one thing about a titan that cannot be
        // recovered from the world seed, so it rides along in the world's save directory. Registered
        // before the systems, because both the AI and the spawner are handed the type.
        siteMemoryType = registry.registerResource(
            TitanSiteMemory.class, TitanSiteMemory.ID, TitanSiteMemory.CODEC);

        registry.registerSystem(new TitanAiSystem(siteMemoryType));
        registry.registerSystem(new TitanAnimationSystem());
        registry.registerSystem(new TitanPartSyncSystem());
        registry.registerSystem(new TitanWeakpointSystem());
        registry.registerSystem(new TitanWeakpointDeathSystem());
        registry.registerSystem(new TitanRagdollSystem());

        worldSpawnSystem = new TitanWorldSpawnSystem(siteMemoryType);
        registry.registerSystem(worldSpawnSystem);
    }
}
