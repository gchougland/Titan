package com.hexvane.titan;

import com.hexvane.titan.bootstrap.TitanBootstrap;
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
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class TitanPlugin extends JavaPlugin {
    private static TitanPlugin instance;

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

        TitanBootstrap.install(this);
        getLogger().atInfo().log("Titan v%s loaded.", modVersion);
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
