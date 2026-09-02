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
 * The ground pound: haul both fists overhead and drive them in together.
 *
 * <p>Deals little damage directly. The throw is almost entirely vertical, so the fall is what hurts. Both
 * fists end up embedded, which gives two arm ramps at once and the widest way onto the back in the fight.
 */
public final class TitanPoundAttack {

    /** Seconds spent hauling both fists overhead. */
    private static final float POUND_WINDUP_SECONDS = 0.95f;
    /** Seconds both fists take to come down. */
    private static final float POUND_SECONDS = 0.5f;
    /** Point in the pound at which the launch fires. */
    private static final float POUND_IMPACT_SECONDS = 0.3f;
    /** Seconds spent dragging both arms back out of the ground. */
    private static final float POUND_RECOVER_SECONDS = 1.1f;
    /** How far ahead of the root both fists land, in blocks. */
    private static final double POUND_REACH = 3.5;
    /** How far apart the two fists land, in blocks. */
    private static final double POUND_FIST_SPREAD = 3.0;

    /**
     * How much of a pound's throw goes straight up, from 0 to 1.
     *
     * <p>Nearly all of it, which is what separates this from a wider smash: the target is lifted rather
     * than pushed away, and takes the damage on landing.
     */
    private static final double POUND_VERTICAL_SHARE = 0.9;

    private TitanPoundAttack() {
    }

    /**
     * Hauls both fists overhead.
     *
     * <p>Aimed at a fixed point in front rather than at the target, because the pound is a shock radiating
     * from between the fists rather than a strike at anybody. Stepping out of the marked circle is the
     * counterplay.
     */
    public static void tickWindup(@Nonnull final TitanAiScratch scratch,
                                  @Nonnull final Store<EntityStore> store,
                                  @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                  @Nonnull final TitanComponent titan,
                                  @Nonnull final TitanVariantAsset variant,
                                  @Nonnull final TransformComponent transform,
                                  final float dt) {

        if (!TitanAiSupport.hasCommitted(titan, POUND_WINDUP_SECONDS)) {
            TitanAiSupport.turnTowards(titan, transform.getPosition(), scratch.targetPosition, variant.getTurnSpeed() * 1.5f, dt);
            TitanSmashAttack.resolveImpactPoint(
                transform.getPosition(), null, titan.getYaw(), POUND_REACH * titan.getScale(), titan.getAttackPoint());
        }

        TitanAiSupport.telegraphCircle(store, commandBuffer, titan, variant, titan.getAttackPoint(),
            variant.getPoundRadius(), POUND_WINDUP_SECONDS - titan.getStateTime(), dt);

        if (titan.getStateTime() >= POUND_WINDUP_SECONDS) {
            titan.setState(TitanState.POUND);
        }
    }

    /** Drives both fists in, launching everything in range almost straight up. */
    public static void tickPound(@Nonnull final Store<EntityStore> store,
                                 @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                 @Nonnull final Ref<EntityStore> self,
                                 @Nonnull final TitanComponent titan,
                                 @Nonnull final TitanVariantAsset variant) {

        if (!titan.isImpactFired() && titan.getStateTime() >= POUND_IMPACT_SECONDS) {
            titan.setImpactFired(true);
            TitanSmashAttack.execute(store, commandBuffer, self, titan.getAttackPoint(),
                variant.getPoundRadius(), variant.getPoundDamage(), variant.getPoundLaunch(),
                POUND_VERTICAL_SHARE,
                variant.getImpactParticle(),
                variant.getPoundSound() != null ? variant.getPoundSound() : variant.getImpactSound());
        }

        if (titan.getStateTime() >= POUND_SECONDS) {
            titan.setState(TitanState.POUND_STUNNED);
        }
    }

    /** Holds both fists embedded for the variant's stun duration. */
    public static void tickStunned(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        if (titan.getStateTime() >= variant.getPoundStunSeconds()) {
            titan.setState(TitanState.POUND_RECOVER);
        }
    }

    /** Drags both arms back out of the ground and returns to the chase. */
    public static void tickRecover(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        if (titan.getStateTime() >= POUND_RECOVER_SECONDS) {
            titan.setAttackCooldown(variant.getAttackCooldown());
            titan.setState(TitanState.CHASE);
        }
    }

    /** Drives both fists at a pair of points either side of the impact. */
    public static void applyHandGoals(@Nonnull final TitanComponent titan, @Nonnull final TitanSkeletonAsset skeleton) {
        final float[] weights = titan.getHandWeights();
        final var goals = titan.getHandGoals();
        final var chains = skeleton.getIkChains();
        final int[] handChains = titan.getHandChains();

        final double scale = titan.getScale();
        final double raise = skeleton.getHipHeight() * scale * TitanAiSupport.RAISED_HAND_HEIGHT_FACTOR;
        final double spread = POUND_FIST_SPREAD * scale * 0.5;

        final double lift = switch (titan.getState()) {
            case POUND_WINDUP -> raise * 1.2 * Math.min(1.0, titan.getStateTime() / POUND_WINDUP_SECONDS);
            case POUND -> {
                final double t = Math.min(1.0, titan.getStateTime() / POUND_IMPACT_SECONDS);
                yield raise * 1.2 * (1.0 - t * t);
            }
            case POUND_RECOVER -> raise * Math.min(1.0, titan.getStateTime() / POUND_RECOVER_SECONDS);
            default -> 0.0;
        };

        final var impact = titan.getAttackPoint();
        final double rightX = Math.cos(titan.getYaw());
        final double rightZ = -Math.sin(titan.getYaw());

        for (int i = 0; i < weights.length; i++) {
            final double side = Math.signum(chains[handChains[i]].getSide()) * spread;
            goals[i].set(impact.x + rightX * side, impact.y + lift, impact.z + rightZ * side);
            weights[i] = titan.getState() == TitanState.POUND_RECOVER
                ? (float) Math.max(0.0, 1.0 - titan.getStateTime() / POUND_RECOVER_SECONDS)
                : 1f;
        }
    }
}
