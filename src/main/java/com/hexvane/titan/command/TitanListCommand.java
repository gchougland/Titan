package com.hexvane.titan.command;

import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.entity.TitanComponent;
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
import com.hypixel.hytale.server.core.util.TargetUtil;

import javax.annotation.Nonnull;
import java.util.ArrayList;

/**
 * {@code /titan list [--radius=n]}: nearby titans and their state, plus every variant the server knows.
 */
public final class TitanListCommand extends AbstractPlayerCommand {

    @Nonnull
    private final DefaultArg<Double> radiusArg = withDefaultArg(
        "radius", "titan_commands.commands.titan.list.radius.desc", ArgTypes.DOUBLE, 128.0, "128");

    public TitanListCommand() {
        super("list", "titan_commands.commands.titan.list.desc");
    }

    @Override
    protected void execute(@Nonnull final CommandContext context,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final Ref<EntityStore> ref,
                           @Nonnull final PlayerRef playerRef,
                           @Nonnull final World world) {

        context.sendMessage(Message.translation("titan_commands.commands.titan.list.variants")
            .param("variants", String.join(", ", TitanVariantAsset.ASSET_MAP.getAssetMap().keySet())));

        final var transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        final var origin = transform.getPosition();
        final var candidates = new ArrayList<>(TargetUtil.getAllEntitiesInSphere(origin, radiusArg.get(context), store));

        int found = 0;
        for (final Ref<EntityStore> candidate : candidates) {
            if (!candidate.isValid()) continue;
            final var titan = store.getComponent(candidate, TitanComponent.getComponentType());
            if (titan == null) continue;

            final var titanTransform = store.getComponent(candidate, TransformComponent.getComponentType());
            if (titanTransform == null) continue;

            found++;
            context.sendMessage(Message.translation("titan_commands.commands.titan.list.entry")
                .param("variant", String.valueOf(titan.getVariantId()))
                .param("state", titan.getState().name())
                .param("clip", titan.getAnimator() == null ? "<none>" : titan.getAnimator().getCurrentName())
                .param("weakpoints", titan.getWeakpointsRemaining())
                .param("total", titan.getWeakpointsTotal())
                .param("toKill", titan.getWeakpointsStillNeeded())
                .param("distance", origin.distance(titanTransform.getPosition())));
        }

        context.sendMessage(Message.translation("titan_commands.commands.titan.list.count").param("count", found));
    }
}
