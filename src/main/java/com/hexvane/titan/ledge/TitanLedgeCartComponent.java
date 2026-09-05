package com.hexvane.titan.ledge;

import com.hexvane.titan.TitanRegistry;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Invisible cling-cart the player Minecart-mounts. Ledge slabs are rails; this entity is what slides.
 */
public final class TitanLedgeCartComponent implements Component<EntityStore> {

    @Nonnull
    public static ComponentType<EntityStore, TitanLedgeCartComponent> getComponentType() {
        return TitanRegistry.getLedgeCartComponentType();
    }

    @Nullable
    private Ref<EntityStore> ledge;
    @Nullable
    private Ref<EntityStore> rider;
    private float t;

    public TitanLedgeCartComponent() {
    }

    public TitanLedgeCartComponent(@Nonnull final Ref<EntityStore> ledge,
                                   @Nonnull final Ref<EntityStore> rider,
                                   final float t) {
        this.ledge = ledge;
        this.rider = rider;
        this.t = t;
    }

    @Nullable
    public Ref<EntityStore> getLedge() {
        return ledge;
    }

    public void setLedge(@Nullable final Ref<EntityStore> ledge) {
        this.ledge = ledge;
    }

    @Nullable
    public Ref<EntityStore> getRider() {
        return rider;
    }

    public float getT() {
        return t;
    }

    public void setT(final float t) {
        this.t = t;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        return new TitanLedgeCartComponent();
    }
}
