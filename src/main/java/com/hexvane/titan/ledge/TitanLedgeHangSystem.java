package com.hexvane.titan.ledge;

import com.hexvane.titan.combat.TitanSmashAttack;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.builtin.mounts.MountSystems;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Set;

/**
 * Drives the invisible cling-cart along static ledge rails.
 *
 * <p>Runs after {@link MountSystems.HandleMountInput} so any Minecart free-drive that shoved the cart is
 * overwritten by a snap back onto the rail. Ledge slabs are never the mount target and never move.
 */
public final class TitanLedgeHangSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final Query<EntityStore> query = Query.and(
        MountedComponent.getComponentType(),
        PlayerInput.getComponentType(),
        TitanLedgeHangComponent.getComponentType());

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
        new SystemDependency<>(Order.AFTER, MountSystems.HandleMountInput.class),
        new SystemDependency<>(Order.BEFORE, PlayerSystems.ProcessPlayerInput.class));

    @Nonnull
    private final Vector3d wish = new Vector3d();
    @Nonnull
    private final Vector3d right = new Vector3d();
    @Nonnull
    private final Vector3d forward = new Vector3d();
    @Nonnull
    private final Vector3d launch = new Vector3d();
    @Nonnull
    private final Vector3d relative = new Vector3d();

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void tick(final float dt,
                     final int index,
                     @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull final Store<EntityStore> store,
                     @Nonnull final CommandBuffer<EntityStore> commandBuffer) {

        final Ref<EntityStore> player = archetypeChunk.getReferenceTo(index);
        final var mounted = archetypeChunk.getComponent(index, MountedComponent.getComponentType());
        final var input = archetypeChunk.getComponent(index, PlayerInput.getComponentType());
        final var hang = archetypeChunk.getComponent(index, TitanLedgeHangComponent.getComponentType());
        if (mounted == null || input == null || hang == null) return;
        if (mounted.getControllerType() != MountController.Minecart) return;

        final Ref<EntityStore> cartRef = hang.getCart() != null ? hang.getCart() : mounted.getMountedToEntity();
        if (cartRef == null || !cartRef.isValid()) {
            clearHang(store, commandBuffer, player, null);
            return;
        }

        final var cart = store.getComponent(cartRef, TitanLedgeCartComponent.getComponentType());
        final var cartTransform = commandBuffer.getComponent(cartRef, TransformComponent.getComponentType());
        if (cart == null || cartTransform == null) {
            clearHang(store, commandBuffer, player, cartRef);
            return;
        }

        readWishAndRelative(input);

        final boolean crouchPressed = crouching(archetypeChunk, index, input, store, cartRef);
        final boolean jumpPressed = jumping(archetypeChunk, index, input, store, cartRef)
            || relative.y > 0.15;

        // Edge-trigger so held jump/crouch from mount state packets do not fire every tick.
        final boolean crouch = crouchPressed && !hang.isCrouchHeld();
        final boolean jump = jumpPressed && !hang.isJumpHeld();
        hang.setCrouchHeld(crouchPressed);
        hang.setJumpHeld(jumpPressed);

        Ref<EntityStore> ledgeRef = cart.getLedge();
        if (ledgeRef == null || !ledgeRef.isValid()) {
            clearHang(store, commandBuffer, player, cartRef);
            return;
        }

        var ledge = store.getComponent(ledgeRef, TitanLedgeComponent.getComponentType());
        var ledgeTransform = store.getComponent(ledgeRef, TransformComponent.getComponentType());
        if (ledge == null || ledgeTransform == null) {
            clearHang(store, commandBuffer, player, cartRef);
            return;
        }

        float yaw = ledgeTransform.getRotation().yaw();
        TitanLedgeSpawner.right(yaw, right);
        TitanLedgeSpawner.forward(yaw, forward);

        final float railStep = railStep(dt);
        final float climbWish = (float) (wish.x * forward.x + wish.z * forward.z);
        final float climbRel = (float) (relative.x * forward.x + relative.z * forward.z);

        if (crouch) {
            clearHang(store, commandBuffer, player, cartRef);
            return;
        }

        if (jump) {
            clearHang(store, commandBuffer, player, cartRef);
            launch.set(wish.x, 0, wish.z);
            if (launch.lengthSquared() < 1.0e-6) {
                launch.set(relative.x, 0, relative.z);
            }
            if (launch.lengthSquared() < 1.0e-6) {
                launch.set(forward).mul(-1);
            } else {
                launch.normalize();
            }
            final double up = (climbWish > 0.2f || climbRel > 0.05f) ? 0.55 : 0.35;
            launch.mul(TitanLedge.JUMP_SPEED * (1.0 - up));
            launch.y = TitanLedge.JUMP_SPEED * up;
            TitanSmashAttack.impulse(commandBuffer, player, launch);
            return;
        }

        if (climbWish > 0.45f || climbRel > 0.05f) {
            pullUp(store, commandBuffer, player, cartRef, ledgeTransform, yaw);
            return;
        }

        float t = cart.getT() + railStep;

        if (t > ledge.getHalfWidth() || t < -ledge.getHalfWidth()) {
            final float overflow = t > 0 ? t - ledge.getHalfWidth() : t + ledge.getHalfWidth();
            final int sign = t > 0 ? 1 : -1;
            final Ref<EntityStore> neighbor = findNeighbor(store, ledgeRef, ledgeTransform, yaw, sign);
            if (neighbor != null) {
                final var nextLedge = store.getComponent(neighbor, TitanLedgeComponent.getComponentType());
                final var nextTransform = store.getComponent(neighbor, TransformComponent.getComponentType());
                if (nextLedge != null && nextTransform != null) {
                    ledgeRef = neighbor;
                    ledge = nextLedge;
                    ledgeTransform = nextTransform;
                    yaw = nextTransform.getRotation().yaw();
                    TitanLedgeSpawner.right(yaw, right);
                    TitanLedgeSpawner.forward(yaw, forward);
                    cart.setLedge(neighbor);
                    t = sign > 0
                        ? -nextLedge.getHalfWidth() + overflow
                        : nextLedge.getHalfWidth() + overflow;
                } else {
                    t = clamp(t, -ledge.getHalfWidth(), ledge.getHalfWidth());
                }
            } else {
                t = clamp(t, -ledge.getHalfWidth(), ledge.getHalfWidth());
            }
        }

        cart.setT(t);

        // Always snap the cart onto the rail — undoes any Minecart free-drive HandleMountInput applied.
        final var spot = TitanLedgeSpawner.hangWorldPosition(
            ledgeTransform.getPosition(), yaw, ledge, t);
        cartTransform.getPosition().set(spot);
        cartTransform.getRotation().set(0, yaw, 0);

        // Drop leftover Absolute/Relative so ProcessPlayerInput does not also shove the player off the mount.
        stripCartDrive(input);

        playClimbAnim(commandBuffer, player, hang, railStep);
    }

    /**
     * Lateral distance to slide this tick along the rail.
     *
     * <p>Wish is a direction (-1..1); Relative is already a world step from the Minecart client, so it is
     * projected onto the rail as-is rather than scaled by {@link TitanLedge#STRAFE_SPEED}.
     */
    private float railStep(final float dt) {
        final float fromWish = (float) (wish.x * right.x + wish.z * right.z);
        if (Math.abs(fromWish) > 0.05f) return fromWish * TitanLedge.STRAFE_SPEED * dt;
        return (float) (relative.x * right.x + relative.z * right.z);
    }

    private void readWishAndRelative(@Nonnull final PlayerInput input) {
        wish.set(0);
        relative.set(0);
        for (final var update : input.getMovementUpdateQueue()) {
            if (update instanceof PlayerInput.WishMovement wanted) {
                wish.add(wanted.getX(), 0, wanted.getZ());
            } else if (update instanceof PlayerInput.RelativeMovement move) {
                relative.add(move.getX(), move.getY(), move.getZ());
            }
        }
        if (wish.lengthSquared() > 1.0) wish.normalize();
    }

    private static void stripCartDrive(@Nonnull final PlayerInput input) {
        final Iterator<PlayerInput.InputUpdate> it = input.getMovementUpdateQueue().iterator();
        while (it.hasNext()) {
            final var update = it.next();
            if (update instanceof PlayerInput.RelativeMovement || update instanceof PlayerInput.AbsoluteMovement) {
                it.remove();
            }
        }
    }

    private static boolean crouching(@Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                                     final int index,
                                     @Nonnull final PlayerInput input,
                                     @Nonnull final Store<EntityStore> store,
                                     @Nonnull final Ref<EntityStore> cartRef) {

        Boolean latest = null;
        for (final var update : input.getMovementUpdateQueue()) {
            if (update instanceof PlayerInput.SetRiderMovementStates set) {
                latest = set.movementStates().crouching;
            } else if (update instanceof PlayerInput.SetMovementStates set) {
                latest = set.movementStates().crouching;
            }
        }
        if (latest != null) return latest;

        final var cartStates = store.getComponent(cartRef, MovementStatesComponent.getComponentType());
        if (cartStates != null && cartStates.getMovementStates().crouching) return true;

        final var states = archetypeChunk.getComponent(index, MovementStatesComponent.getComponentType());
        return states != null && states.getMovementStates().crouching;
    }

    private static boolean jumping(@Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                                   final int index,
                                   @Nonnull final PlayerInput input,
                                   @Nonnull final Store<EntityStore> store,
                                   @Nonnull final Ref<EntityStore> cartRef) {

        Boolean latest = null;
        for (final var update : input.getMovementUpdateQueue()) {
            if (update instanceof PlayerInput.SetRiderMovementStates set) {
                latest = set.movementStates().jumping;
            } else if (update instanceof PlayerInput.SetMovementStates set) {
                latest = set.movementStates().jumping;
            }
        }
        if (latest != null) return latest;

        final var cartStates = store.getComponent(cartRef, MovementStatesComponent.getComponentType());
        if (cartStates != null && cartStates.getMovementStates().jumping) return true;

        final var states = archetypeChunk.getComponent(index, MovementStatesComponent.getComponentType());
        return states != null && states.getMovementStates().jumping;
    }

    private void pullUp(@Nonnull final Store<EntityStore> store,
                        @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                        @Nonnull final Ref<EntityStore> player,
                        @Nonnull final Ref<EntityStore> cartRef,
                        @Nonnull final TransformComponent ledgeTransform,
                        final float yaw) {

        TitanLedgeSpawner.forward(yaw, forward);
        final var spot = new Vector3d(ledgeTransform.getPosition());
        spot.y += TitanLedge.PULL_UP;
        spot.sub(forward.x * TitanLedge.PULL_FORWARD, 0, forward.z * TitanLedge.PULL_FORWARD);

        clearHang(store, commandBuffer, player, cartRef);
        AnimationUtils.playAnimation(player, AnimationSlot.Movement, "MantleUp", true, commandBuffer);

        final var playerTransform = commandBuffer.getComponent(player, TransformComponent.getComponentType());
        final var rotation = playerTransform != null ? playerTransform.getRotation() : ledgeTransform.getRotation();
        commandBuffer.putComponent(player, Teleport.getComponentType(), new Teleport(spot, rotation));
    }

    private static void clearHang(@Nonnull final Store<EntityStore> store,
                                  @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                  @Nonnull final Ref<EntityStore> player,
                                  @Nullable final Ref<EntityStore> cartRef) {

        commandBuffer.tryRemoveComponent(player, MountedComponent.getComponentType());
        commandBuffer.tryRemoveComponent(player, TitanLedgeHangComponent.getComponentType());
        AnimationUtils.stopAnimation(player, AnimationSlot.Movement, true, commandBuffer);

        Ref<EntityStore> cart = cartRef;
        if (cart == null) {
            final var hang = store.getComponent(player, TitanLedgeHangComponent.getComponentType());
            if (hang != null) cart = hang.getCart();
        }
        if (cart != null && cart.isValid()
            && store.getComponent(cart, TitanLedgeCartComponent.getComponentType()) != null) {
            commandBuffer.removeEntity(cart, RemoveReason.REMOVE);
        }
    }

    private static void playClimbAnim(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                      @Nonnull final Ref<EntityStore> player,
                                      @Nonnull final TitanLedgeHangComponent hang,
                                      final float strafe) {

        final String anim;
        if (strafe > 0.15f) anim = "ClimbRight";
        else if (strafe < -0.15f) anim = "ClimbLeft";
        else anim = "ClimbIdle";

        if (anim.equals(hang.getLastAnim())) return;
        AnimationUtils.playAnimation(player, AnimationSlot.Movement, anim, true, commandBuffer);
        hang.setLastAnim(anim);
    }

    @Nullable
    private Ref<EntityStore> findNeighbor(@Nonnull final Store<EntityStore> store,
                                          @Nonnull final Ref<EntityStore> current,
                                          @Nonnull final TransformComponent currentTransform,
                                          final float currentYaw,
                                          final int sign) {

        TitanLedgeSpawner.right(currentYaw, right);
        final var origin = currentTransform.getPosition();
        final var probe = new Vector3d(origin).add(
            right.x * sign * TitanLedge.HALF_WIDTH, 0, right.z * sign * TitanLedge.HALF_WIDTH);

        Ref<EntityStore> best = null;
        double bestDist = Double.POSITIVE_INFINITY;

        for (final Ref<EntityStore> candidate : TargetUtil.getAllEntitiesInSphere(
            probe, TitanLedge.NEIGHBOR_SEARCH, store)) {
            if (!candidate.isValid() || candidate.equals(current)) continue;
            final var other = store.getComponent(candidate, TitanLedgeComponent.getComponentType());
            final var otherTransform = store.getComponent(candidate, TransformComponent.getComponentType());
            if (other == null || otherTransform == null) continue;

            final float otherYaw = otherTransform.getRotation().yaw();
            final var otherRight = new Vector3d();
            TitanLedgeSpawner.right(otherYaw, otherRight);
            final double align = Math.abs(right.x * otherRight.x + right.z * otherRight.z);
            if (align < TitanLedge.NEIGHBOR_ALIGN) continue;

            final var at = otherTransform.getPosition();
            if (Math.abs(at.y - origin.y) > TitanLedge.NEIGHBOR_Y_TOLERANCE) continue;

            final double along = (at.x - origin.x) * right.x + (at.z - origin.z) * right.z;
            if (sign > 0 && along <= 0) continue;
            if (sign < 0 && along >= 0) continue;

            final double gap = Math.abs(along) - TitanLedge.HALF_WIDTH - other.getHalfWidth();
            if (gap < -0.05 || gap > TitanLedge.TRANSFER_GAP) continue;

            final double dist = origin.distanceSquared(at);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }

        return best;
    }

    private static float clamp(final float value, final float min, final float max) {
        return Math.max(min, Math.min(max, value));
    }
}
