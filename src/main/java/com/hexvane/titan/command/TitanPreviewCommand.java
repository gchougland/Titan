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
 * <p>Stands one of a titan's prefabs up as a prefab preview — the engine's own hologram primitive — so that
 * the one question standing between the mod and a very large saving can be answered by looking at it.
 *
 * <p>The saving: a {@code PrefabPreview} is a single entity carrying a whole block list. Its javadoc says
 * the blocks are immutable once resolved and a viewer is sent them once, on first seeing the entity, after
 * which the client re-meshes locally. One of those per bone would take the Roaming Temple from around 4,700
 * replicated transforms a tick to thirteen, which is not an optimisation of the current approach so much as
 * a different approach that happens not to have the problem.
 *
 * <p>The question: whether the client turns that cached mesh when the entity's rotation changes. Prefab
 * previews exist to show a builder where a paste will land, and a paste lands on the block grid, so there
 * is a fair chance the client draws the blocks axis-aligned and ignores rotation entirely. A titan is
 * articulated limbs; if the mesh cannot turn, this is only ever usable for parts that do not, and the
 * temple's body yaws along with everything else. None of that is answerable from this end — the client in
 * the source drop is network interop bindings with no renderer in it.
 *
 * <p>Why this rather than the engine's {@code /prefabpreview spawn}, which is otherwise the same thing:
 * that one hardcodes an identity rotation, so it cannot ask the question. Spawn two of these at different
 * yaws and the answer is whichever you see. Worth noting while looking: whether it draws solid or as a
 * translucent ghost, since a ghost titan is not a titan, and that a preview carries no collision at all, so
 * the climbable voxels would have to stay whatever happens.
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
        // Checked here rather than left to the resolver, which logs a warning to the console and spawns an
        // entity showing nothing at all — the most confusing possible outcome for a command whose entire
        // purpose is that you look at the result and believe what you see.
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
