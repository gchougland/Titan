package com.hexvane.titan.ai;

import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.combat.TitanBoulder;
import com.hexvane.titan.combat.TitanSmashAttack;
import com.hexvane.titan.combat.TitanSound;
import com.hexvane.titan.combat.TitanTelegraph;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanState;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The boulder throw: tear a rock out of the ground and lob it at a distant target.
 *
 * <p>The titan's only ranged answer, and the only attack whose recovery offers no climb, since the titan
 * never leaves its feet. The rock itself is built by {@link TitanBoulder}.
 */
public final class TitanHurlAttack {

    /** Seconds spent digging a boulder out of the ground. */
    private static final float HURL_WINDUP_SECONDS = 1.3f;
    /** Point in the windup at which the rock comes free. */
    private static final float HURL_RIP_SECONDS = 0.8f;
    /** Seconds the throw itself takes. */
    private static final float HURL_SECONDS = 0.6f;
    /** Point in the throw at which the rock leaves the hand. */
    private static final float HURL_RELEASE_SECONDS = 0.25f;
    /** Seconds spent following through afterwards. Short, since the titan never left its feet. */
    private static final float HURL_RECOVER_SECONDS = 0.5f;
    /** How far ahead of the root the titan digs, in blocks. */
    private static final double HURL_RIP_REACH = 4.0;

    /** How far ahead of the root the rock leaves from, in blocks. */
    private static final double HURL_RELEASE_REACH = 5.0;

    /**
     * How far above the root the rock leaves from, in blocks.
     *
     * <p>Chest height rather than overhead. It adds to whatever the arc itself climbs, so releasing at the
     * titan's full standing height would start the rock most of the way to the treetops.
     */
    private static final double HURL_RELEASE_HEIGHT = 5.5;

    private TitanHurlAttack() {
    }

    /**
     * Digs a boulder out of the ground.
     *
     * <p>Two markers are drawn, saying different things. A small one beside the titan shows where the rock
     * is coming from and is only a tell. The one out at the target shows where it is going, and keeps
     * tracking through the windup because the throw has not been aimed yet.
     */
    public static void tickWindup(@Nonnull final TitanAiScratch scratch,
                                  @Nonnull final Store<EntityStore> store,
                                  @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                  @Nonnull final TitanComponent titan,
                                  @Nonnull final TitanVariantAsset variant,
                                  @Nonnull final TransformComponent transform,
                                  final float dt) {

        TitanAiSupport.turnTowards(titan, transform.getPosition(), scratch.targetPosition, variant.getTurnSpeed() * 1.5f, dt);
        TitanSmashAttack.resolveImpactPoint(
            transform.getPosition(), null, titan.getYaw(), HURL_RIP_REACH * titan.getScale(), titan.getAttackPoint());

        // Fired once, at the frame the rock comes free rather than at the start of the dig.
        if (!titan.isImpactFired() && titan.getStateTime() >= HURL_RIP_SECONDS) {
            titan.setImpactFired(true);
            TitanTelegraph.burst(commandBuffer, variant.getTelegraphCrackParticle(),
                titan.getAttackPoint(), (float) titan.getScale());
            TitanSound.play(commandBuffer, variant.getHurlRipSound(), titan.getAttackPoint());
        }

        TitanAiSupport.telegraphCircle(store, commandBuffer, titan, variant, scratch.targetPosition,
            variant.getHurlRadius(), HURL_WINDUP_SECONDS - titan.getStateTime(), dt);

        if (titan.getStateTime() >= HURL_WINDUP_SECONDS) {
            titan.setState(TitanState.HURL);
        }
    }

    /** Throws the rock, releasing partway through the arm's travel. */
    public static void tickHurl(@Nonnull final TitanAiScratch scratch,
                                @Nonnull final Store<EntityStore> store,
                                @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                @Nonnull final Ref<EntityStore> self,
                                @Nonnull final TitanComponent titan,
                                @Nonnull final TitanVariantAsset variant,
                                @Nonnull final TitanSkeletonAsset skeleton,
                                @Nonnull final TransformComponent transform) {

        if (!titan.isImpactFired() && titan.getStateTime() >= HURL_RELEASE_SECONDS) {
            titan.setImpactFired(true);

            final double scale = titan.getScale();
            TitanBoulder.resolveReleasePoint(transform.getPosition(), titan.getYaw(),
                HURL_RELEASE_REACH * scale, HURL_RELEASE_HEIGHT * scale, scratch.point);

            final String prefab = TitanBoulder.resolvePrefab(variant, handPrefab(titan, skeleton));
            if (prefab != null) {
                TitanBoulder.throwAt(store, self, variant, prefab, scratch.point, scratch.targetPosition, (float) scale);
            }
            TitanSound.play(commandBuffer, variant.getHurlThrowSound(), scratch.point);
        }

        if (titan.getStateTime() >= HURL_SECONDS) {
            titan.setState(TitanState.HURL_RECOVER);
        }
    }

    /** Follows through and returns to the chase. */
    public static void tickRecover(@Nonnull final TitanComponent titan, @Nonnull final TitanVariantAsset variant) {
        if (titan.getStateTime() >= HURL_RECOVER_SECONDS) {
            titan.setAttackCooldown(variant.getAttackCooldown());
            titan.setState(TitanState.CHASE);
        }
    }

    /**
     * Positions the throwing hand's IK goal for the current state.
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
            case HURL_WINDUP -> {
                // Down to the ground, grip, then haul back up. The turn at the rip point is the moment the
                // rock comes free, and the same moment the ground is shown splitting.
                final double t = titan.getStateTime();
                final double lift = t < HURL_RIP_SECONDS
                    ? raise * (1.0 - t / HURL_RIP_SECONDS)
                    : raise * 0.8 * ((t - HURL_RIP_SECONDS) / Math.max(1.0e-3, HURL_WINDUP_SECONDS - HURL_RIP_SECONDS));
                goal.set(impact.x, impact.y + Math.max(0.0, lift), impact.z);
                weights[active] = Math.min(1f, (float) (t / (HURL_RIP_SECONDS * 0.5)));
            }
            case HURL -> {
                // Sweeps up and forward past the release point, so the rock is already gone by the time the
                // arm reaches the end of its travel.
                final double t = Math.min(1.0, titan.getStateTime() / HURL_SECONDS);
                final double scale = titan.getScale();
                goal.set(
                    impact.x - Math.sin(titan.getYaw()) * HURL_RELEASE_REACH * scale * t,
                    impact.y + raise * 0.8 + HURL_RELEASE_HEIGHT * scale * t * 0.5,
                    impact.z - Math.cos(titan.getYaw()) * HURL_RELEASE_REACH * scale * t
                );
                weights[active] = 1f;
            }
            case HURL_RECOVER ->
                weights[active] = Math.max(0f, 1f - titan.getStateTime() / HURL_RECOVER_SECONDS);
            default -> {
            }
        }
    }

    /** The prefab of the throwing arm's hand, so a titan throws a piece of itself. */
    @Nullable
    private static String handPrefab(@Nonnull final TitanComponent titan, @Nonnull final TitanSkeletonAsset skeleton) {
        final int chain = titan.findHandChainForSide(skeleton, titan.getAttackSide());
        if (chain < 0) return null;

        final int[] bones = skeleton.getIkChains()[titan.getHandChains()[chain]].getBoneIndices();
        if (bones.length == 0) return null;

        return skeleton.getBones()[bones[bones.length - 1]].getPrefab();
    }
}
