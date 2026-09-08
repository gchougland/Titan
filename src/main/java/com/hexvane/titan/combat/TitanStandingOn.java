package com.hexvane.titan.combat;

import com.hexvane.titan.dunewyrm.DunewyrmPartComponent;
import com.hexvane.titan.dunewyrm.DunewyrmTuning;
import com.hexvane.titan.entity.TitanPartComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Detects when a player is standing on climbable titan / Dunewyrm voxels.
 *
 * <p>Knockback while anchored to a moving platform can launch players absurd distances, so callers skip
 * or soften impulse when this is true.
 */
public final class TitanStandingOn {

    private TitanStandingOn() {
    }

    public static boolean isOnClimbable(@Nonnull final Store<EntityStore> store,
                                        @Nonnull final Ref<EntityStore> victim) {
        final var transform = store.getComponent(victim, TransformComponent.getComponentType());
        if (transform == null) return false;
        return isOnClimbable(store, transform.getPosition());
    }

    public static boolean isOnClimbable(@Nonnull final Store<EntityStore> store,
                                        @Nonnull final Vector3d feet) {
        for (final Ref<EntityStore> candidate : TargetUtil.getAllEntitiesInCylinder(feet, 1.8, 3.0, store)) {
            if (store.getComponent(candidate, HitboxCollision.getComponentType()) == null) continue;
            final boolean titanPart = store.getComponent(candidate, TitanPartComponent.getComponentType()) != null;
            final boolean snakePart = store.getComponent(candidate, DunewyrmPartComponent.getComponentType()) != null;
            if (!titanPart && !snakePart) continue;
            final var ct = store.getComponent(candidate, TransformComponent.getComponentType());
            if (ct == null) continue;
            final double dy = feet.y - ct.getPosition().y;
            if (dy < -0.35 || dy > 4.5) continue;
            final double dx = feet.x - ct.getPosition().x;
            final double dz = feet.z - ct.getPosition().z;
            if (dx * dx + dz * dz <= 2.6 * 2.6) return true;
        }
        return false;
    }

    /** Feet clearly above a segment centre — used by Dunewyrm contact / chase. */
    public static boolean isAboveSegment(@Nonnull final Vector3d feet, @Nonnull final Vector3d segment) {
        final double dx = feet.x - segment.x;
        final double dz = feet.z - segment.z;
        final double r = DunewyrmTuning.CONTACT_RADIUS;
        if (dx * dx + dz * dz > r * r) return false;
        final double dy = feet.y - segment.y;
        return dy >= 0.75 && dy <= 4.5;
    }
}
