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
     * Writes the config back out over what was just read.
     *
     * <p>A missing config file is not an error: the engine hands back the codec's defaults and never writes
     * anything, which leaves a server owner with nothing to edit and no way to discover what is tunable. A
     * file that predates a new option has the same problem for that option alone. Saving on every boot
     * covers both, and is safe because what gets written is what was read: any value already set survives
     * the round trip and only the keys that were absent appear, at their defaults.
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
