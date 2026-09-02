package com.hexvane.titan.ai;

import com.hexvane.titan.asset.TitanSkeletonAsset;
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
 * The body slam: rear back, throw the whole creature forward onto the floor, and lie there winded.
 *
 * <p>Leaves the longest climbing window in the fight. The braced forearms are part of the attack rather
 * than decoration: the back settles too high to jump onto from flat ground, so the arms lie out in front as
 * a pair of ramps up to it.
 */
public final class TitanSlamAttack {

    /**
     * How far ahead of the root the chest comes down, in blocks.
     *
     * <p>Also used by {@link TitanPlowAttack} for its beached recovery, which braces the same way.
     */
    public static final double SLAM_REACH = 3.0;

    /** Seconds spent reared back before a body slam. Longer than an arm windup, since it is a bigger tell. */
    private static final float SLAM_WINDUP_SECONDS = 1.1f;
    /** Seconds the body takes to come down. */
    private static final float SLAM_SECONDS = 0.5f;
    /** Point in the slam at which the area damage fires. */
    private static final float SLAM_IMPACT_SECONDS = 0.3f;
    /** Seconds spent shoving back up off the floor. */
    private static final float RISE_SECONDS = 1.6f;

    private TitanSlamAttack() {
    }

    /** Rears back, tracking the target so the braced arms follow the turn. */
    public static void tickWindup(@Nonnull final TitanAiScratch scratch,
                                  @Nonnull final Store<EntityStore> store,
                                  @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                  @Nonnull final TitanComponent titan,
                                  @Nonnull final TitanVariantAsset variant,
                                  @Nonnull final TransformComponent transform,
                                  final float dt) {

        TitanAiSupport.turnTowards(titan, transform.getPosition(), scratch.targetPosition, variant.getTurnSpeed() * 1.5f, dt);

        // Lands under the chest rather than on the target, because the titan is falling on its own front;
        // aiming the blast at the player would let it belly-flop sideways onto someone stood beside it.
        // Tracked through the windup so the braced arms follow the turn, and frozen once SLAM begins.
        TitanSmashAttack.resolveImpactPoint(
            transform.getPosition(), null, titan.getYaw(), SLAM_REACH, titan.getAttackPoint());

        TitanAiSupport.telegraphCircle(store, commandBuffer, titan, variant, titan.getAttackPoint(),
            variant.getSlamRadius(), SLAM_WINDUP_SECONDS - titan.getStateTime(), dt);

        if (titan.getStateTime() >= SLAM_WINDUP_SECONDS) {
            titan.setState(TitanState.SLAM);
        }
    }

    /** Pitches the body down, firing the area damage partway through the fall. */
    public static void tickSlam(@Nonnull final Store<EntityStore> store,
                                @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                @Nonnull final Ref<EntityStore> self,
                                @Nonnull final TitanComponent titan,
                                @Nonnull final TitanVariantAsset variant) {

        if (!titan.isImpactFired() && titan.getStateTime() >= SLAM_IMPACT_SECONDS) {
            titan.setImpactFired(true);
            TitanSmashAttack.execute(store, commandBuffer, self, variant, titan.getAttackPoint(),
                variant.getSlamRadius());
        }

        if (titan.getStateTime() >= SLAM_SECONDS) {
            titan.setState(TitanState.PRONE);
        }
    }

    /** Lies winded with the back exposed for the variant's prone duration. */
    public static void tickProne(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        if (titan.getStateTime() >= variant.getSlamProneSeconds()) {
            titan.setState(TitanState.RISING);
        }
    }

    /** Pushes back up off the floor. Shared with the plow, which ends beached the same way. */
    public static void tickRising(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        if (titan.getStateTime() >= RISE_SECONDS) {
            titan.setAttackCooldown(variant.getAttackCooldown());
            titan.setState(TitanState.CHASE);
        }
    }

    /**
     * Plants both forearms on the floor ahead of the titan.
     *
     * <p>Held out through the windup as well as the slam itself, since the body is already tipping forward
     * onto them by then.
     */
    public static void applyHandGoals(@Nonnull final TitanComponent titan, @Nonnull final TitanSkeletonAsset skeleton) {
        final float[] weights = titan.getHandWeights();
        final var goals = titan.getHandGoals();
        final var chains = skeleton.getIkChains();
        final int[] handChains = titan.getHandChains();

        final double scale = titan.getScale();
        final double reach = TitanAiSupport.BRACE_HAND_REACH * scale;
        final double spread = TitanAiSupport.BRACE_HAND_SPREAD * scale * 0.5;
        final double raise = skeleton.getHipHeight() * scale * TitanAiSupport.RAISED_HAND_HEIGHT_FACTOR;

        // How far off the floor the hands still are. They come down with the body and stay down until it
        // starts to push back up.
        final double lift = switch (titan.getState()) {
            case SLAM_WINDUP -> raise * 0.6 * Math.min(1.0, titan.getStateTime() / SLAM_WINDUP_SECONDS);
            case SLAM -> {
                final double t = Math.min(1.0, titan.getStateTime() / SLAM_IMPACT_SECONDS);
                yield raise * 0.6 * (1.0 - t * t);
            }
            case RISING -> raise * 0.5 * Math.min(1.0, titan.getStateTime() / RISE_SECONDS);
            default -> 0.0;
        };

        final var origin = titan.getAttackPoint();
        final double forwardX = -Math.sin(titan.getYaw());
        final double forwardZ = -Math.cos(titan.getYaw());
        final double rightX = Math.cos(titan.getYaw());
        final double rightZ = -Math.sin(titan.getYaw());

        for (int i = 0; i < weights.length; i++) {
            final double side = Math.signum(chains[handChains[i]].getSide()) * spread;
            goals[i].set(
                origin.x + forwardX * reach + rightX * side,
                origin.y + lift,
                origin.z + forwardZ * reach + rightZ * side
            );
            weights[i] = 1f;
        }
    }
}
