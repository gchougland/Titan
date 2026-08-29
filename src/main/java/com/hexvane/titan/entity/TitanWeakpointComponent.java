package com.hexvane.titan.entity;

import com.hexvane.titan.TitanRegistry;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * An ore node bolted to a titan's back. These are the only damageable part of the creature: breaking every
 * one of them kills it.
 */
public final class TitanWeakpointComponent implements Component<EntityStore> {

    public static ComponentType<EntityStore, TitanWeakpointComponent> getComponentType() {
        return TitanRegistry.getWeakpointComponentType();
    }

    @Nullable
    private Ref<EntityStore> owner;
    private int boneIndex;
    @Nonnull
    private final Vector3d localOffset = new Vector3d();
    private boolean broken;

    public TitanWeakpointComponent() {
    }

    public TitanWeakpointComponent(@Nonnull final Ref<EntityStore> owner, final int boneIndex, @Nonnull final Vector3d localOffset) {
        this.owner = owner;
        this.boneIndex = boneIndex;
        this.localOffset.set(localOffset);
    }

    @Nullable
    public Ref<EntityStore> getOwner() {
        return owner;
    }

    public int getBoneIndex() {
        return boneIndex;
    }

    /** Offset from the bone pivot, in model units. */
    @Nonnull
    public Vector3d getLocalOffset() {
        return localOffset;
    }

    public boolean isBroken() {
        return broken;
    }

    /**
     * Marks the node as destroyed.
     *
     * @return {@code true} the first time it is called, so the caller only decrements the titan's counter once
     */
    public boolean markBroken() {
        if (broken) return false;
        broken = true;
        return true;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        final var copy = new TitanWeakpointComponent();
        copy.owner = owner;
        copy.boneIndex = boneIndex;
        copy.localOffset.set(localOffset);
        copy.broken = broken;
        return copy;
    }
}
