package com.hexvane.titan.bootstrap;

import com.hexvane.titan.TitanRegistry;
import com.hexvane.titan.anim.TitanClipLibrary;
import com.hexvane.titan.asset.TitanClipSetAsset;
import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hexvane.titan.asset.TitanSpawnRuleAsset;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.command.TitanCommand;
import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.npc.TitanNpcRegistration;
import com.hexvane.titan.spawn.PrefabVoxelReader;
import com.hexvane.titan.spawn.TitanEnvironment;
import com.hexvane.titan.spawn.TitanSiteMemory;
import com.hexvane.titan.system.TitanAiSystem;
import com.hexvane.titan.system.TitanAnimationSystem;
import com.hexvane.titan.system.TitanBossBarSystem;
import com.hexvane.titan.system.TitanBrainSyncSystem;
import com.hexvane.titan.system.TitanBoulderSystem;
import com.hexvane.titan.system.TitanHealthSyncSystem;
import com.hexvane.titan.system.TitanPartSyncSystem;
import com.hexvane.titan.system.TitanRagdollSystem;
import com.hexvane.titan.system.TitanRootDamageSystem;
import com.hexvane.titan.system.TitanShellDamageSystem;
import com.hexvane.titan.system.TitanSpawnFxSystem;
import com.hexvane.titan.system.TitanSyncStats;
import com.hexvane.titan.system.TitanTrioCleanupSystem;
import com.hexvane.titan.system.TitanWeakpointDamageBonusSystem;
import com.hexvane.titan.system.TitanWeakpointDamageSystem;
import com.hexvane.titan.system.TitanWeakpointDeathSystem;
import com.hexvane.titan.system.TitanWeakpointSystem;
import com.hexvane.titan.system.TitanWorldSpawnSystem;
import com.hexvane.titan.yaga.YagaEggSiteSystem;
import com.hexvane.titan.yaga.YagaEggSystem;
import com.hexvane.titan.yaga.YagaFurnaceSystem;
import com.hexvane.titan.yaga.YagaInteractSystem;
import com.hexvane.titan.yaga.YagaLeapInteraction;
import com.hexvane.titan.yaga.YagaMemory;
import com.hexvane.titan.yaga.YagaPetSystem;
import com.hexvane.titan.yaga.YagaPointInteraction;
import com.hexvane.titan.yaga.YagaRespawnSystem;
import com.hexvane.titan.ledge.TitanLedgeHangSystem;
import com.hexvane.titan.ledge.TitanLedgeInteractSystem;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Single place where the mod hands itself to the engine: asset types, components, systems and commands.
 *
 * <p>Called from {@code TitanPlugin.setup()}. Order matters: components must exist before the systems that
 * query them are constructed, because a system builds its query in its constructor.
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

    /** Exposed so the {@code /titan yaga} commands can read and edit what the houses remember. */
    @Nullable
    private static ResourceType<EntityStore, YagaMemory> yagaMemoryType;

    @Nullable
    public static ResourceType<EntityStore, YagaMemory> getYagaMemoryType() {
        return yagaMemoryType;
    }

    private TitanBootstrap() {
    }

    public static void install(@Nonnull final PluginBase plugin) {
        TitanNpcRegistration.register(NPCPlugin.get());
        registerInteractions();
        registerAssets(plugin);
        registerComponentsAndSystems(plugin);
        applyEngineGlobals();
        plugin.getCommandRegistry().registerCommand(new TitanCommand());
    }

    /**
     * Teaches the interaction parser the types this mod's items use.
     *
     * <p>Before the assets, and not optional: an item naming a type nothing has registered does not fall
     * back to doing nothing, it fails to parse, and the item goes missing along with its interaction.
     */
    private static void registerInteractions() {
        Interaction.CODEC.register(YagaLeapInteraction.TYPE, YagaLeapInteraction.class, YagaLeapInteraction.CODEC);
        Interaction.CODEC.register(YagaPointInteraction.TYPE, YagaPointInteraction.class, YagaPointInteraction.CODEC);
    }

    /**
     * Pushes the config's engine-wide settings onto the engine.
     *
     * <p>Only the entity level-of-detail ratio so far, and it is a global shared with everything else that
     * has a small bounding box. The engine stops sending a client any entity whose thickness is small next
     * to its distance, which for a titan's one-block voxels lands at about 169 blocks, inside the default
     * view distance, so a titan on the horizon comes apart while its silhouette is still legible. Raising
     * it costs every dropped item staying visible further out, so the engine's own default is restored
     * when the config carries no value.
     */
    private static void applyEngineGlobals() {
        final double ratio = TitanConfig.get().getEntityLodRatio();
        EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO = ratio > 0
            ? ratio
            : EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO_DEFAULT;
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
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, TitanVariantAsset.class, TitanBootstrap::onVariantsLoaded);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, TitanSpawnRuleAsset.class, TitanBootstrap::onSpawnRulesLoaded);
    }

    private static void onClipSetsLoaded(@Nonnull final LoadedAssetsEvent<String, TitanClipSetAsset, DefaultAssetMap<String, TitanClipSetAsset>> event) {
        TitanClipLibrary.invalidate();
    }

    private static void onSkeletonsLoaded(@Nonnull final LoadedAssetsEvent<String, TitanSkeletonAsset, DefaultAssetMap<String, TitanSkeletonAsset>> event) {
        TitanClipLibrary.invalidate();
        PrefabVoxelReader.invalidate();
    }

    /** The wander fence caches variant environment names resolved to indexes, so a reload has to drop it. */
    private static void onVariantsLoaded(@Nonnull final LoadedAssetsEvent<String, TitanVariantAsset, DefaultAssetMap<String, TitanVariantAsset>> event) {
        TitanEnvironment.invalidate();
    }

    private static void onSpawnRulesLoaded(@Nonnull final LoadedAssetsEvent<String, TitanSpawnRuleAsset, DefaultAssetMap<String, TitanSpawnRuleAsset>> event) {
        TitanSpawnRuleAsset.invalidate();
        if (worldSpawnSystem != null) worldSpawnSystem.onRulesReloaded();
    }

    private static void registerComponentsAndSystems(@Nonnull final PluginBase plugin) {
        final var registry = plugin.getEntityStoreRegistry();
        TitanRegistry.register(registry);

        // Cleared sites are the one thing about a titan that cannot be recovered from the world seed, so
        // they ride along in the world's save directory. Registered before the systems: both the AI and the
        // spawner are handed the type.
        siteMemoryType = registry.registerResource(
            TitanSiteMemory.class, TitanSiteMemory.ID, TitanSiteMemory.CODEC);

        // A Baba Yaga house, by contrast, is entirely the result of what a player did, so all of it is
        // kept: where it is, how grown it is, and what is in its chests.
        yagaMemoryType = registry.registerResource(
            YagaMemory.class, YagaMemory.ID, YagaMemory.CODEC);

        registry.registerSystem(new TitanAiSystem(siteMemoryType));
        registry.registerSystem(new TitanAnimationSystem());
        registry.registerSystem(new TitanPartSyncSystem());
        // After part sync, despite running before it: a system's declared dependencies are validated as it
        // is registered, so anything it names has to be registered already. This one sits between the
        // animation and the sync, and names both.
        registry.registerSystem(new TitanSpawnFxSystem());
        registry.registerSystem(new TitanSyncStats.Roll());
        registry.registerSystem(new TitanWeakpointSystem());
        registry.registerSystem(new TitanWeakpointDeathSystem());
        registry.registerSystem(new TitanRootDamageSystem());
        registry.registerSystem(new TitanHealthSyncSystem());
        registry.registerSystem(new TitanBossBarSystem());
        registry.registerSystem(new TitanBossBarSystem.Removal());
        registry.registerSystem(new TitanBrainSyncSystem());
        registry.registerSystem(new TitanTrioCleanupSystem());
        registry.registerSystem(new TitanWeakpointDamageBonusSystem());
        registry.registerSystem(new TitanShellDamageSystem());
        registry.registerSystem(new TitanWeakpointDamageSystem());
        registry.registerSystem(new TitanRagdollSystem());
        registry.registerSystem(new TitanBoulderSystem());
        registry.registerSystem(new TitanBoulderSystem.Parts());

        // The pets. TitanAiSystem hands them over rather than sharing them, so between them these carry the
        // whole of what a Baba Yaga does: the egg's hatch, and the house's steering and crouch.
        registry.registerSystem(new YagaEggSiteSystem.EnsureComponents());
        registry.registerSystem(new YagaEggSiteSystem.SpawnOnAdd());
        registry.registerSystem(new YagaEggSystem(siteMemoryType));
        registry.registerSystem(new YagaPetSystem());
        registry.registerSystem(new YagaFurnaceSystem());
        registry.registerSystem(new YagaInteractSystem());
        registry.registerSystem(new YagaRespawnSystem(yagaMemoryType));

        registry.registerSystem(new TitanLedgeInteractSystem());
        registry.registerSystem(new TitanLedgeHangSystem());

        worldSpawnSystem = new TitanWorldSpawnSystem(siteMemoryType);
        registry.registerSystem(worldSpawnSystem);
    }
}
