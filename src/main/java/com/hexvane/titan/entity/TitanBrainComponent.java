package com.hexvane.titan.entity;

import com.hexvane.titan.TitanRegistry;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Lives on the invisible brain NPC. Points at the titan root that Role Actions should drive.
 *
 * <p>Runtime-only: the brain is spawned with the titan and torn down with it.
 */
public final class TitanBrainComponent implements Component<EntityStore> {

    @Nullable
    private Ref<EntityStore> titanRoot;

    public TitanBrainComponent() {
    }

    public TitanBrainComponent(@Nonnull final Ref<EntityStore> titanRoot) {
        this.titanRoot = titanRoot;
    }

    public static ComponentType<EntityStore, TitanBrainComponent> getComponentType() {
        return TitanRegistry.getBrainComponentType();
    }

    @Nullable
    public Ref<EntityStore> getTitanRoot() {
        return titanRoot;
    }

    public void setTitanRoot(@Nullable final Ref<EntityStore> titanRoot) {
        this.titanRoot = titanRoot;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        final var copy = new TitanBrainComponent();
        copy.titanRoot = titanRoot;
        return copy;
    }
}
