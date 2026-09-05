package com.hexvane.titan.command;

import com.hexvane.titan.yaga.YagaComponent;
import com.hexvane.titan.yaga.YagaUpgrade;
import com.hypixel.hytale.component.Ref;
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
 * {@code /titan yaga upgrade [--radius=n]}: grows the nearest Baby Yaga into a Baba Yaga.
 *
 * <p>Stands in for the upgrade item until there is one. Both call {@code YagaUpgrade.apply}, so what the
 * item will do is exactly what this does.
 */
public final class TitanYagaUpgradeCommand extends AbstractPlayerCommand {

    @Nonnull
    private final DefaultArg<Double> radiusArg = withDefaultArg(
        "radius", "titan_yaga.commands.titan.yaga.radius.desc", ArgTypes.DOUBLE, 64.0, "64");

    public TitanYagaUpgradeCommand() {
        super("upgrade", "titan_yaga.commands.titan.yaga.upgrade.desc");
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
        if (yaga == null) return;

        // Checked here rather than left to the upgrade, so a fully grown house gets told what is wrong
        // instead of a generic failure.
        if (yaga.getStage() != YagaComponent.Stage.BABY) {
            context.sendMessage(Message.translation("titan_yaga.commands.titan.yaga.upgrade.notBaby"));
            return;
        }

        final YagaUpgrade.Result result = YagaUpgrade.apply(store, root);
        if (!result.ok()) {
            context.sendMessage(Message.translation("titan_yaga.commands.titan.yaga.upgrade.failed")
                .param("error", String.valueOf(result.error())));
            return;
        }

        if (result.dropped() > 0) {
            context.sendMessage(Message.translation("titan_yaga.commands.titan.yaga.upgrade.spilled")
                .param("dropped", result.dropped()));
            return;
        }

        context.sendMessage(Message.translation("titan_yaga.commands.titan.yaga.upgrade.success")
            .param("moved", result.moved())
            .param("total", result.total()));
    }
}
