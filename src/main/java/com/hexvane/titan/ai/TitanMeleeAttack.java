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
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * The single-armed smash: raise one fist, drive it into the ground, and leave it embedded.
 *
 * <p>The most frequent attack a titan makes, and the shortest climbing window it offers. Drives
 * {@link TitanState#WINDUP}, {@link TitanState#SMASH}, {@link TitanState#STUNNED} and
 * {@link TitanState#RECOVER}.
 */
public final class TitanMeleeAttack {

    /**
     * Seconds the arm hangs in the air before it comes down.
     *
     * <p>Long enough for the marker under it to be seen and stepped out of. Held in the same band as the
     * other windups so the whole moveset reads at one pace.
     */
    private static final float WINDUP_SECONDS = 1.1f;
    /** Seconds the arm takes to travel down. */
    private static final float SMASH_SECONDS = 0.55f;
    /** Point in the smash at which the area damage fires. */
    private static final float IMPACT_SECONDS = 0.35f;
    /** Seconds spent pulling the hand back out of the ground. */
    private static final float RECOVER_SECONDS = 0.8f;

    private TitanMeleeAttack() {
    }

    /** Aims at the target for the first part of the windup, then commits and marks the ground. */
    public static void tickWindup(@Nonnull final TitanAiScratch scratch,
                                  @Nonnull final Store<EntityStore> store,
                                  @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                  @Nonnull final TitanComponent titan,
                                  @Nonnull final TitanVariantAsset variant,
                                  @Nonnull final TransformComponent transform,
                                  final float dt) {

        TitanAiSupport.turnTowards(titan, transform.getPosition(), scratch.targetPosition, variant.getTurnSpeed() * 1.5f, dt);

        // Marking a spot the titan is still turning away from would be a lie, and marking one it is still
        // tracking the player onto would be useless, so aiming stops partway through.
        if (!TitanAiSupport.hasCommitted(titan, WINDUP_SECONDS)) {
            TitanSmashAttack.resolveImpactPoint(
                transform.getPosition(),
                titan.getTarget() != null ? scratch.targetPosition : null,
                titan.getYaw(),
                variant.getAttackRange(),
                titan.getAttackPoint());
        }

        TitanAiSupport.telegraphCircle(store, commandBuffer, titan, variant, titan.getAttackPoint(),
            variant.getAttackRadius(), WINDUP_SECONDS - titan.getStateTime(), dt);

        if (titan.getStateTime() >= WINDUP_SECONDS) {
            titan.setState(TitanState.SMASH);
        }
    }

    /** Drives the fist down, firing the area damage partway through the travel. */
    public static void tickSmash(@Nonnull final Store<EntityStore> store,
                                 @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                 @Nonnull final Ref<EntityStore> self,
                                 @Nonnull final TitanComponent titan,
                                 @Nonnull final TitanVariantAsset variant) {

        if (!titan.isImpactFired() && titan.getStateTime() >= IMPACT_SECONDS) {
            titan.setImpactFired(true);
            TitanSmashAttack.execute(store, commandBuffer, self, variant, titan.getAttackPoint());
        }

        if (titan.getStateTime() >= SMASH_SECONDS) {
            titan.setState(TitanState.STUNNED);
        }
    }

    /** Holds the hand embedded for the variant's stun duration, which is the climbing window. */
    public static void tickStunned(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        if (titan.getStateTime() >= variant.getStunSeconds()) {
            titan.setState(TitanState.RECOVER);
        }
    }

    /** Pulls the arm free and hands control back to the chase. */
    public static void tickRecover(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        if (titan.getStateTime() >= RECOVER_SECONDS) {
            titan.setAttackCooldown(variant.getAttackCooldown());
            titan.setState(TitanState.CHASE);
        }
    }

    /**
     * Positions the working hand's IK goal for the current state.
     *
     * @param raise how far above the impact point a raised hand sits, in world blocks
     */
    public static void applyHandGoal(@Nonnull final TitanComponent titan,
                                     @Nonnull final Vector3d goal,
                                     @Nonnull final Vector3d impact,
                                     final double raise,
                                     @Nonnull final float[] weights,
                                     final int active) {

        switch (titan.getState()) {
            case WINDUP -> {
                goal.set(impact.x, impact.y + raise, impact.z);
                weights[active] = Math.min(1f, titan.getStateTime() / WINDUP_SECONDS);
            }
            case SMASH -> {
                final double t = Math.min(1.0, titan.getStateTime() / IMPACT_SECONDS);
                // Eased in so the arm accelerates into the ground rather than drifting down linearly.
                goal.set(impact.x, impact.y + raise * (1.0 - t * t), impact.z);
                weights[active] = 1f;
            }
            case STUNNED -> {
                goal.set(impact);
                weights[active] = 1f;
            }
            case RECOVER -> {
                final double t = Math.min(1.0, titan.getStateTime() / RECOVER_SECONDS);
                goal.set(impact.x, impact.y + raise * t * 0.5, impact.z);
                weights[active] = (float) (1.0 - t);
            }
            default -> {
            }
        }
    }
}
