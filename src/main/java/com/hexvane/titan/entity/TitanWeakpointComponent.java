package com.hexvane.titan.entity;

import com.hexvane.titan.TitanRegistry;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
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
    @Nonnull
    private final Quaterniond localRotation = new Quaterniond();
    private boolean broken;

    public TitanWeakpointComponent() {
    }

    public TitanWeakpointComponent(@Nonnull final Ref<EntityStore> owner,
                                   final int boneIndex,
                                   @Nonnull final Vector3d localOffset,
                                   @Nonnull final Quaterniondc localRotation) {
        this.owner = owner;
        this.boneIndex = boneIndex;
        this.localOffset.set(localOffset);
        this.localRotation.set(localRotation);
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

    /**
     * Orientation relative to the bone, tilting the ore's growth axis along the socket's outward normal so
     * a node on the chest juts forwards and one on the back juts backwards instead of all of them standing
     * upright.
     */
    @Nonnull
    public Quaterniondc getLocalRotation() {
        return localRotation;
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
        copy.localRotation.set(localRotation);
        copy.broken = broken;
        return copy;
    }
}
