package com.hexvane.titan.command;

import com.hexvane.titan.bootstrap.TitanBootstrap;
import com.hexvane.titan.yaga.YagaComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * {@code /titan yaga forget [--radius=n]}: deletes the nearest house and the record of it.
 *
 * <p>{@code /titan kill} would take the body down, but the house would be rebuilt from its saved record on
 * the next restart, chests and all. Forgetting it is the only way to be rid of one, which is what testing
 * needs and what a player who no longer wants theirs needs too.
 */
public final class TitanYagaForgetCommand extends AbstractPlayerCommand {

    @Nonnull
    private final DefaultArg<Double> radiusArg = withDefaultArg(
        "radius", "titan_yaga.commands.titan.yaga.radius.desc", ArgTypes.DOUBLE, 64.0, "64");

    public TitanYagaForgetCommand() {
        super("forget", "titan_yaga.commands.titan.yaga.forget.desc");
    }

    @Override
    protected void execute(@Nonnull final CommandContext context,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final Ref<EntityStore> ref,
                           @Nonnull final PlayerRef playerRef,
                           @Nonnull final World world) {

        final var transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        final Ref<EntityStore> root =
            TitanCommandUtil.findNearestYaga(store, transform.getPosition(), radiusArg.get(context));
        if (root == null) {
            context.sendMessage(Message.translation("titan_yaga.commands.titan.yaga.noneNearby"));
            return;
        }

        final var yaga = store.getComponent(root, YagaComponent.getComponentType());
        final var memoryType = TitanBootstrap.getYagaMemoryType();

        // Before the removal, because taking the body down is what normally triggers the record being
        // written back. Dropping the record first leaves nothing for that to update.
        if (yaga != null && memoryType != null) {
            store.getResource(memoryType).forget(yaga.getHouseId());
        }

        store.removeEntity(root, RemoveReason.REMOVE);
        context.sendMessage(Message.translation("titan_yaga.commands.titan.yaga.forget.success"));
    }
}
