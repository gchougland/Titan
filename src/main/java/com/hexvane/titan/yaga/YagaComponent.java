package com.hexvane.titan.yaga;

import com.hexvane.titan.TitanRegistry;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

/**
 * What a Baba Yaga house is beyond the titan it is built on: whose it is, what it has been told to do, and
 * what is in its cupboards.
 *
 * <p>Sits on the titan root next to {@link com.hexvane.titan.entity.TitanComponent}, which owns the body.
 * Runtime-only like the rest of the cluster; {@link YagaMemory} is what actually survives a restart, and
 * this is rebuilt from it.
 */
public final class YagaComponent implements Component<EntityStore> {

    /** How grown the house is. Each stage is a different variant with its own prefabs and fixtures. */
    public enum Stage {
        /** Unhatched, sitting in its nest. Not a pet yet and has no owner. */
        EGG,
        BABY,
        BABA;

        @Nonnull
        private static final Stage[] VALUES = values();

        /** Variant id for this stage. */
        @Nonnull
        public String variantId() {
            return switch (this) {
                case EGG -> "Yaga_Egg";
                case BABY -> "Yaga_Baby";
                case BABA -> "Yaga_Baba";
            };
        }

        /**
         * The stage a variant names, or {@code null} for a titan that is not a Baba Yaga.
         *
         * <p>Matching on the variant id rather than on a component is what lets the egg do without one: it
         * has no owner, no mode and no cupboards, so there would be nothing in it to hold.
         */
        @Nullable
        public static Stage of(@Nullable final String variantId) {
            if (variantId == null) return null;
            for (final Stage stage : VALUES) {
                if (stage.variantId().equals(variantId)) return stage;
            }
            return null;
        }

        /** The name {@code /titan yaga spawn} takes. */
        @Nonnull
        public String argument() {
            return name().toLowerCase(Locale.ROOT);
        }

        @Nullable
        public static Stage parse(@Nullable final String argument) {
            if (argument == null) return null;
            for (final Stage stage : VALUES) {
                if (stage.argument().equalsIgnoreCase(argument)) return stage;
            }
            return null;
        }

        /** The stage this one grows into, or {@code null} once it is fully grown. */
        @Nullable
        public Stage next() {
            return switch (this) {
                case EGG -> BABY;
                case BABY -> BABA;
                case BABA -> null;
            };
        }
    }

    /** What the house has been told to do. Toggled by using the body. */
    public enum Mode {
        /** Walks after its owner and stops short of them. */
        FOLLOW,
        /** Folds its legs and stays put, low enough to climb onto. */
        RESTING
    }

    @Nonnull
    public static ComponentType<EntityStore, YagaComponent> getComponentType() {
        return TitanRegistry.getYagaComponentType();
    }

    @Nonnull
    private UUID houseId = UUID.randomUUID();
    @Nullable
    private UUID ownerUuid;
    @Nonnull
    private Stage stage = Stage.BABY;
    @Nonnull
    private Mode mode = Mode.FOLLOW;
    private float crouch;
    @Nullable
    private YagaFurnace furnace;
    @Nonnull
    private SimpleItemContainer[] inventories = new SimpleItemContainer[0];
    private boolean leaping;
    private double lift;
    private float leapYaw;

    /** For the component registry. */
    public YagaComponent() {
    }

    public YagaComponent(@Nonnull final Stage stage, @Nullable final UUID ownerUuid) {
        this.stage = stage;
        this.ownerUuid = ownerUuid;
    }

    /**
     * Which house this is, for as long as it exists anywhere — including in the save file.
     *
     * <p>Identity has to be the house's own rather than its owner's, because a player can crack a second
     * egg. Keyed by owner, the second house would quietly overwrite the first one's record and the first
     * would never come back. It also survives an upgrade: the grown house is a different entity carrying
     * the same id, so it goes on updating the same record instead of orphaning it.
     */
    @Nonnull
    public UUID getHouseId() {
        return houseId;
    }

    /** Adopts the id of the record a house is being rebuilt from. */
    public void setHouseId(@Nonnull final UUID houseId) {
        this.houseId = houseId;
    }

