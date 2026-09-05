package com.hexvane.titan.entity;

import com.hexvane.titan.TitanRegistry;
import com.hexvane.titan.asset.TitanFixtureDef;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Marks one voxel of a titan as something a player can use.
 *
 * <p>Sits alongside {@link TitanPartComponent} on the same entity: the fixture is a normal part of the body
 * that the skeleton moves about like any other, and this only records what using it means. It carries the
 * resolved answer rather than the asset it came from, because the system that handles a use has an entity
 * in hand and no cheap way back to the variant that spawned it.
 *
 * <p>Runtime-only, like every other titan component: fixtures are rebuilt from the prefabs on spawn, and
 * what survives a restart is the titan's own record rather than its blocks.
 */
public final class TitanFixtureComponent implements Component<EntityStore> {

    @Nonnull
    public static ComponentType<EntityStore, TitanFixtureComponent> getComponentType() {
        return TitanRegistry.getFixtureComponentType();
    }

    @Nullable
    private Ref<EntityStore> owner;
    @Nonnull
    private TitanFixtureDef.Kind kind = TitanFixtureDef.Kind.CHEST;
    private int inventoryIndex;
    @Nullable
    private String closedHint;
    @Nullable
    private String openHint;
    @Nullable
    private String closedBlock;
    @Nullable
    private String openBlock;
    private boolean open;
    @Nullable
    private String colliderConfigId;

    /** For the component registry. */
    public TitanFixtureComponent() {
    }

    public TitanFixtureComponent(@Nonnull final Ref<EntityStore> owner,
                                 @Nonnull final TitanFixtureDef.Kind kind,
                                 final int inventoryIndex,
                                 @Nullable final String closedHint,
                                 @Nullable final String openHint) {
        this.owner = owner;
        this.kind = kind;
        this.inventoryIndex = inventoryIndex;
        this.closedHint = closedHint;
        this.openHint = openHint;
    }

    /** The titan root this fixture belongs to. */
    @Nullable
    public Ref<EntityStore> getOwner() {
        return owner;
    }

    /** What using it does. */
    @Nonnull
    public TitanFixtureDef.Kind getKind() {
        return kind;
    }

    /** Which of the titan's containers this fixture opens, for the kinds that open one. */
    public int getInventoryIndex() {
        return inventoryIndex;
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * Whether this fixture is one that swings out of the way rather than opening a window.
     *
     * <p>A door with no open state to swing into is not one of them, and is left as scenery: without both
     * block states the swing has nothing to show, and a fixture that reports itself openable while looking
     * shut is worse than one that never offered.
     */
    public boolean canSwing() {
        return kind == TitanFixtureDef.Kind.DOOR && closedBlock != null && openBlock != null;
    }

    /** @return whether the door now stands open */
    public boolean toggleOpen() {
        open = !open;
        return open;
    }

    /**
     * The two block states this fixture wears, if it is a door.
     *
     * <p>A door's open and shut poses are two block types rather than two orientations of one — the leaf
     * hangs at ninety degrees in the open state's own model — so opening one means putting a different
     * block on the voxel. Both keys are resolved once, when the fixture is built, because the state
     * definitions are on the block's asset and looking them up per click would be a lookup per click.
     *
     * @see #blockFor
     */
    public void setBlocks(@Nullable final String closedBlock, @Nullable final String openBlock) {
        this.closedBlock = closedBlock;
        this.openBlock = openBlock;
    }

    /** The block key for the given state, or {@code null} if this fixture does not change block. */
    @Nullable
    public String blockFor(final boolean open) {
        return open ? openBlock : closedBlock;
    }

    /**
     * The collider the voxel had before its door was opened, so shutting it can put the same one back.
     *
     * <p>A door is made passable by taking its collider off outright, which is how the rig expresses a
     * voxel with no collision, and the component that carried it goes with it. Which collider it was is a
     * property of the skeleton rather than of the door, so it is noted down here on the way out rather than
     * being looked up again on the way back in.
     */
    @Nullable
    public String getColliderConfigId() {
        return colliderConfigId;
    }

    public void setColliderConfigId(@Nullable final String colliderConfigId) {
        this.colliderConfigId = colliderConfigId;
    }

    /**
     * The prompt for the fixture's current state.
     *
     * <p>Held here rather than looked up from the variant because the system that answers a use has the
     * voxel in hand and no cheap way back to the asset that spawned it.
     */
    @Nullable
    public String getHint() {
        return open ? openHint : closedHint;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        final var copy = new TitanFixtureComponent();
        copy.owner = owner;
        copy.kind = kind;
        copy.inventoryIndex = inventoryIndex;
        copy.closedHint = closedHint;
        copy.openHint = openHint;
        copy.closedBlock = closedBlock;
        copy.openBlock = openBlock;
        copy.open = open;
        copy.colliderConfigId = colliderConfigId;
        return copy;
    }
}
