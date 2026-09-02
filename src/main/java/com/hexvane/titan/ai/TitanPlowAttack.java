package com.hexvane.titan.ai;

import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.combat.TitanRider;
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
import java.util.concurrent.ThreadLocalRandom;

/**
 * The plow: bury the front edge of the body and drive forward, scraping off anyone riding the back.
 *
 * <p>The titan's answer to being climbed, and the only attack with a direction rather than a radius, so it
 * is telegraphed as a corridor and dodged by stepping aside. It ends beached, which is a long recovery.
 */
public final class TitanPlowAttack {

    /** Seconds spent rearing up and pitching the front down before a plow. */
    private static final float PLOW_WINDUP_SECONDS = 1.0f;
    /** How often a plow in progress damages whatever is in front of it. */
    private static final float PLOW_SWEEP_SECONDS = 0.3f;
    /** How far ahead of the root the plow's blade sits, in blocks. */
    private static final double PLOW_BLADE_REACH = 4.0;
    /** How far the corridor marker reaches ahead of the titan, as a multiple of the run it will make. */
    private static final double PLOW_TELEGRAPH_LEAD = 1.15;
    /** How much of the throw given to a rider goes straight up rather than backwards. */
    private static final double PLOW_RIDER_LIFT = 0.55;
    /** How far around the body to look for riders, in blocks. Comfortably wider than a titan. */
    private static final double RIDER_SEARCH_RADIUS = 16.0;

    private TitanPlowAttack() {
    }

    /**
     * Starts a plow if somebody is on the back, the cooldown has run out, and the roll goes its way.
     *
     * <p>All three gates matter. Without the roll, climbing on would be answered instantly every time.
     * Without a cooldown separate from the ordinary one, a titan being climbed is also being hit, which
     * holds the ordinary cooldown at zero and would let it plow continuously.
     *
     * @return whether a plow was started
     */
    public static boolean tryBegin(@Nonnull final TitanAiScratch scratch,
                                   @Nonnull final Store<EntityStore> store,
                                   @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                   @Nonnull final TitanComponent titan,
                                   @Nonnull final TitanVariantAsset variant,
                                   @Nonnull final TitanSkeletonAsset skeleton,
                                   @Nonnull final TransformComponent transform) {

        if (titan.getPlowCooldown() > 0f || variant.getPlowChance() <= 0f) return false;

        final var pose = titan.getPose();
        if (pose == null) return false;

        final TitanRider.Back back = TitanRider.measure(skeleton);
        if (back == null) return false;

        if (!TitanRider.any(store, pose, back, transform.getPosition(),
            RIDER_SEARCH_RADIUS * titan.getScale(), scratch.riders)) {
            return false;
        }
        if (ThreadLocalRandom.current().nextFloat() >= variant.getPlowChance()) {
            // Rolled and lost. A partial cooldown still starts, so the next roll is not on the very next
            // tick, which would turn the chance into a formality.
            titan.setPlowCooldown(variant.getPlowCooldown() * 0.5f);
            return false;
        }

        titan.getVelocity().set(0);
        titan.setState(TitanState.PLOW_WINDUP);
        TitanSound.play(commandBuffer, variant.getTelegraphSound(), transform.getPosition());
        return true;
    }

    /**
     * Rears up and pitches the front of the body towards the floor.
     *
     * <p>The corridor is drawn slightly longer than the run will be, so nobody is caught by the last stride.
     */
    public static void tickWindup(@Nonnull final TitanAiScratch scratch,
                                  @Nonnull final Store<EntityStore> store,
                                  @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                  @Nonnull final TitanComponent titan,
                                  @Nonnull final TitanVariantAsset variant,
                                  @Nonnull final TransformComponent transform,
                                  final float dt) {

        // Turning stops partway through, so the corridor drawn on the ground is the one the titan will run
        // down rather than the last frame of a line that kept swinging after the player.
        if (!TitanAiSupport.hasCommitted(titan, PLOW_WINDUP_SECONDS)) {
            TitanAiSupport.turnTowards(titan, transform.getPosition(), scratch.targetPosition, variant.getTurnSpeed(), dt);
        }
        TitanSmashAttack.resolveImpactPoint(
            transform.getPosition(), null, titan.getYaw(), PLOW_BLADE_REACH * titan.getScale(), titan.getAttackPoint());

        final float remaining = PLOW_WINDUP_SECONDS - titan.getStateTime();
        if (titan.consumePulse(dt, TitanTelegraph.pulseInterval(remaining))) {
            TitanTelegraph.corridor(commandBuffer, store.getExternalData().getWorld().getChunkStore(),
                variant.getTelegraphLineParticle(), transform.getPosition(), titan.getYaw(),
                variant.getPlowSpeed() * variant.getPlowSeconds() * PLOW_TELEGRAPH_LEAD,
                variant.getPlowRadius());
        }

        if (titan.getStateTime() >= PLOW_WINDUP_SECONDS) {
            titan.setState(TitanState.PLOW);
            TitanSound.play(commandBuffer, variant.getPlowSound(), transform.getPosition());
        }
    }

