package com.hexvane.titan.command;

import com.hexvane.titan.anim.TitanClipLibrary;
import com.hexvane.titan.asset.TitanClipSetAsset;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanState;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * {@code /titan anim <clip> [--state=NAME] [--radius=n]}: previews a clip on the nearest titan.
 *
 * <p>Development aid for authoring {@code .blockyanim} files. The clip cache is dropped on every run, so a
 * re-export from Blockbench is picked up without restarting the server.
 */
public final class TitanAnimCommand extends AbstractPlayerCommand {

    @Nonnull
    private final RequiredArg<String> clipArg =
        withRequiredArg("clip", "titan_commands.commands.titan.anim.clip.desc", ArgTypes.STRING);
    @Nonnull
    private final OptionalArg<TitanState> stateArg =
        withOptionalArg("state", "titan_commands.commands.titan.anim.state.desc", ArgTypes.forEnum("state", TitanState.class));
    @Nonnull
    private final DefaultArg<Double> radiusArg = withDefaultArg(
        "radius", "titan_commands.commands.titan.anim.radius.desc", ArgTypes.DOUBLE, 128.0, "128");

    public TitanAnimCommand() {
        super("anim", "titan_commands.commands.titan.anim.desc");
    }

    @Override
    protected void execute(@Nonnull final CommandContext context,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final Ref<EntityStore> ref,
                           @Nonnull final PlayerRef playerRef,
                           @Nonnull final World world) {

        final var transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        final Ref<EntityStore> titanRef = TitanCommandUtil.findNearest(store, transform.getPosition(), radiusArg.get(context));
        if (titanRef == null) {
            context.sendMessage(Message.translation("titan_commands.commands.titan.noneNearby"));
            return;
        }

        final var titan = store.getComponent(titanRef, TitanComponent.getComponentType());
        final var skeleton = titan == null ? null : titan.getSkeleton();
        final var animator = titan == null ? null : titan.getAnimator();
        if (titan == null || skeleton == null || animator == null) return;

        // Force a re-read so an edited .blockyanim shows up immediately.
        TitanClipLibrary.invalidate();

        final String clipName = clipArg.get(context);
        final var clip = TitanClipLibrary.get(skeleton, clipName);
        if (clip == null) {
            final var clipSet = TitanClipSetAsset.find(skeleton.getClipSet());
            context.sendMessage(Message.translation("titan_commands.commands.titan.anim.unknownClip")
                .param("clip", clipName)
                .param("known", clipSet == null ? "<no clip set>" : String.join(", ", clipSet.getAnimations().keySet())));
            return;
        }

        if (stateArg.provided(context)) {
            // Changing state marks the clip dirty, so set it first and let the override win afterwards.
            titan.setState(stateArg.get(context));
            titan.consumeClipDirty();
        }
        animator.play(clip, true);

        context.sendMessage(Message.translation("titan_commands.commands.titan.anim.playing")
            .param("clip", clipName)
            .param("duration", clip.getDuration())
            .param("state", titan.getState().name()));
    }
}
