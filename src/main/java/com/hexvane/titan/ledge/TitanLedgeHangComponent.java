package com.hexvane.titan.ledge;

import com.hexvane.titan.TitanRegistry;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Marks a player who is hanging: Minecart-mounted to an invisible {@link TitanLedgeCartComponent}.
 */
public final class TitanLedgeHangComponent implements Component<EntityStore> {

    @Nonnull
    public static ComponentType<EntityStore, TitanLedgeHangComponent> getComponentType() {
        return TitanRegistry.getLedgeHangComponentType();
    }

    @Nullable
    private Ref<EntityStore> cart;
    @Nullable
    private String lastAnim;
    private boolean jumpHeld;
    private boolean crouchHeld;

    public TitanLedgeHangComponent() {
    }

    public TitanLedgeHangComponent(@Nonnull final Ref<EntityStore> cart) {
        this.cart = cart;
    }

    @Nullable
    public Ref<EntityStore> getCart() {
        return cart;
    }

    public void setCart(@Nullable final Ref<EntityStore> cart) {
        this.cart = cart;
    }

    @Nullable
    public String getLastAnim() {
        return lastAnim;
    }

    public void setLastAnim(@Nullable final String lastAnim) {
        this.lastAnim = lastAnim;
    }

    public boolean isJumpHeld() {
        return jumpHeld;
    }

    public void setJumpHeld(final boolean jumpHeld) {
        this.jumpHeld = jumpHeld;
    }

    public boolean isCrouchHeld() {
        return crouchHeld;
    }

    public void setCrouchHeld(final boolean crouchHeld) {
        this.crouchHeld = crouchHeld;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        final var copy = new TitanLedgeHangComponent();
        copy.cart = cart;
        copy.lastAnim = lastAnim;
        copy.jumpHeld = jumpHeld;
        copy.crouchHeld = crouchHeld;
        return copy;
    }
}
