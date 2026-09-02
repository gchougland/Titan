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
 * One voxel of a boulder in flight. Follows the boulder's centre until it lands, then is handed over to the
 * debris physics that already cleans up a dead titan.
 *
 * <p>Runtime-only, for the same reason a boulder's centre is.
 */
public final class TitanBoulderPartComponent implements Component<EntityStore> {

    public static ComponentType<EntityStore, TitanBoulderPartComponent> getComponentType() {
        return TitanRegistry.getBoulderPartComponentType();
    }

    @Nullable
    private Ref<EntityStore> boulder;
    /** Offset from the boulder's centre in world blocks, before the tumble is applied. */
    @Nonnull
    private final Vector3d localOffset = new Vector3d();
    private float scale = 1f;
    /** Set once this voxel has been let go into the debris physics, so it is only handed over once. */
    private boolean released;

    public TitanBoulderPartComponent() {
    }

    public TitanBoulderPartComponent(@Nonnull final Ref<EntityStore> boulder,
                                     @Nonnull final Vector3d localOffset,
                                     final float scale) {
        this.boulder = boulder;
        this.localOffset.set(localOffset);
        this.scale = scale;
    }

    @Nullable
    public Ref<EntityStore> getBoulder() {
        return boulder;
    }

    @Nonnull
    public Vector3d getLocalOffset() {
        return localOffset;
    }

    public float getScale() {
        return scale;
    }

    public boolean isReleased() {
        return released;
    }

    public void setReleased(final boolean released) {
        this.released = released;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        final var copy = new TitanBoulderPartComponent();
        copy.boulder = boulder;
        copy.localOffset.set(localOffset);
        copy.scale = scale;
        copy.released = released;
        return copy;
    }
}
