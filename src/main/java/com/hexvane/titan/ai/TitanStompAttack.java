package com.hexvane.titan.ai;

import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.combat.TitanSmashAttack;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanState;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * The leg stomp: lift the nearest foot clear of the gait and drive it back down.
 *
 * <p>The only attack that borrows a leg rather than an arm, so the remaining legs have to keep supporting
 * the body throughout. It offers no climbing window, which makes it the answer for a titan too large to be
 * climbed at all.
 */
public final class TitanStompAttack {

    /** Quarter turn in radians, used to ease the lift along the first quadrant of a sine. */
    private static final double QUARTER_TURN = Math.PI * 0.5;

    /** Fallback hip height for a titan whose skeleton went missing mid-attack, in model units. */
    private static final double FALLBACK_HIP_HEIGHT = 6.0;

    private TitanStompAttack() {
    }

    /**
     * Commits the leg nearest the target to a stomp.
     *
     * <p>Chosen by where the feet are standing rather than by which corner of the body they hang off, so a
     * titan caught mid-stride uses the leg with the least distance to travel. The choice and the landing
     * spot are both fixed here: re-picking during the windup would let the player drag the raised leg after
     * them, and the telegraph is only meaningful if the spot is committed.
     */
    public static void begin(@Nonnull final TitanAiScratch scratch, @Nonnull final TitanComponent titan) {
        final int foot = titan.findFootNearest(scratch.targetPosition);
        if (foot < 0) {
            titan.setState(TitanState.WINDUP);
            return;
        }

        // Read now rather than each tick, because the contact point stops being available once the leg
        // leaves the ground. A foot caught mid-step reports where it is heading, not where it is.
        final var state = titan.getFeet()[foot];
        titan.getAttackPoint().set(state.stepping ? state.stepTarget : state.planted);

        titan.setStompFoot(foot);
        titan.getStompGoal().set(state.current);
        titan.setState(TitanState.STOMP_WINDUP);
    }

    /**
     * Hauls the chosen leg up and marks the ground under it.
     *
     * <p>The landing spot never moves. The titan can neither chase nor reach during a stomp, so stepping
     * out of the circle is the only counterplay and tracking would remove it.
     *
     * <p>The foot is lifted rather than swung, since a leg holding up one corner of the body cannot reach
     * out without the rest of it falling over. It comes down where it already was.
     */
    public static void tickWindup(@Nonnull final TitanAiScratch scratch,
                                  @Nonnull final Store<EntityStore> store,
                                  @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                  @Nonnull final TitanComponent titan,
                                  @Nonnull final TitanVariantAsset variant,
                                  @Nonnull final TransformComponent transform,
                                  final float dt) {

        TitanAiSupport.turnTowards(titan, transform.getPosition(), scratch.targetPosition, variant.getTurnSpeed(), dt);
        titan.getVelocity().set(0);

        if (!hasFoot(titan)) {
            abandon(titan, variant);
            return;
        }

        final float windup = Math.max(0.01f, variant.getStompWindupSeconds());
        final double lift = liftHeight(titan, variant);
        final double progress = Math.min(1.0, titan.getStateTime() / windup);

        // Eased so the leg rises fast and hangs at the top, which is where the threat is read from. A
        // linear rise spends the whole windup still climbing and never looks committed.
        titan.getStompGoal().set(titan.getAttackPoint());
        titan.getStompGoal().y += lift * Math.sin(progress * QUARTER_TURN);

        TitanAiSupport.telegraphCircle(store, commandBuffer, titan, variant, titan.getAttackPoint(),
            variant.getStompRadius(), windup - titan.getStateTime(), dt);

        if (titan.getStateTime() >= windup) {
            titan.setState(TitanState.STOMP);
        }
    }

    /** Drives the leg back down. The blast fires as it lands rather than partway, since the foot is the blow. */
    public static void tickStomp(@Nonnull final Store<EntityStore> store,
                                 @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                 @Nonnull final Ref<EntityStore> self,
                                 @Nonnull final TitanComponent titan,
                                 @Nonnull final TitanVariantAsset variant) {

        if (!hasFoot(titan)) {
            abandon(titan, variant);
            return;
        }

        final float fall = Math.max(0.01f, variant.getStompSeconds());
        final double lift = liftHeight(titan, variant);
        final double progress = Math.min(1.0, titan.getStateTime() / fall);

        titan.getVelocity().set(0);
        titan.getStompGoal().set(titan.getAttackPoint());
        // Squared so it accelerates into the ground under its own weight rather than being lowered.
        titan.getStompGoal().y += lift * (1.0 - progress * progress);

        if (!titan.isImpactFired() && titan.getStateTime() >= fall) {
            titan.setImpactFired(true);
            final String sound = variant.getStompSound() != null ? variant.getStompSound() : variant.getImpactSound();
            TitanSmashAttack.execute(store, commandBuffer, self, titan.getAttackPoint(),
                variant.getStompRadius(), variant.getStompDamage(), variant.getStompKnockback(),
                TitanSmashAttack.VERTICAL_SHARE, variant.getImpactParticle(), sound);
            titan.setState(TitanState.STOMP_RECOVER);
        }
    }

    /**
     * Holds the leg planted for a moment, then hands it back to the gait.
     *
     * <p>Clearing the stomp foot is what releases it. Until then the animation system drives that leg from
     * {@code stompGoal} and the walk planner is kept away from it.
     */
    public static void tickRecover(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        titan.getStompGoal().set(titan.getAttackPoint());
        titan.getVelocity().set(0);

        if (titan.getStateTime() >= variant.getStompRecoverSeconds()) {
            release(titan);
            titan.setAttackCooldown(variant.getAttackCooldown());
            titan.setState(TitanState.IDLE);
        }
    }

    private static boolean hasFoot(@Nonnull final TitanComponent titan) {
        final int foot = titan.getStompFoot();
        return foot >= 0 && foot < titan.getFeet().length;
    }

    /**
     * Gives up on a stomp that has lost its leg, which can only happen if the rig changed underneath it.
     * Goes through the same release as a completed stomp so the foot is never left pinned to a stale goal.
     */
    private static void abandon(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        release(titan);
        titan.setAttackCooldown(variant.getAttackCooldown());
        titan.setState(TitanState.IDLE);
    }

    /** Hands the stomping leg back to the walk planner, leaving it planted where it landed. */
    private static void release(@Nonnull final TitanComponent titan) {
        if (hasFoot(titan)) {
            final var state = titan.getFeet()[titan.getStompFoot()];
            state.current.set(titan.getAttackPoint());
            state.planted.set(titan.getAttackPoint());
            state.stepping = false;
            state.stepProgress = 0f;
        }
        titan.setStompFoot(-1);
    }

    /** How far a stomping foot is hauled off the ground, in world blocks. */
    private static double liftHeight(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        final var skeleton = titan.getSkeleton();
        final double hip = skeleton == null ? FALLBACK_HIP_HEIGHT : skeleton.getHipHeight();
        return hip * titan.getScale() * variant.getStompLift();
    }
}
