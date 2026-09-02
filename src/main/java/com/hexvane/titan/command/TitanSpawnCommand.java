package com.hexvane.titan.command;

import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.spawn.ColliderMode;
import com.hexvane.titan.spawn.TitanSpawner;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.RelativeDoublePosition;
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
 * {@code /titan spawn <variant> [--position=x y z] [--yaw=degrees] [--colliders=none|top|all]}
 */
public final class TitanSpawnCommand extends AbstractPlayerCommand {

    /** How far in front of the caller a titan spawns, in blocks, so it does not appear on top of them. */
    private static final double SPAWN_AHEAD = 12.0;

    @Nonnull
    private static final List<String> COLLIDER_MODES = Arrays.stream(ColliderMode.values())
        .map(ColliderMode::argument)
        .toList();

    @Nonnull
    private static final SingleArgumentType<String> VARIANT =
        TitanCommandUtil.suggesting(TitanCommandUtil::enabledVariants);
    @Nonnull
    private static final SingleArgumentType<String> COLLIDERS =
        TitanCommandUtil.suggesting(() -> COLLIDER_MODES);

    @Nonnull
    private final RequiredArg<String> variantArg =
        withRequiredArg("variant", "titan_commands.commands.titan.spawn.variant.desc", VARIANT);
    @Nonnull
    private final OptionalArg<RelativeDoublePosition> positionArg =
        withOptionalArg("position", "titan_commands.commands.titan.spawn.position.desc", ArgTypes.RELATIVE_POSITION);
    @Nonnull
    private final OptionalArg<Float> yawArg =
        withOptionalArg("yaw", "titan_commands.commands.titan.spawn.yaw.desc", ArgTypes.FLOAT);
    @Nonnull
    private final OptionalArg<String> collidersArg =
        withOptionalArg("colliders", "titan_commands.commands.titan.spawn.colliders.desc", COLLIDERS);

    public TitanSpawnCommand() {
        super("spawn", "titan_commands.commands.titan.spawn.desc");
    }

    @Override
    protected void execute(@Nonnull final CommandContext context,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final Ref<EntityStore> ref,
                           @Nonnull final PlayerRef playerRef,
                           @Nonnull final World world) {

        final String variantId = variantArg.get(context);
        if (TitanVariantAsset.find(variantId) == null) {
            context.sendMessage(Message.translation("titan_commands.commands.titan.unknownVariant")
                .param("variant", variantId)
                .param("known", String.join(", ", TitanVariantAsset.ASSET_MAP.getAssetMap().keySet())));
            return;
        }

        ColliderMode colliderMode = ColliderMode.DEFAULT;
        if (collidersArg.provided(context)) {
            colliderMode = ColliderMode.parse(collidersArg.get(context));
            if (colliderMode == null) {
                context.sendMessage(Message.translation("titan_commands.commands.titan.spawn.badColliders")
                    .param("value", collidersArg.get(context))
                    .param("known", Arrays.stream(ColliderMode.values())
                        .map(ColliderMode::argument)
                        .collect(Collectors.joining(", "))));
                return;
            }
        }

        final var transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        final float yaw = yawArg.provided(context)
            ? (float) Math.toRadians(yawArg.get(context))
            : transform.getRotation().yaw();

        final Vector3d position = positionArg.provided(context)
            ? positionArg.get(context).getRelativePosition(context, world, store)
            : aheadOf(transform.getPosition(), transform.getRotation().yaw());

        // Turned to face the caller rather than away, so a spawned titan is looking at whoever summoned it.
        final var result = TitanSpawner.spawn(store, variantId, position, (float) (yaw + Math.PI), colliderMode);

        if (!result.ok()) {
            context.sendMessage(Message.translation("titan_commands.commands.titan.spawn.failed")
                .param("error", String.valueOf(result.error())));
            return;
        }

        context.sendMessage(Message.translation("titan_commands.commands.titan.spawn.success")
            .param("variant", variantId)
            .param("parts", result.parts())
            .param("weakpoints", result.weakpoints())
            .param("colliders", colliderMode.argument())
            .param("x", position.x)
            .param("y", position.y)
            .param("z", position.z));
    }

    @Nonnull
    private static Vector3d aheadOf(@Nonnull final Vector3d origin, final float yaw) {
        return new Vector3d(
            origin.x - Math.sin(yaw) * SPAWN_AHEAD,
            origin.y,
            origin.z - Math.cos(yaw) * SPAWN_AHEAD);
    }
}
