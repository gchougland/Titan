package com.hexvane.titan.yaga;

import com.hexvane.titan.TitanRegistry;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Marks a worldgen nest that should hold a Baba Yaga egg.
 *
 * <p>Placed inside the nest structure prefab so the nest scenery is permanent world blocks and the egg is
 * assembled by Titan code when the marker loads. {@link #hatched} is what makes the nest one-shot: once an
 * egg has been cracked open the marker stays and never asks for another.
 *
 * <p>Serializable (unlike the rest of a titan) so a nest survives chunk unload and still remembers whether
 * it has already been claimed.
 */
public final class YagaEggSiteComponent implements Component<EntityStore> {

    @Nonnull
    public static final String ID = "YagaEggSite";

    @Nonnull
    public static final BuilderCodec<YagaEggSiteComponent> CODEC =
        BuilderCodec.builder(YagaEggSiteComponent.class, YagaEggSiteComponent::new)
            .append(
                new KeyedCodec<>("Variant", Codec.STRING),
                (c, v) -> c.variant = v == null || v.isEmpty() ? "Yaga_Egg" : v,
                c -> c.variant
            ).add()
            .append(
                new KeyedCodec<>("Hatched", Codec.BOOLEAN),
                (c, v) -> c.hatched = v,
                c -> c.hatched
            ).add()
            .build();

    @Nonnull
    public static ComponentType<EntityStore, YagaEggSiteComponent> getComponentType() {
        return TitanRegistry.getYagaEggSiteComponentType();
    }

    @Nonnull
    private String variant = "Yaga_Egg";
    private boolean hatched;

    /**
     * True while a deferred spawn is in flight. Runtime only: a restart clears it and the next load tries
     * again, which is the safe direction.
     */
    private transient boolean pending;

    public YagaEggSiteComponent() {
    }

    @Nonnull
    public String getVariant() {
        return variant;
    }

    public boolean isHatched() {
        return hatched;
    }

    public void setHatched(final boolean hatched) {
        this.hatched = hatched;
    }

    public boolean isPending() {
        return pending;
    }

    public void setPending(final boolean pending) {
        this.pending = pending;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        final var copy = new YagaEggSiteComponent();
        copy.variant = variant;
        copy.hatched = hatched;
        return copy;
    }
}
