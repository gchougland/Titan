package com.hexvane.titan.asset;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A block of a titan's geometry that a player can use, rather than only walk on.
 *
 * <p>Matched by block key, so a fixture is authored by putting the block in the prefab and naming it here.
 * A door, a chest or a workbench in the prefab is one anchor voxel plus filler references, and the spawner
 * only ever builds the anchor, so each entry resolves to exactly one entity per copy of the block.
 *
 * <p>{@code EnumCodec} spells constants in CamelCase, so JSON writes {@code "Chest"}, {@code "Workbench"}
 * and so on.
 */
public final class TitanFixtureDef {

    /** What using the fixture does. The behaviour itself lives in the systems that read this. */
    public enum Kind {
        /** Swings between its open and closed block states. */
        DOOR,
        /** Opens one of the titan's own item containers. */
        CHEST,
        /** Skips the night, exactly as a bed placed in the world does. */
        BED,
        /** Opens a smelting UI backed by the titan. */
        FURNACE,
        /** Opens a crafting UI. */
        WORKBENCH
    }

    @Nonnull
    public static final BuilderCodec<TitanFixtureDef> CODEC = BuilderCodec.builder(TitanFixtureDef.class, TitanFixtureDef::new)
        .append(
            new KeyedCodec<>("Block", Codec.STRING, true),
            (o, v) -> o.block = v,
            o -> o.block
        ).add()
        .append(
            new KeyedCodec<>("Kind", new EnumCodec<>(Kind.class), true),
            (o, v) -> o.kind = v,
            o -> o.kind
        ).add()
        .append(
            new KeyedCodec<>("Capacity", Codec.INTEGER),
            (o, v) -> o.capacity = v,
            o -> o.capacity
        ).add()
        .append(
            new KeyedCodec<>("InventoryIndex", Codec.INTEGER),
            (o, v) -> o.inventoryIndex = v,
            o -> o.inventoryIndex
        ).add()
        .append(
            new KeyedCodec<>("Hint", Codec.STRING),
            (o, v) -> o.hint = v,
            o -> o.hint
        ).add()
        .append(
            new KeyedCodec<>("OpenHint", Codec.STRING),
            (o, v) -> o.openHint = v,
            o -> o.openHint
        ).add()
        .build();

    private String block;
    @Nonnull
    private Kind kind = Kind.CHEST;
    private int capacity = 36;
    private int inventoryIndex;
    @Nullable
    private String hint;
    @Nullable
    private String openHint;

    /** Block key that marks a voxel as this fixture. */
    @Nonnull
    public String getBlock() {
        return block;
    }

    /** What using it does. */
    @Nonnull
    public Kind getKind() {
        return kind;
    }

    /**
     * Slots in the container this fixture opens, for {@link Kind#CHEST} and {@link Kind#FURNACE}.
     *
     * <p>Rows are not a server-side concept; the client lays a container out nine slots wide, so four rows
     * is {@code 36} and eight rows is {@code 72}.
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Which of the titan's containers the first copy of this block opens.
     *
     * <p>A prefab may hold the same fixture block more than once — the Baba has two large chests — and each
     * copy wants its own contents. The spawner numbers the copies from here in a stable order, so two
     * chests declared at index {@code 0} become containers {@code 0} and {@code 1}.
     */
    public int getInventoryIndex() {
        return inventoryIndex;
    }

    /** Translation key for the prompt shown when the fixture is looked at. */
    @Nullable
    public String getHint() {
        return hint;
    }

    /**
     * Translation key for the prompt shown while a {@link Kind#DOOR} stands open, falling back to
     * {@link #getHint()}.
     *
     * <p>A door is the one fixture whose prompt has to change with its state: "open" and "close" are
     * different instructions, and a door that still offered to open itself once open would read as broken.
     */
    @Nullable
    public String getOpenHint() {
        return openHint == null ? hint : openHint;
    }
}