    /**
     * Grinds forward, damaging whatever is in front and throwing off whoever is on top.
     *
     * <p>The titan does not turn during the run. It committed to a line during the windup and that line is
     * what was shown, so steering into a dodge would make the corridor decorative.
     */
    public static void tickPlow(@Nonnull final TitanAiScratch scratch,
                                @Nonnull final Store<EntityStore> store,
                                @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                @Nonnull final Ref<EntityStore> self,
                                @Nonnull final TitanComponent titan,
                                @Nonnull final TitanVariantAsset variant,
                                @Nonnull final TitanSkeletonAsset skeleton,
                                @Nonnull final TransformComponent transform,
                                final float dt) {

        final var position = transform.getPosition();
        scratch.direction.set(-Math.sin(titan.getYaw()), 0, -Math.cos(titan.getYaw()));
        titan.getVelocity().set(scratch.direction).mul(variant.getPlowSpeed());
        position.fma(variant.getPlowSpeed() * dt, scratch.direction);

        TitanSmashAttack.resolveImpactPoint(
            position, null, titan.getYaw(), PLOW_BLADE_REACH * titan.getScale(), titan.getAttackPoint());

        // Swept in bursts rather than every tick. The blade covers its own width several times a second, so
        // a continuous sweep would deal the attack's damage twenty times over to anyone who stood still.
        if (titan.consumePulse(dt, PLOW_SWEEP_SECONDS)) {
            TitanSmashAttack.execute(store, commandBuffer, self, titan.getAttackPoint(),
                variant.getPlowRadius(), variant.getPlowDamage(), variant.getAttackKnockback(),
                TitanSmashAttack.VERTICAL_SHARE,
                variant.getImpactParticle(), null);
            throwRiders(scratch, store, commandBuffer, titan, variant, skeleton, position);
        }

        if (titan.getStateTime() >= variant.getPlowSeconds()) {
            titan.getVelocity().set(0);
            titan.setPlowCooldown(variant.getPlowCooldown());
            titan.setState(TitanState.PLOW_RECOVER);
        }
    }

    /**
     * Lies beached at the end of the run, then hands over to {@link TitanSlamAttack#tickRising}.
     *
     * <p>Sharing the rise keeps the arms braced throughout, which is what leaves both this recovery and the
     * body slam's open to being climbed.
     */
    public static void tickRecover(@Nonnull final TitanComponent titan,
                                   @Nonnull final TitanVariantAsset variant,
                                   @Nonnull final TransformComponent transform) {

        titan.getVelocity().set(0);
        TitanSmashAttack.resolveImpactPoint(
            transform.getPosition(), null, titan.getYaw(), TitanSlamAttack.SLAM_REACH * titan.getScale(),
            titan.getAttackPoint());

        if (titan.getStateTime() >= variant.getPlowBeachedSeconds()) {
            titan.setState(TitanState.RISING);
        }
    }

    /**
     * Sweeps both arms back and out of the way of the blade.
     *
     * <p>Out in front they would plow instead of the body, and at the sides they would sit in the corridor
     * the attack is meant to clear.
     */
    public static void applyHandGoals(@Nonnull final TitanComponent titan, @Nonnull final TitanSkeletonAsset skeleton) {
        final float[] weights = titan.getHandWeights();
        final var goals = titan.getHandGoals();
        final var chains = skeleton.getIkChains();
        final int[] handChains = titan.getHandChains();

        final double scale = titan.getScale();
        final double reach = TitanAiSupport.BRACE_HAND_REACH * scale;
        final double spread = TitanAiSupport.BRACE_HAND_SPREAD * scale * 0.5;
        final double height = skeleton.getHipHeight() * scale * 0.35;

        final double blend = titan.getState() == TitanState.PLOW_WINDUP
            ? Math.min(1.0, titan.getStateTime() / PLOW_WINDUP_SECONDS)
            : 1.0;

        final var origin = titan.getAttackPoint();
        // Backwards, hence the sign flip against the forward vector every other attack uses.
        final double backX = Math.sin(titan.getYaw());
        final double backZ = Math.cos(titan.getYaw());
        final double rightX = Math.cos(titan.getYaw());
        final double rightZ = -Math.sin(titan.getYaw());

        for (int i = 0; i < weights.length; i++) {
            final double side = Math.signum(chains[handChains[i]].getSide()) * spread;
            goals[i].set(
                origin.x + backX * reach * blend + rightX * side,
                origin.y + height,
                origin.z + backZ * reach * blend + rightZ * side
            );
            weights[i] = (float) blend;
        }
    }

    /**
     * Throws anyone still on the back off, backwards and up.
     *
     * <p>Much harder than any other knockback the titan deals, since a rider who is nudged and lands back
     * on the slab has not been removed. Aimed backwards relative to the titan so they land in the ground it
     * has already driven through rather than the ground it is about to.
     */
    private static void throwRiders(@Nonnull final TitanAiScratch scratch,
                                    @Nonnull final Store<EntityStore> store,
                                    @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                    @Nonnull final TitanComponent titan,
                                    @Nonnull final TitanVariantAsset variant,
                                    @Nonnull final TitanSkeletonAsset skeleton,
                                    @Nonnull final Vector3d position) {

        final var pose = titan.getPose();
        if (pose == null) return;

        final TitanRider.Back back = TitanRider.measure(skeleton);
        if (back == null) return;

        TitanRider.collect(store, pose, back, position, RIDER_SEARCH_RADIUS * titan.getScale(), scratch.riders);
        if (scratch.riders.isEmpty()) return;

        final float strength = variant.getPlowRiderKnockback();
        scratch.impulse.set(
            Math.sin(titan.getYaw()) * strength * (1.0 - PLOW_RIDER_LIFT),
            strength * PLOW_RIDER_LIFT,
            Math.cos(titan.getYaw()) * strength * (1.0 - PLOW_RIDER_LIFT)
        );

        for (final Ref<EntityStore> rider : scratch.riders) {
            TitanSmashAttack.impulse(commandBuffer, rider, scratch.impulse);
        }
    }
}
