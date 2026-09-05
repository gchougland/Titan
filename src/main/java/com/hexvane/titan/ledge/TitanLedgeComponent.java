package com.hexvane.titan.ledge;

import com.hexvane.titan.TitanRegistry;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Marks an entity as a grab ledge: a static nub a player can Minecart-mount and slide along.
 */
public final class TitanLedgeComponent implements Component<EntityStore> {

    @Nonnull
    public static ComponentType<EntityStore, TitanLedgeComponent> getComponentType() {
        return TitanRegistry.getLedgeComponentType();
    }

    private float halfWidth = TitanLedge.HALF_WIDTH;
    private float hangDown = TitanLedge.HANG_DOWN;
    private float hangOut = TitanLedge.HANG_OUT;

    public TitanLedgeComponent() {
    }

    public TitanLedgeComponent(final float halfWidth, final float hangDown, final float hangOut) {
        this.halfWidth = halfWidth;
        this.hangDown = hangDown;
        this.hangOut = hangOut;
    }

    public float getHalfWidth() {
        return halfWidth;
    }

    public float getHangDown() {
        return hangDown;
    }

    public float getHangOut() {
        return hangOut;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        return new TitanLedgeComponent(halfWidth, hangDown, hangOut);
    }
}
