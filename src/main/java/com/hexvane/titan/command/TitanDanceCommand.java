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
 * {@code /titan dance [--radius=n]} — makes the nearest titan perform a stock player emote.
 *
 * <p>The point of the command is the demonstration: the clip is the untouched vanilla
 * {@code Dance_Boogie.blockyanim}, authored for a character a fraction of a titan's size, and it plays
 * because titan skeletons name their bones after the player rig. Anything animated for a character can be
 * dropped into a clip set the same way.
 *
 * <p>The titan is parked in {@link TitanState#EMOTING} so the AI stops steering it and the IK stops
 * planting its feet, leaving the clip in sole control. It stays there until something wakes it back up:
 * {@code /titan anim} with a state, a kill, or the next server restart.
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

        // Entering a state marks the clip dirty, which would have the animation system overwrite the dance
        // on the very next tick. Swallow that before handing the animator the clip we actually want.
        titan.setState(TitanState.EMOTING);
        titan.consumeClipDirty();
        animator.play(clip, true);

        context.sendMessage(Message.translation("titan_commands.commands.titan.dance.playing")
            .param("bones", countDrivenBones(clip))
            .param("total", clip.getBoneCount())
            .param("duration", clip.getDuration()));
    }

    /** How many of the skeleton's bones the borrowed clip actually found a track for. */
    private static int countDrivenBones(@Nonnull final TitanClip clip) {
        int driven = 0;
        for (int i = 0; i < clip.getBoneCount(); i++) {
            if (clip.getTrack(i) != null) driven++;
        }
        return driven;
    }
}
