package com.hexvane.titan;

import com.hexvane.titan.entity.TitanBoulderComponent;
import com.hexvane.titan.entity.TitanBoulderPartComponent;
import com.hexvane.titan.entity.TitanBrainComponent;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanFixtureComponent;
import com.hexvane.titan.entity.TitanPartComponent;
import com.hexvane.titan.entity.TitanShellComponent;
import com.hexvane.titan.entity.TitanSpawnFxComponent;
import com.hexvane.titan.entity.TitanWeakpointComponent;
import com.hexvane.titan.ledge.TitanLedgeCartComponent;
import com.hexvane.titan.ledge.TitanLedgeComponent;
import com.hexvane.titan.ledge.TitanLedgeHangComponent;
import com.hexvane.titan.yaga.YagaComponent;
import com.hexvane.titan.yaga.YagaEggSiteComponent;
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
    private static ComponentType<EntityStore, TitanFixtureComponent> fixtureComponentType;
    private static ComponentType<EntityStore, TitanShellComponent> shellComponentType;
    private static ComponentType<EntityStore, TitanSpawnFxComponent> spawnFxComponentType;
    private static ComponentType<EntityStore, YagaComponent> yagaComponentType;
    private static ComponentType<EntityStore, YagaEggSiteComponent> yagaEggSiteComponentType;
    private static ComponentType<EntityStore, TitanLedgeComponent> ledgeComponentType;
    private static ComponentType<EntityStore, TitanLedgeHangComponent> ledgeHangComponentType;
    private static ComponentType<EntityStore, TitanLedgeCartComponent> ledgeCartComponentType;

    private TitanRegistry() {
    }

    /**
     * Registers every titan component. All of them are runtime-only: a titan is a cluster of entities wired
     * together by references, so persisting one half-built would leave orphaned voxels behind. Boulders are
     * the same arrangement in miniature and live for seconds. The nest marker is the exception and is
     * codec-registered so worldgen nests remember whether they have already hatched.
     */
    public static void register(@Nonnull final IComponentRegistry<EntityStore> registry) {
        titanComponentType = registry.registerComponent(TitanComponent.class, TitanComponent::new);
        partComponentType = registry.registerComponent(TitanPartComponent.class, TitanPartComponent::new);
        weakpointComponentType = registry.registerComponent(TitanWeakpointComponent.class, TitanWeakpointComponent::new);
        boulderComponentType = registry.registerComponent(TitanBoulderComponent.class, TitanBoulderComponent::new);
        boulderPartComponentType =
            registry.registerComponent(TitanBoulderPartComponent.class, TitanBoulderPartComponent::new);
        brainComponentType = registry.registerComponent(TitanBrainComponent.class, TitanBrainComponent::new);
        fixtureComponentType = registry.registerComponent(TitanFixtureComponent.class, TitanFixtureComponent::new);
        shellComponentType = registry.registerComponent(TitanShellComponent.class, TitanShellComponent::new);
        spawnFxComponentType = registry.registerComponent(TitanSpawnFxComponent.class, TitanSpawnFxComponent::new);
        yagaComponentType = registry.registerComponent(YagaComponent.class, YagaComponent::new);
        yagaEggSiteComponentType = registry.registerComponent(
            YagaEggSiteComponent.class, YagaEggSiteComponent.ID, YagaEggSiteComponent.CODEC);
        ledgeComponentType = registry.registerComponent(TitanLedgeComponent.class, TitanLedgeComponent::new);
        ledgeHangComponentType = registry.registerComponent(TitanLedgeHangComponent.class, TitanLedgeHangComponent::new);
        ledgeCartComponentType = registry.registerComponent(TitanLedgeCartComponent.class, TitanLedgeCartComponent::new);
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
    public static ComponentType<EntityStore, TitanFixtureComponent> getFixtureComponentType() {
        return require(fixtureComponentType);
    }

    @Nonnull
    public static ComponentType<EntityStore, TitanShellComponent> getShellComponentType() {
        return require(shellComponentType);
    }

    @Nonnull
    public static ComponentType<EntityStore, TitanSpawnFxComponent> getSpawnFxComponentType() {
        return require(spawnFxComponentType);
    }

    @Nonnull
    public static ComponentType<EntityStore, YagaComponent> getYagaComponentType() {
        return require(yagaComponentType);
    }

    @Nonnull
    public static ComponentType<EntityStore, YagaEggSiteComponent> getYagaEggSiteComponentType() {
        return require(yagaEggSiteComponentType);
    }

    @Nonnull
    public static ComponentType<EntityStore, TitanLedgeComponent> getLedgeComponentType() {
        return require(ledgeComponentType);
    }

    @Nonnull
    public static ComponentType<EntityStore, TitanLedgeHangComponent> getLedgeHangComponentType() {
        return require(ledgeHangComponentType);
    }

    @Nonnull
    public static ComponentType<EntityStore, TitanLedgeCartComponent> getLedgeCartComponentType() {
        return require(ledgeCartComponentType);
    }

    @Nonnull
    private static <T> T require(final T value) {
        if (value == null) throw new IllegalStateException("Titan components have not been registered yet");
        return value;
    }
}
