package com.hexvane.titan.command;

import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanState;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.FlagArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import javax.annotation.Nonnull;
import java.util.ArrayList;

/**
 * {@code /titan kill [--radius=n] [--instant]}
 *
 * <p>Without {@code --instant} this plays the full death: the titan falls apart and drops its ore.
 */
public final class TitanKillCommand extends AbstractPlayerCommand {

    @Nonnull
    private final DefaultArg<Double> radiusArg = withDefaultArg(
        "radius", "titan_commands.commands.titan.kill.radius.desc", ArgTypes.DOUBLE, 64.0, "64");
    @Nonnull
    private final FlagArg instantArg = withFlagArg("instant", "titan_commands.commands.titan.kill.instant.desc");

    public TitanKillCommand() {
        super("kill", "titan_commands.commands.titan.kill.desc");
    }

    @Override
    protected void execute(@Nonnull final CommandContext context,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final Ref<EntityStore> ref,
                           @Nonnull final PlayerRef playerRef,
                           @Nonnull final World world) {

        final var transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        final boolean instant = instantArg.get(context);
        final var candidates = new ArrayList<>(
            TargetUtil.getAllEntitiesInSphere(transform.getPosition(), radiusArg.get(context), store));

        int killed = 0;
        for (final Ref<EntityStore> candidate : candidates) {
            if (!candidate.isValid()) continue;
            final var titan = store.getComponent(candidate, TitanComponent.getComponentType());
            if (titan == null || titan.getState() == TitanState.DYING) continue;

            if (instant) {
                store.removeEntity(candidate, RemoveReason.REMOVE);
            } else {
                titan.setState(TitanState.DYING);
            }
            killed++;
        }

        context.sendMessage(Message.translation("titan_commands.commands.titan.kill.result").param("count", killed));
    }
}
