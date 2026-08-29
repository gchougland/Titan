package com.hexvane.titan.asset;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps logical animation names ({@code Sleep}, {@code Walk}, {@code Attack_Arm_L}, ...) to
 * {@code .blockyanim} files and their playback settings.
 */
public final class TitanClipSetAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, TitanClipSetAsset>> {

    @Nonnull
    public static final DefaultAssetMap<String, TitanClipSetAsset> ASSET_MAP = new DefaultAssetMap<>();

    @Nonnull
    public static final AssetBuilderCodec<String, TitanClipSetAsset> CODEC = AssetBuilderCodec.builder(
        TitanClipSetAsset.class,
        TitanClipSetAsset::new,
        Codec.STRING,
        (a, id) -> a.id = id,
        a -> a.id,
        (a, data) -> a.data = data,
        a -> a.data
    )
        .append(
            new KeyedCodec<>("Animations", new MapCodec<TitanClipEntry, HashMap<String, TitanClipEntry>>(TitanClipEntry.CODEC, HashMap::new), true),
            (a, v) -> a.animations = v,
            a -> a.animations
        ).add()
        .build();

    private String id;
    private AssetExtraInfo.Data data;
    @Nonnull
    private Map<String, TitanClipEntry> animations = Map.of();

    @Nonnull
    @Override
    public String getId() {
        return id;
    }

    @Nonnull
    public Map<String, TitanClipEntry> getAnimations() {
        return animations;
    }

    @Nullable
    public TitanClipEntry get(@Nonnull final String name) {
        return animations.get(name);
    }

    @Nullable
    public static TitanClipSetAsset find(@Nullable final String id) {
        return id == null ? null : ASSET_MAP.getAsset(id);
    }
}
