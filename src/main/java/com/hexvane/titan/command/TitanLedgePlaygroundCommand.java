package com.hexvane.titan.command;

import com.hexvane.titan.ledge.TitanLedgeSpawner;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/** {@code /titan ledge playground} — row, gap, and pull-up shelf for cling testing. */
public final class TitanLedgePlaygroundCommand extends AbstractPlayerCommand {

    /** How far ahead the playground centre sits, in blocks. */
    private static final double AHEAD = 4.0;

    public TitanLedgePlaygroundCommand() {
        super("playground", "titan_commands.commands.titan.ledge.playground.desc");
    }

    @Override
    protected void execute(@Nonnull final CommandContext context,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final Ref<EntityStore> ref,
                           @Nonnull final PlayerRef playerRef,
                           @Nonnull final World world) {

        final var transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        final float yaw = transform.getRotation().yaw();
        final float ledgeYaw = (float) (yaw + Math.PI);
        final var origin = new Vector3d(
            transform.getPosition().x - Math.sin(yaw) * AHEAD,
            transform.getPosition().y + 2.0,
            transform.getPosition().z - Math.cos(yaw) * AHEAD);

        final int count = TitanLedgeSpawner.spawnPlayground(store, origin, ledgeYaw);
        if (count <= 0) {
            context.sendMessage(Message.translation("titan_commands.commands.titan.ledge.failed"));
            return;
        }

        context.sendMessage(Message.translation("titan_commands.commands.titan.ledge.playground.success")
            .param("count", count)
            .param("x", origin.x)
            .param("y", origin.y)
            .param("z", origin.z));
    }
}
