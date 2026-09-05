package com.hexvane.titan.ai;

import com.hexvane.titan.ik.GroundSampler;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * The part of driving a titan's body that has nothing to do with fighting.
 *
 * <p>Split out of the combat state machine so a pet can reuse it. Every titan has to sit on the ground
 * whatever it is doing, and there is only one right way to do that; a pet copying the maths would drift
 * from the combat titans the first time either was tuned.
 */
public final class TitanBodyDriver {

    /**
     * How fast the body settles onto new terrain height, in blocks per second.
     *
     * <p>Public because it is a speed limit as well as a rate: anything asking the body to be somewhere
     * else vertically — a pet sitting down, for one — has to ask for it no faster than this, or the body
     * is left chasing a height it never reaches.
     */
    public static final double BODY_HEIGHT_FOLLOW = 4.0;

    /** Vertical search window for the ground under the body itself, in blocks. */
    private static final int BODY_GROUND_ABOVE = 6;
    private static final int BODY_GROUND_BELOW = 16;

    private TitanBodyDriver() {
    }

    /**
     * Eases the root towards the terrain under it. The root sits at the feet plane, so the body bone's own
     * bind offset provides the hip height.
     *
     * <p>Eased rather than snapped because the ground under something this wide changes in whole blocks:
     * following it exactly would make the whole titan hop as it crossed a step.
     *
     * @param sink how far below the ground to hold the root, in blocks. Non-zero folds the legs under the
     *             body, since the IK keeps the feet where they were planted.
     */
    public static void settleBodyHeight(@Nonnull final Store<EntityStore> store,
                                        @Nonnull final TransformComponent transform,
                                        final float dt,
                                        final double sink) {

        final var chunkStore = store.getExternalData().getWorld().getChunkStore();
        final var position = transform.getPosition();
        final double ground = GroundSampler.sample(
            chunkStore, position.x, position.y + sink, position.z, BODY_GROUND_ABOVE, BODY_GROUND_BELOW);
        if (!GroundSampler.isValid(ground)) return;

        final double delta = ground - sink - position.y;
        final double maxStep = BODY_HEIGHT_FOLLOW * dt;
        position.y += Math.abs(delta) <= maxStep ? delta : Math.copySign(maxStep, delta);
    }
}
