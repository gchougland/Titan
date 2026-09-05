package com.hexvane.titan.yaga;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.ChargingInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

/**
 * A mouse button held with the wand: sends the player's house walking the way they are pointing.
 *
 * <p>Registered as the interaction type {@code YagaPoint} and named by both the wand's {@code Primary} and
 * its {@code Secondary}, so either button does it.
 *
 * <p>Built on charging, which is the engine's held input: a charge normally means a bow being drawn or a
 * shield being raised, and what those have in common with this is the only part being used — the client
 * reports that the button is still down, tick after tick, until it is let go. Holding indefinitely is
 * exactly what a shield does. The charge itself is thrown away; there is no bow to loose and no tier to
 * reach, and the whole of the interaction is the fact that it is still running.
 *
 * <p>Not the mouse events, which was the first attempt and did nothing: those come from a packet the
 * client only sends for some kinds of item, and a plain held button never produced one. Not a repeated
 * instant interaction either, which would poll the hold at whatever rate the client re-fires and give the
 * house a stutter to walk to.
 *
 * <p>Like the leap, this knows nothing about houses. It marks the player as pointing and leaves {@link
 * YagaPetSystem} to find out on the next tick whether they own anything that cares, which is also what
 * decides <em>where</em> — the heading is read fresh from the player every tick, so the house follows the
 * wand around as it swings rather than setting off on the bearing it had when the button went down.
 */
public final class YagaPointInteraction extends ChargingInteraction {

    /** The name this is declared under in the item asset. */
    @Nonnull
    public static final String TYPE = "YagaPoint";

    @Nonnull
    public static final BuilderCodec<YagaPointInteraction> CODEC = BuilderCodec
        .builder(YagaPointInteraction.class, YagaPointInteraction::new, ChargingInteraction.ABSTRACT_CODEC)
        .documentation("Walks the Baba Yaga house belonging to whoever holds the wand towards where they point it.")
        .afterDecode(interaction -> {
            // Held for as long as the player likes: there is nothing to charge up, and a house that stopped
            // on its own after a second or two would have to be told again for every stride.
            interaction.allowIndefiniteHold = true;

            // No charge bar over the hotbar. It would fill and sit there for the whole walk, saying nothing.
            interaction.displayProgress = false;
        })
        .build();

    @Override
    protected void tick0(final boolean firstRun,
                         final float time,
                         @Nonnull final InteractionType type,
                         @Nonnull final InteractionContext context,
                         @Nonnull final CooldownHandler cooldownHandler) {

        super.tick0(firstRun, time, type, context, cooldownHandler);

        final var commandBuffer = context.getCommandBuffer();
        if (commandBuffer == null) return;

        final var playerRef = commandBuffer.getComponent(context.getEntity(), PlayerRef.getComponentType());
        if (playerRef == null) return;

        final var uuid = playerRef.getUuid();
        if (uuid == null) return;

        // Renewed rather than set once, so the hold lapses on its own if the client stops saying anything:
        // this is the last word either way, since a chain that ends for any reason — released, cancelled,
        // interrupted, the player logging out from under it — ends up here with something other than
        // NotFinished, or stops arriving at all.
        if (context.getState().state == InteractionState.NotFinished) YagaWand.point(uuid);
        else YagaWand.release(uuid);
    }

    @Nonnull
    @Override
    public String toString() {
        return "YagaPointInteraction{} " + super.toString();
    }
}
