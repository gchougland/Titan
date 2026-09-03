package com.hexvane.titan;

import com.hexvane.titan.entity.TitanBoulderComponent;
import com.hexvane.titan.entity.TitanBoulderPartComponent;
import com.hexvane.titan.entity.TitanBrainComponent;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanPartComponent;
import com.hexvane.titan.entity.TitanWeakpointComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.IComponentRegistry;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Holds the component types the mod registers, so systems and spawn code can reach them without threading
 * the plugin instance everywhere.
 */
public final class TitanRegistry {

    private static ComponentType<EntityStore, TitanComponent> titanComponentType;
    private static ComponentType<EntityStore, TitanPartComponent> partComponentType;
    private static ComponentType<EntityStore, TitanWeakpointComponent> weakpointComponentType;
    private static ComponentType<EntityStore, TitanBoulderComponent> boulderComponentType;
    private static ComponentType<EntityStore, TitanBoulderPartComponent> boulderPartComponentType;
    private static ComponentType<EntityStore, TitanBrainComponent> brainComponentType;

    private TitanRegistry() {
    }

    /**
     * Registers every titan component. All of them are runtime-only: a titan is a cluster of entities wired
     * together by references, so persisting one half-built would leave orphaned voxels behind. Boulders are
     * the same arrangement in miniature and live for seconds.
     */
    public static void register(@Nonnull final IComponentRegistry<EntityStore> registry) {
        titanComponentType = registry.registerComponent(TitanComponent.class, TitanComponent::new);
        partComponentType = registry.registerComponent(TitanPartComponent.class, TitanPartComponent::new);
        weakpointComponentType = registry.registerComponent(TitanWeakpointComponent.class, TitanWeakpointComponent::new);
        boulderComponentType = registry.registerComponent(TitanBoulderComponent.class, TitanBoulderComponent::new);
        boulderPartComponentType =
            registry.registerComponent(TitanBoulderPartComponent.class, TitanBoulderPartComponent::new);
        brainComponentType = registry.registerComponent(TitanBrainComponent.class, TitanBrainComponent::new);
    }

    @Nonnull
    public static ComponentType<EntityStore, TitanComponent> getTitanComponentType() {
        return require(titanComponentType);
    }

    @Nonnull
    public static ComponentType<EntityStore, TitanPartComponent> getPartComponentType() {
        return require(partComponentType);
    }

    @Nonnull
    public static ComponentType<EntityStore, TitanWeakpointComponent> getWeakpointComponentType() {
        return require(weakpointComponentType);
    }

    @Nonnull
    public static ComponentType<EntityStore, TitanBoulderComponent> getBoulderComponentType() {
        return require(boulderComponentType);
    }

    @Nonnull
    public static ComponentType<EntityStore, TitanBoulderPartComponent> getBoulderPartComponentType() {
        return require(boulderPartComponentType);
    }

    @Nonnull
    public static ComponentType<EntityStore, TitanBrainComponent> getBrainComponentType() {
        return require(brainComponentType);
    }

    @Nonnull
    private static <T> T require(final T value) {
        if (value == null) throw new IllegalStateException("Titan components have not been registered yet");
        return value;
    }
}
