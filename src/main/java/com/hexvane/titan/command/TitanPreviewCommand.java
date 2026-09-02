package com.hexvane.titan.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentPrefabPreview;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * {@code /titan preview <prefab> [yaw] [layers]}
 *
 * <p>Development aid that stands one of a titan's prefabs up as a {@link PersistentPrefabPreview}, the
 * engine's hologram primitive. A preview is a single entity carrying a whole block list; the blocks are
 * immutable once resolved and a viewer is sent them once, on first seeing the entity, after which the
 * client re-meshes locally. Building bones this way would take the Roaming Temple from around 4,700
 * replicated transforms a tick to thirteen.
 *
 * <p>Whether that is usable turns on something the source cannot answer: previews exist to show a builder
 * where a paste will land, and a paste lands on the block grid, so the client may draw the blocks
 * axis-aligned and ignore the entity's rotation. A titan is articulated limbs and the Roaming Temple yaws
 * its whole body, so a mesh that cannot turn would only serve the parts that never move. The client in the
 * source drop is network interop bindings with no renderer, so the remaining way to answer it is to spawn
 * two of these at different yaws and compare; the engine's own {@code /prefabpreview spawn} hardcodes an
 * identity rotation. The same comparison shows whether the blocks draw solid or as a translucent ghost.
 * Either way a preview carries no collision, so climbable voxels would still be needed.
 *
 * <p>These are persistent entities and outlive a restart. Clean up with the engine's
 * {@code /prefabpreview remove --radius=64}.
 */
public final class TitanPreviewCommand extends AbstractPlayerCommand {

    /** How far ahead of the caller the hologram is placed, in blocks. A bone is tens of blocks across. */
    private static final double PREVIEW_DISTANCE = 24.0;

    @Nonnull
    private final RequiredArg<String> prefabArg =
        withRequiredArg("prefab", "titan_commands.commands.titan.preview.prefab.desc", ArgTypes.STRING);
    @Nonnull
    private final OptionalArg<Double> yawArg =
        withOptionalArg("yaw", "titan_commands.commands.titan.preview.yaw.desc", ArgTypes.DOUBLE);
    @Nonnull
    private final DefaultArg<Integer> layersArg = withDefaultArg(
        "layers", "titan_commands.commands.titan.preview.layers.desc", ArgTypes.INTEGER, Integer.MAX_VALUE, "all");

    public TitanPreviewCommand() {
        super("preview", "titan_commands.commands.titan.preview.desc");
    }

    @Override
    protected void execute(@Nonnull final CommandContext context,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final Ref<EntityStore> ref,
                           @Nonnull final PlayerRef playerRef,
                           @Nonnull final World world) {

        final var transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        final String prefab = prefabArg.get(context);
        // Checked here because the resolver only logs a console warning and then spawns an entity showing
        // nothing, which is indistinguishable from a preview that failed to draw.
        if (PrefabStore.get().findBrowsablePrefabPath(prefab) == null) {
            context.sendMessage(Message.translation("titan_commands.commands.titan.preview.unknownPrefab")
                .param("prefab", prefab));
            return;
        }

        final float facing = transform.getRotation().yaw();
        final var position = new Vector3d(transform.getPosition()).add(
            -Math.sin(facing) * PREVIEW_DISTANCE, 0, -Math.cos(facing) * PREVIEW_DISTANCE);

        final float yaw = yawArg.provided(context)
            ? (float) Math.toRadians(yawArg.get(context))
            : facing;

        PersistentPrefabPreview.spawn(store, position, new Rotation3f().set(0f, yaw, 0f), prefab, layersArg.get(context));

        context.sendMessage(Message.translation("titan_commands.commands.titan.preview.spawned")
            .param("prefab", prefab)
            .param("yaw", (float) Math.toDegrees(yaw))
            .param("distance", PREVIEW_DISTANCE));
    }
}
