package com.hexvane.titan.combat;

import com.hexvane.titan.asset.TitanSkeletonAsset;
import com.hexvane.titan.asset.TitanSocketDef;
import com.hexvane.titan.anim.TitanPose;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Finds the players standing on a titan's back.
 *
 * <p>Climbing up there is the whole fight — the ore is on the back and nowhere else — so the titan needs to
 * know when someone has managed it. The test is exact rather than a radius around the body: a player
 * hanging off the side of an arm has not got up there, and a player crouched between the shoulders very
 * much has, and the two are barely a block apart in world space.
 *
 * <p>It works by putting the player into the slab's own frame of reference. The pose already holds a matrix
 * that takes a point on the body and says where in the world it is; running that backwards takes a player
 * in the world and says where on the body they are, at which point "on the back" is a box comparison. That
 * stays true no matter how the titan is standing, which matters here more than anywhere else: the move this
 * feeds pitches the whole rig forward, and a check made in world space would lose the rider the moment the
 * back stopped being level.
 */
public final class TitanRider {

    /** How far past the slab's edge still counts as being on it, in model units. */
    private static final double EDGE_MARGIN = 1.2;
    /** How far below the surface a rider may be, for the tick they land in it. */
    private static final double BELOW = 1.0;
    /** How far above the surface a rider may be. Roughly the height of a player plus a jump. */
    private static final double ABOVE = 5.0;
    /** Fallback half-extents, used only if a skeleton declares no ore sockets to measure. */
    private static final double DEFAULT_HALF_LENGTH = 4.5;
    private static final double DEFAULT_HALF_DEPTH = 2.0;
    private static final double DEFAULT_SURFACE = 2.0;

    private TitanRider() {
    }

    /** The measurements of one titan's back, in the bone-local space of the bone that carries it. */
    public record Back(int boneIndex, double halfLength, double halfDepth, double surface) {
    }

    /**
     * Works out which bone the ore sits on and how far the surface it sits on reaches.
     *
     * <p>Read off the skeleton's ore sockets rather than named in a constant. The sockets are placed on the
     * climbable surface by definition — that is what they are for — so the outermost of them describes the
     * platform exactly, and a titan built on a different rig with a differently shaped back gets the right
     * answer without anyone writing its dimensions down twice.
     *
     * @return {@code null} for a skeleton with no ore sockets, which has no back to stand on
     */
    @Nullable
    public static Back measure(@Nonnull final TitanSkeletonAsset skeleton) {
        final TitanSocketDef[] sockets = skeleton.getWeakpointSockets();
        if (sockets.length == 0) return null;

        int boneIndex = -1;
        double halfLength = 0;
        double halfDepth = 0;
        double surface = Double.NEGATIVE_INFINITY;

        for (final TitanSocketDef socket : sockets) {
            if (socket.getBoneIndex() < 0) continue;
            // Sockets on more than one bone would need more than one box; take the first and measure that.
            if (boneIndex < 0) boneIndex = socket.getBoneIndex();
            if (socket.getBoneIndex() != boneIndex) continue;

            halfLength = Math.max(halfLength, Math.abs(socket.getOffset().x));
            halfDepth = Math.max(halfDepth, Math.abs(socket.getOffset().z));
            surface = Math.max(surface, socket.getOffset().y);
        }

        if (boneIndex < 0) return null;
        if (halfLength <= 0) halfLength = DEFAULT_HALF_LENGTH;
        if (halfDepth <= 0) halfDepth = DEFAULT_HALF_DEPTH;
        if (!Double.isFinite(surface)) surface = DEFAULT_SURFACE;

        return new Back(boneIndex, halfLength, halfDepth, surface);
    }

    /**
     * Collects the players currently standing on the titan's back into {@code out}.
     *
     * @param searchRadius how far around the titan to look, in world blocks. Must comfortably cover the
     *                     body: a miss here reads as nobody being up there.
     */
    public static void collect(@Nonnull final Store<EntityStore> store,
                               @Nonnull final TitanPose pose,
                               @Nonnull final Back back,
                               @Nonnull final Vector3d titanPosition,
                               final double searchRadius,
                               @Nonnull final List<Ref<EntityStore>> out) {

        out.clear();

        final var toLocal = new Matrix4d(pose.getWorld(back.boneIndex()));
        // A singular matrix would mean a bone collapsed to nothing, which the IK can briefly produce.
        if (Math.abs(toLocal.determinant()) < 1.0e-9) return;
        toLocal.invert();

        final var local = new Vector3d();

        for (final Ref<EntityStore> candidate : TargetUtil.getAllEntitiesInSphere(titanPosition, searchRadius, store)) {
            if (!candidate.isValid()) continue;
            if (store.getComponent(candidate, Player.getComponentType()) == null) continue;

            final var transform = store.getComponent(candidate, TransformComponent.getComponentType());
            if (transform == null) continue;

            final var at = transform.getPosition();
            toLocal.transformPosition(at.x, at.y, at.z, local);

            if (Math.abs(local.x) > back.halfLength() + EDGE_MARGIN) continue;
            if (Math.abs(local.z) > back.halfDepth() + EDGE_MARGIN) continue;
            if (local.y < back.surface() - BELOW || local.y > back.surface() + ABOVE) continue;

            out.add(candidate);
        }
    }

    /** Whether anybody is up there. */
    public static boolean any(@Nonnull final Store<EntityStore> store,
                              @Nonnull final TitanPose pose,
                              @Nonnull final Back back,
                              @Nonnull final Vector3d titanPosition,
                              final double searchRadius,
                              @Nonnull final List<Ref<EntityStore>> scratch) {
        collect(store, pose, back, titanPosition, searchRadius, scratch);
        return !scratch.isEmpty();
    }
}
