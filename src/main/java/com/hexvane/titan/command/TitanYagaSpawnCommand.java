package com.hexvane.titan.command;

import com.hexvane.titan.yaga.YagaComponent;
import com.hexvane.titan.yaga.YagaSpawn;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code /titan yaga spawn <egg|baby|baba>}: builds a house at the named stage, owned by the caller.
 *
 * <p>{@code /titan spawn Yaga_Baby} reaches the same place, but by variant id and after a detour through
 * the generic spawner's adoption path. Naming the stage is what this is for: the stages are one creature
 * from the player's side and an egg, a hatchling and a house are how they are talked about.
 */
public final class TitanYagaSpawnCommand extends AbstractPlayerCommand {

    /** How far in front of the caller it appears, in blocks, so a grown house does not land on them. */
    private static final double SPAWN_AHEAD = 16.0;

    @Nonnull
    private static final List<String> STAGES = Arrays.stream(YagaComponent.Stage.values())
        .map(YagaComponent.Stage::argument)
        .toList();

    @Nonnull
    private final RequiredArg<String> stageArg = withRequiredArg(
        "stage", "titan_yaga.commands.titan.yaga.spawn.stage.desc",
        TitanCommandUtil.suggesting(() -> STAGES));

    public TitanYagaSpawnCommand() {
        super("spawn", "titan_yaga.commands.titan.yaga.spawn.desc");
    }

    @Override
    protected void execute(@Nonnull final CommandContext context,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final Ref<EntityStore> ref,
                           @Nonnull final PlayerRef playerRef,
                           @Nonnull final World world) {

        final YagaComponent.Stage stage = YagaComponent.Stage.parse(stageArg.get(context));
        if (stage == null) {
            context.sendMessage(Message.translation("titan_yaga.commands.titan.yaga.spawn.badStage")
                .param("value", stageArg.get(context))
                .param("known", STAGES.stream().collect(Collectors.joining(", "))));
            return;
        }

        final var transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        final float yaw = transform.getRotation().yaw();
        final Vector3d position = new Vector3d(
            transform.getPosition().x - Math.sin(yaw) * SPAWN_AHEAD,
            transform.getPosition().y,
            transform.getPosition().z - Math.cos(yaw) * SPAWN_AHEAD);

        // Turned to face the caller, and given to them. An egg takes no owner, so the uuid is ignored for
        // that stage and whoever cracks it gets it instead.
        final YagaSpawn.Result result =
            YagaSpawn.spawn(store, stage, position, (float) (yaw + Math.PI), playerRef.getUuid());

        if (!result.ok()) {
            context.sendMessage(Message.translation("titan_yaga.commands.titan.yaga.spawn.failed")
                .param("error", String.valueOf(result.error())));
            return;
        }

        context.sendMessage(Message.translation("titan_yaga.commands.titan.yaga.spawn.success")
            .param("stage", stage.argument())
            .param("x", position.x)
            .param("y", position.y)
            .param("z", position.z));
    }
}
