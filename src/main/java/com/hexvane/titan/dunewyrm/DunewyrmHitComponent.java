package com.hexvane.titan.dunewyrm;

import com.hexvane.titan.TitanRegistry;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Marks a body-segment voxel as hittable. Damage is cancelled and charged to the segment HP pool on the
 * owning snake root.
 */
public final class DunewyrmHitComponent implements Component<EntityStore> {

    @Nonnull
    public static ComponentType<EntityStore, DunewyrmHitComponent> getComponentType() {
        return TitanRegistry.getDunewyrmHitComponentType();
    }

    @Nullable
    private Ref<EntityStore> owner;
    private int segmentIndex;

    public DunewyrmHitComponent() {
    }

    public DunewyrmHitComponent(@Nonnull final Ref<EntityStore> owner, final int segmentIndex) {
        this.owner = owner;
        this.segmentIndex = segmentIndex;
    }

    @Nullable
    public Ref<EntityStore> getOwner() {
        return owner;
    }

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public void setSegmentIndex(final int segmentIndex) {
        this.segmentIndex = segmentIndex;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        final var copy = new DunewyrmHitComponent();
        copy.owner = owner;
        copy.segmentIndex = segmentIndex;
        return copy;
    }
}
