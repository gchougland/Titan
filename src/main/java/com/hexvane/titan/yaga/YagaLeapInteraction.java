package com.hexvane.titan.yaga;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;

/**
 * The use key, held with the wand: tells the player's house to leap.
 *
 * <p>Registered as the interaction type {@code YagaLeap} and named by the wand item's {@code Use}
 * interaction, which is the only supported way of hearing about that key. It carries no target — the point
 * of the wand is that a house too far off to click on still answers — so unlike everything else the mod
 * hangs off an interaction, there is no {@code UseEntityEvent} to listen for and the work has to be done
 * from inside the chain itself.
 *
 * <p>Nothing here knows which house is meant, and it does not need to: the ask is left against the player
 * for {@link YagaPetSystem} to collect on the next tick, when it has a house in hand and can decide
 * whether that house is theirs, is grown enough to leap, and is standing on something to leap off.
 */
public final class YagaLeapInteraction extends SimpleInstantInteraction {

    /** The name this is declared under in the item asset. */
    @Nonnull
    public static final String TYPE = "YagaLeap";

    @Nonnull
    public static final BuilderCodec<YagaLeapInteraction> CODEC = BuilderCodec
        .builder(YagaLeapInteraction.class, YagaLeapInteraction::new, SimpleInstantInteraction.CODEC)
        .documentation("Tells the Baba Yaga house belonging to whoever is holding the wand to leap forward.")
        .build();

    @Override
    protected void firstRun(@Nonnull final InteractionType type,
                            @Nonnull final InteractionContext context,
                            @Nonnull final CooldownHandler cooldownHandler) {

        final var commandBuffer = context.getCommandBuffer();
        if (commandBuffer == null) return;

        final var playerRef = commandBuffer.getComponent(context.getEntity(), PlayerRef.getComponentType());
        if (playerRef == null) return;

        final var uuid = playerRef.getUuid();
        if (uuid != null) YagaWand.leap(uuid);
    }

    @Nonnull
    @Override
    public String toString() {
        return "YagaLeapInteraction{} " + super.toString();
    }
}
