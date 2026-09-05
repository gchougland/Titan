package com.hexvane.titan.yaga;

import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One player's Baba Yaga house, as much of it as is worth keeping.
 *
 * <p>Everything here is something a player did: they cracked an egg, they grew it, they told it to sit
 * down somewhere, they put things in its chests. The body is not here at all — the eight hundred odd
 * blocks it is made of come back out of the prefabs, the same reasoning
 * {@link com.hexvane.titan.entity.TitanComponent} gives for staying runtime-only.
 *
 * <p>Keyed by the house rather than by its owner or its position, both of which change: a player can crack
 * a second egg, and a house walks.
 */
public final class YagaRecord {

    @Nonnull
    public static final BuilderCodec<YagaRecord> CODEC = BuilderCodec.builder(YagaRecord.class, YagaRecord::new)
        .append(
            new KeyedCodec<>("Id", Codec.STRING, true),
            (o, v) -> o.id = v,
            o -> o.id
        ).add()
        .append(
            new KeyedCodec<>("Owner", Codec.STRING, true),
            (o, v) -> o.owner = v,
            o -> o.owner
        ).add()
        .append(
            new KeyedCodec<>("Stage", Codec.STRING, true),
            (o, v) -> o.stage = v,
            o -> o.stage
        ).add()
        .append(
            new KeyedCodec<>("X", Codec.DOUBLE, true),
            (o, v) -> o.x = v,
            o -> o.x
        ).add()
        .append(
            new KeyedCodec<>("Y", Codec.DOUBLE, true),
            (o, v) -> o.y = v,
            o -> o.y
        ).add()
        .append(
            new KeyedCodec<>("Z", Codec.DOUBLE, true),
            (o, v) -> o.z = v,
            o -> o.z
        ).add()
        .append(
            new KeyedCodec<>("Yaw", Codec.FLOAT, true),
            (o, v) -> o.yaw = v,
            o -> o.yaw
        ).add()
        .append(
            new KeyedCodec<>("Resting", Codec.BOOLEAN, true),
            (o, v) -> o.resting = v,
            o -> o.resting
        ).add()
        .append(
            // The stock container codec, so a chest full of a modded item written by some other pack comes
            // back exactly as that pack would have written it.
            new KeyedCodec<>("Inventories", new ArrayCodec<>(SimpleItemContainer.CODEC, SimpleItemContainer[]::new)),
            (o, v) -> o.inventories = v,
            o -> o.inventories
        ).add()
        .append(
            // The furnace's own codec, the same one the engine writes into a chunk for a furnace block, so
            // what comes back is a furnace mid-smelt rather than three containers and a guess.
            new KeyedCodec<>("Furnace", ProcessingBenchBlock.CODEC),
            (o, v) -> o.furnace = v,
            o -> o.furnace
        ).add()
        .build();

    @Nonnull
    private String id = "";
    @Nonnull
    private String owner = "";
    @Nonnull
    private String stage = YagaComponent.Stage.BABY.name();
    private double x;
    private double y;
    private double z;
    private float yaw;
    private boolean resting;
    @Nonnull
    private SimpleItemContainer[] inventories = new SimpleItemContainer[0];
    @Nullable
    private ProcessingBenchBlock furnace;

    /** For the codec. */
    public YagaRecord() {
    }

    public YagaRecord(@Nonnull final UUID id,
                      @Nonnull final UUID owner,
                      @Nonnull final YagaComponent.Stage stage,
                      @Nonnull final Vector3d position,
                      final float yaw,
                      final boolean resting,
                      @Nonnull final SimpleItemContainer[] inventories,
                      @Nullable final ProcessingBenchBlock furnace) {

        this.id = id.toString();
        this.owner = owner.toString();
        this.stage = stage.name();
        this.x = position.x;
        this.y = position.y;
        this.z = position.z;
        this.yaw = yaw;
        this.resting = resting;
        this.inventories = inventories;
        this.furnace = furnace;
    }

    /** Which house this is, or {@code null} if the saved text will not parse. */
    @Nullable
    public UUID id() {
        return parse(id);
    }

    /**
     * The owner's account UUID, or {@code null} if the saved text will not parse.
     *
     * <p>A record whose owner cannot be read is a record that can never be matched to a player, so callers
     * drop it rather than keeping a house nobody can ever be given.
     */
    @Nullable
    public UUID ownerUuid() {
        return parse(owner);
    }

    @Nullable
    private static UUID parse(@Nonnull final String text) {
        try {
            return UUID.fromString(text);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The stage to rebuild at, or {@code null} for a stage this version no longer has.
     *
     * <p>{@link YagaComponent.Stage#EGG} counts as unreadable here too: an egg is not owned and never
     * produces a record, so one naming it has been tampered with.
     */
    @Nullable
    public YagaComponent.Stage stage() {
        for (final YagaComponent.Stage candidate : YagaComponent.Stage.values()) {
            if (candidate != YagaComponent.Stage.EGG && candidate.name().equals(stage)) return candidate;
        }
        return null;
    }

    @Nonnull
    public Vector3d position() {
        return new Vector3d(x, y, z);
    }

    public float yaw() {
        return yaw;
    }

    public boolean isResting() {
        return resting;
    }

    @Nonnull
    public SimpleItemContainer[] getInventories() {
        return inventories;
    }

    /** The furnace as it was left, or {@code null} for a house whose furnace was never lit. */
    @Nullable
    public ProcessingBenchBlock getFurnace() {
        return furnace;
    }
}
