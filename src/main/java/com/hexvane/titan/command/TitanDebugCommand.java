package com.hexvane.titan.command;

import com.hexvane.titan.asset.TitanIkChainDef;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanPartComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * {@code /titan debug <mode> [--particle=<system>] [--radius=n]}
 *
 * <p>Development aid that reports the nearest titan's rig as chat text, and also draws it in the world when
 * a particle system is named. Particles are opt-in because which effect reads well depends on the asset
 * packs the server has loaded.
 */
public final class TitanDebugCommand extends AbstractPlayerCommand {

    public enum Mode {
        /** Bone pivots and the clip currently driving them. */
        BONES,
        /** Foot contact points, hand goals and IK weights. */
        IK,
        /** Which voxels carry hard collision, and so what a player can stand on. */
        COLLIDERS,
        /** State machine, target and weakpoint tally. */
        STATE
    }

    @Nonnull
    private final RequiredArg<Mode> modeArg =
        withRequiredArg("mode", "titan_commands.commands.titan.debug.mode.desc", ArgTypes.forEnum("mode", Mode.class));
    @Nonnull
    private final OptionalArg<ParticleSystem> particleArg =
        withOptionalArg("particle", "titan_commands.commands.titan.debug.particle.desc", ArgTypes.PARTICLE_SYSTEM);
    @Nonnull
    private final DefaultArg<Double> radiusArg = withDefaultArg(
        "radius", "titan_commands.commands.titan.debug.radius.desc", ArgTypes.DOUBLE, 128.0, "128");

    public TitanDebugCommand() {
        super("debug", "titan_commands.commands.titan.debug.desc");
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
        if (titan == null) return;

        final String particle = particleArg.provided(context) ? particleArg.get(context).getId() : null;

        switch (modeArg.get(context)) {
            case BONES -> dumpBones(context, store, titan, particle);
            case IK -> dumpIk(context, store, titan, particle);
            case COLLIDERS -> dumpColliders(context, store, titanRef, transform.getPosition(), particle);
            case STATE -> dumpState(context, titan);
        }
    }

    private void dumpBones(@Nonnull final CommandContext context,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final TitanComponent titan,
                           @Nullable final String particle) {

        final var skeleton = titan.getSkeleton();
        final var pose = titan.getPose();
        if (skeleton == null || pose == null) return;

        final var position = new Vector3d();
        for (final var bone : skeleton.getBones()) {
            pose.getWorldPosition(bone.getIndex(), position);
            context.sendMessage(Message.translation("titan_commands.commands.titan.debug.bone")
                .param("bone", bone.getName())
                .param("x", position.x)
                .param("y", position.y)
                .param("z", position.z));
            mark(store, particle, position);
        }
    }

    private void dumpIk(@Nonnull final CommandContext context,
                        @Nonnull final Store<EntityStore> store,
                        @Nonnull final TitanComponent titan,
                        @Nullable final String particle) {

        final var skeleton = titan.getSkeleton();
        if (skeleton == null) return;

        final var feet = titan.getFeet();
        final int[] footChains = titan.getFootChains();
        for (int i = 0; i < feet.length; i++) {
            final TitanIkChainDef chain = skeleton.getIkChains()[footChains[i]];
            context.sendMessage(Message.translation("titan_commands.commands.titan.debug.foot")
                .param("chain", chain.getName())
                .param("stepping", feet[i].stepping)
                .param("progress", feet[i].stepProgress)
                .param("x", feet[i].current.x)
                .param("y", feet[i].current.y)
                .param("z", feet[i].current.z));
            mark(store, particle, feet[i].current);
        }

        final var goals = titan.getHandGoals();
        final float[] weights = titan.getHandWeights();
        final int[] handChains = titan.getHandChains();
        for (int i = 0; i < goals.length; i++) {
            final TitanIkChainDef chain = skeleton.getIkChains()[handChains[i]];
            context.sendMessage(Message.translation("titan_commands.commands.titan.debug.hand")
                .param("chain", chain.getName())
                .param("weight", weights[i])
                .param("x", goals[i].x)
                .param("y", goals[i].y)
                .param("z", goals[i].z));
            if (weights[i] > 0f) mark(store, particle, goals[i]);
        }
    }

    private void dumpColliders(@Nonnull final CommandContext context,
                               @Nonnull final Store<EntityStore> store,
                               @Nonnull final Ref<EntityStore> titanRef,
                               @Nonnull final Vector3d origin,
                               @Nullable final String particle) {

        int parts = 0;
        int colliders = 0;

        for (final Ref<EntityStore> candidate : TitanCommandUtil.snapshotNearby(store, origin, 128.0)) {
            if (!candidate.isValid()) continue;
            final var part = store.getComponent(candidate, TitanPartComponent.getComponentType());
            if (part == null) continue;

            final var owner = part.getOwner();
            if (owner == null || owner.getIndex() != titanRef.getIndex()) continue;

            parts++;
            if (store.getComponent(candidate, HitboxCollision.getComponentType()) == null) continue;

            colliders++;
            final var transform = store.getComponent(candidate, TransformComponent.getComponentType());
            if (transform != null) mark(store, particle, transform.getPosition());
        }

        context.sendMessage(Message.translation("titan_commands.commands.titan.debug.colliders")
            .param("parts", parts)
            .param("colliders", colliders));
    }

    private void dumpState(@Nonnull final CommandContext context, @Nonnull final TitanComponent titan) {
        context.sendMessage(Message.translation("titan_commands.commands.titan.debug.state")
            .param("variant", String.valueOf(titan.getVariantId()))
            .param("state", titan.getState().name())
            .param("stateTime", titan.getStateTime())
            .param("clip", titan.getAnimator() == null ? "<none>" : titan.getAnimator().getCurrentName())
            .param("yaw", (float) Math.toDegrees(titan.getYaw()))
            .param("cooldown", titan.getAttackCooldown())
            .param("side", titan.getAttackSide() < 0 ? "L" : "R")
            .param("weakpoints", titan.getWeakpointsRemaining())
            .param("total", titan.getWeakpointsTotal())
            .param("toKill", titan.getWeakpointsStillNeeded()));
    }

    private static void mark(@Nonnull final Store<EntityStore> store,
                             @Nullable final String particle,
                             @Nonnull final Vector3d position) {
        if (particle == null) return;
        ParticleUtil.spawnParticleEffect(particle, position, store);
    }
}
