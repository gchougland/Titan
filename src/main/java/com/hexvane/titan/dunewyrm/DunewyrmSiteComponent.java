package com.hexvane.titan.dunewyrm;

import com.hexvane.titan.TitanRegistry;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Worldgen marker inside {@code Dunewyrm_Structure_Site}. Serializable so cleared sites stay cleared across
 * chunk unload.
 */
public final class DunewyrmSiteComponent implements Component<EntityStore> {

    @Nonnull
    public static final String ID = "DunewyrmSite";

    @Nonnull
    public static final BuilderCodec<DunewyrmSiteComponent> CODEC =
        BuilderCodec.builder(DunewyrmSiteComponent.class, DunewyrmSiteComponent::new)
            .append(
                new KeyedCodec<>("Cleared", Codec.BOOLEAN),
                (c, v) -> c.cleared = v,
                c -> c.cleared
            ).add()
            .build();

    @Nonnull
    public static ComponentType<EntityStore, DunewyrmSiteComponent> getComponentType() {
        return TitanRegistry.getDunewyrmSiteComponentType();
    }

    private boolean cleared;
    private transient boolean pending;

    public DunewyrmSiteComponent() {
    }

    public boolean isCleared() {
        return cleared;
    }

    public void setCleared(final boolean cleared) {
        this.cleared = cleared;
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
        final var copy = new DunewyrmSiteComponent();
        copy.cleared = cleared;
        return copy;
    }
}
