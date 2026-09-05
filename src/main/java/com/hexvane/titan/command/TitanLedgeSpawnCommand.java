package com.hexvane.titan.command;

import com.hexvane.titan.ledge.TitanLedge;
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

/** {@code /titan ledge spawn} — one grab nub ahead of the caller. */
public final class TitanLedgeSpawnCommand extends AbstractPlayerCommand {

    public TitanLedgeSpawnCommand() {
        super("spawn", "titan_commands.commands.titan.ledge.spawn.desc");
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
        // Face the player: nub looks back at the caller.
        final float ledgeYaw = (float) (yaw + Math.PI);
        final var position = new Vector3d(
            transform.getPosition().x - Math.sin(yaw) * TitanLedge.SPAWN_AHEAD,
            transform.getPosition().y + 1.5,
            transform.getPosition().z - Math.cos(yaw) * TitanLedge.SPAWN_AHEAD);

        final Ref<EntityStore> ledge = TitanLedgeSpawner.spawn(store, position, ledgeYaw);
        if (ledge == null) {
            context.sendMessage(Message.translation("titan_commands.commands.titan.ledge.failed"));
            return;
        }

        context.sendMessage(Message.translation("titan_commands.commands.titan.ledge.spawn.success")
            .param("x", position.x)
            .param("y", position.y)
            .param("z", position.z));
    }
}
