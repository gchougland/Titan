package com.hexvane.titan.command;

import com.hexvane.titan.anim.TitanClip;
import com.hexvane.titan.anim.TitanClipLibrary;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanState;
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
 * {@code /titan dance [--radius=n]}: plays a stock player emote on the nearest titan. Development aid for
 * checking that clips authored for a character retarget onto a titan skeleton.
 *
 * <p>The clip is the unmodified vanilla {@code Dance_Boogie.blockyanim}; it plays because titan skeletons
 * name their bones after the player rig. The titan is held in {@link TitanState#EMOTING} for the duration
 * so the AI stops steering it and the IK stops planting its feet, and it stays there until
 * {@code /titan anim} sets another state, it is killed, or the server restarts.
 */
public final class TitanDanceCommand extends AbstractPlayerCommand {

    @Nonnull
    private static final String CLIP = "Dance";

    @Nonnull
    private final DefaultArg<Double> radiusArg = withDefaultArg(
        "radius", "titan_commands.commands.titan.dance.radius.desc", ArgTypes.DOUBLE, 128.0, "128");

    public TitanDanceCommand() {
        super("dance", "titan_commands.commands.titan.dance.desc");
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

        final var clip = TitanClipLibrary.get(skeleton, CLIP);
        if (clip == null) {
            context.sendMessage(Message.translation("titan_commands.commands.titan.dance.unavailable")
                .param("skeleton", skeleton.getId()));
            return;
        }

        // Entering a state marks the clip dirty, so the animation system would overwrite the dance on the
        // next tick. Consume the flag before handing the animator the emote.
        titan.setState(TitanState.EMOTING);
        titan.consumeClipDirty();
        animator.play(clip, true);

        context.sendMessage(Message.translation("titan_commands.commands.titan.dance.playing")
            .param("bones", countDrivenBones(clip))
            .param("total", clip.getBoneCount())
            .param("duration", clip.getDuration()));
    }

    /** @return how many of the clip's bones have a track, out of {@link TitanClip#getBoneCount()}. */
    private static int countDrivenBones(@Nonnull final TitanClip clip) {
        int driven = 0;
        for (int i = 0; i < clip.getBoneCount(); i++) {
            if (clip.getTrack(i) != null) driven++;
        }
        return driven;
    }
}
