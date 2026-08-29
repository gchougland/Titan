package com.hexvane.titan.bootstrap;

import com.hexvane.titan.TitanRegistry;
import com.hexvane.titan.anim.TitanClipLibrary;
import com.hexvane.titan.asset.TitanClipSetAsset;
import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.command.TitanCommand;
import com.hexvane.titan.spawn.PrefabVoxelReader;
import com.hexvane.titan.system.TitanAiSystem;
import com.hexvane.titan.system.TitanAnimationSystem;
import com.hexvane.titan.system.TitanPartSyncSystem;
import com.hexvane.titan.system.TitanRagdollSystem;
import com.hexvane.titan.system.TitanWeakpointDeathSystem;
import com.hexvane.titan.system.TitanWeakpointSystem;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import com.hypixel.hytale.server.core.plugin.PluginBase;

import javax.annotation.Nonnull;

/**
 * Single place where the mod hands itself to the engine: asset types, components, systems and commands.
 *
 * <p>Called from {@code TitanPlugin.setup()}. Order matters — components must exist before the systems
 * that query them are constructed, because a system builds its query in its constructor.
 */
public final class TitanBootstrap {

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

        // Parsed clips and prefab voxels are derived from assets, so they have to be thrown away whenever
        // the assets behind them are reloaded.
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, TitanClipSetAsset.class, TitanBootstrap::onClipSetsLoaded);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, TitanSkeletonAsset.class, TitanBootstrap::onSkeletonsLoaded);
    }

    private static void onClipSetsLoaded(@Nonnull final LoadedAssetsEvent<String, TitanClipSetAsset, DefaultAssetMap<String, TitanClipSetAsset>> event) {
        TitanClipLibrary.invalidate();
    }

    private static void onSkeletonsLoaded(@Nonnull final LoadedAssetsEvent<String, TitanSkeletonAsset, DefaultAssetMap<String, TitanSkeletonAsset>> event) {
        TitanClipLibrary.invalidate();
        PrefabVoxelReader.invalidate();
    }

    private static void registerComponentsAndSystems(@Nonnull final PluginBase plugin) {
        final var registry = plugin.getEntityStoreRegistry();
        TitanRegistry.register(registry);

        registry.registerSystem(new TitanAiSystem());
        registry.registerSystem(new TitanAnimationSystem());
        registry.registerSystem(new TitanPartSyncSystem());
        registry.registerSystem(new TitanWeakpointSystem());
        registry.registerSystem(new TitanWeakpointDeathSystem());
        registry.registerSystem(new TitanRagdollSystem());
    }
}
