package com.hexvane.titan;

import com.hexvane.titan.bootstrap.TitanBootstrap;
import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.generated.HstatsBuildMetadata;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.AssetPackRegisterEvent;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.util.Config;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for the Titan mod.
 *
 * <p>{@link #setup()} loads the server config and hands off to {@link TitanBootstrap}, which registers the
 * components, systems, assets and commands. {@link #start()} publishes the mod's asset pack so the Asset
 * Editor can see it.
 */
public final class TitanPlugin extends JavaPlugin {
    private static TitanPlugin instance;

    /**
     * Server-owner tuning, at {@code mods/Hexvane_Titan/config.json}. Declared as a field because
     * {@code withConfig} refuses to run once setup has started, and the engine loads it before setup.
     */
    private final Config<TitanConfig> config = withConfig(TitanConfig.CODEC);

    public TitanPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Nullable
    public static TitanPlugin get() {
        return instance;
    }

    @Override
    protected void setup() {
        instance = this;

        String hstatsModUuid = HstatsBuildMetadata.HSTATS_MOD_UUID;
        String modVersion = this.getManifest().getVersion().toString();
        if (!hstatsModUuid.isBlank()) {
            new HStats(hstatsModUuid, modVersion);
            getLogger().atInfo().log("HStats metrics enabled for Titan v%s.", modVersion);
        } else {
            getLogger()
                .atInfo()
                .log(
                    "HStats metrics disabled: set TITAN_HSTATS_MOD_UUID when building, "
                        + "or Gradle property hstats_mod_uuid, to your hstats.dev mod UUID."
                );
        }

        TitanConfig.setActive(config.get());
        syncConfigFile();

        TitanBootstrap.install(this);
        getLogger().atInfo().log("Titan v%s loaded.", modVersion);
    }

    /**
     * Writes the config back out over what was just read, so the file on disk always lists every option.
     *
     * <p>The engine falls back to codec defaults without writing a file, so a fresh install would otherwise
     * leave the owner with nothing to edit, and a file predating a new option would omit that option.
     * Saving what was just read is lossless: existing values survive the round trip and absent keys are
     * added at their defaults.
     */
    private void syncConfigFile() {
        Path path = getDataDirectory().resolve("config.json");
        boolean existed = Files.exists(path);

        config.save().whenComplete((ignored, error) -> {
            if (error != null) {
                getLogger().atWarning().withCause(error).log("Could not write config to %s", path);
            } else if (!existed) {
                getLogger().atInfo().log("Wrote default config to %s", path);
            }
        });
    }

    @Override
    protected void start() {
        if (!this.getManifest().includesAssetPack()) {
            return;
        }

        String packId = new PluginIdentifier(this.getManifest()).toString();
        AssetPack pack = AssetModule.get().getAssetPack(packId);
        if (pack == null) {
            getLogger().atWarning().log("Asset pack %s not found in AssetModule; Asset Editor may not list this mod", packId);
            return;
        }

        HytaleServer.get()
            .getEventBus()
            .<Void, AssetPackRegisterEvent>dispatchFor(AssetPackRegisterEvent.class)
            .dispatch(new AssetPackRegisterEvent(pack));

        CommonAssetModule commonAssets = CommonAssetModule.get();
        if (commonAssets != null) {
            commonAssets.loadCommonAssets(pack, System.nanoTime());
            if (Universe.get().getPlayerCount() > 0) {
                Universe.get().broadcastPacketNoCache(new RequestCommonAssetsRebuild());
            }
        }
    }
}