    /**
     * Whoever cracked the egg, or {@code null} while it is still an egg.
     *
     * <p>Only the owner may rest it, open its chests or point it anywhere. Held as a UUID rather than a reference
     * because the owner logs out and comes back as a different entity, while the house stays where it was.
     */
    @Nullable
    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(@Nullable final UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public boolean isOwner(@Nullable final UUID uuid) {
        return ownerUuid != null && ownerUuid.equals(uuid);
    }

    @Nonnull
    public Stage getStage() {
        return stage;
    }

    @Nonnull
    public Mode getMode() {
        return mode;
    }

    public void setMode(@Nonnull final Mode mode) {
        this.mode = mode;
    }

    /**
     * How far into the resting crouch it is, {@code 0} stood up and {@code 1} fully folded.
     *
     * <p>Ramped rather than switched so the house lowers itself over a second or so. The gait planner is
     * still stepping the feet while it moves, and a body that dropped in one tick would leave the legs
     * behind it.
     */
    public float getCrouch() {
        return crouch;
    }

    public void setCrouch(final float crouch) {
        this.crouch = Math.max(0f, Math.min(1f, crouch));
    }

    /**
     * The house's furnace, or {@code null} at a stage that has none.
     *
     * <p>Kept apart from the chests above, though it holds items too. Those are plain containers the house
     * only has to hand out; this is a working furnace with a fire, a recipe and slots that refuse what does
     * not belong in them, and all of that is the engine's to look after rather than this component's.
     *
     * @see YagaFurnace
     */
    @Nullable
    public YagaFurnace getFurnace() {
        return furnace;
    }

    public void setFurnace(@Nullable final YagaFurnace furnace) {
        this.furnace = furnace;
    }

    /**
     * Whether the house is in the air on a leap of its own.
     *
     * <p>While it is, the body is on a ballistic arc rather than resting on the terrain under it, and the
     * usual settling onto the ground is suspended: the two disagree by definition, and the ground wins
     * every time, which would land the house in the same tick it took off.
     */
    public boolean isLeaping() {
        return leaping;
    }

    /** How fast the body is rising, in blocks per second, negative on the way down. */
    public double getLift() {
        return lift;
    }

    public void setLift(final double lift) {
        this.lift = lift;
    }

    /**
     * The heading the leap was launched on, in radians.
     *
     * <p>Kept rather than read off the body each tick so a leap goes where it was aimed. The owner can turn
     * the wand while the house is in the air, and a house that followed it would curve mid-jump and land
     * somewhere nobody chose.
     */
    public float getLeapYaw() {
        return leapYaw;
    }

    /** Sends the house into the air at {@code lift} blocks per second, heading {@code yaw}. */
    public void leap(final double lift, final float yaw) {
        this.leaping = true;
        this.lift = lift;
        this.leapYaw = yaw;
    }

    /** Puts the house back on the ground, ending the arc. */
    public void land() {
        this.leaping = false;
        this.lift = 0;
    }

    /**
     * Sizes the container set to what this stage's fixtures ask for, keeping whatever was already there.
     *
     * <p>Called on spawn and again on an upgrade, when the same house grows from one small chest to two
     * large ones. Existing contents are carried over slot for slot, which is what makes an upgrade keep
     * the player's things without any copying at the call site.
     */
    public void resize(@Nonnull final int[] capacities) {
        final var next = new SimpleItemContainer[capacities.length];
        for (int i = 0; i < capacities.length; i++) {
            final var container = new SimpleItemContainer((short) Math.max(1, capacities[i]));
            if (i < inventories.length) YagaInventory.transfer(inventories[i], container);
            next[i] = container;
        }
        inventories = next;
    }

    /** @return the container at {@code index}, or {@code null} if this stage has no such chest. */
    @Nullable
    public SimpleItemContainer inventory(final int index) {
        return index >= 0 && index < inventories.length ? inventories[index] : null;
    }

    @Nonnull
    public SimpleItemContainer[] getInventories() {
        return inventories;
    }

    public void setInventories(@Nonnull final SimpleItemContainer[] inventories) {
        this.inventories = inventories;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        // Deliberately shallow on the containers: cloning is the engine copying an entity, and two houses
        // sharing one set of chests is a far safer failure than duplicating whatever is inside them.
        final var copy = new YagaComponent(stage, ownerUuid);
        copy.houseId = houseId;
        copy.mode = mode;
        copy.crouch = crouch;
        copy.inventories = inventories;
        copy.furnace = furnace;
        return copy;
    }
}
