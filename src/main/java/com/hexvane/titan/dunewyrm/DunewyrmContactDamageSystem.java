package com.hexvane.titan.dunewyrm;

import com.hypixel.hytale.server.core.modules.physics.systems.IVelocityModifyingSystem;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.combat.TitanSound;
import com.hexvane.titan.combat.TitanImpulse;
import com.hexvane.titan.combat.TitanStandingOn;
import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.ik.GroundSampler;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Periodic damage to players overlapping the Dunewyrm's body while it is above ground.
 */
public final class DunewyrmContactDamageSystem extends EntityTickingSystem<EntityStore> implements IVelocityModifyingSystem {

    /**
     * Players that already had knockback applied this world tick. Shared across every snake so a split
     * pair cannot both {@code put}/{@code ensure} KnockbackComponent in the same flush.
     */
    @Nonnull
    private static final IntOpenHashSet KNOCKBACK_THIS_TICK = new IntOpenHashSet();
    private static long knockbackTickKey = Long.MIN_VALUE;

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(
        DunewyrmComponent.getComponentType(),
        TransformComponent.getComponentType());

    @Nonnull
    private final Vector3d push = new Vector3d();
    @Nonnull
    private final Vector3d probe = new Vector3d();
    @Nonnull
    private final Vector3d eject = new Vector3d();
    @Nonnull
    private final IntOpenHashSet hitPlayers = new IntOpenHashSet();

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(final float dt,
                     final int index,
                     @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull final Store<EntityStore> store,
                     @Nonnull final CommandBuffer<EntityStore> commandBuffer) {

        final var worm = archetypeChunk.getComponent(index, DunewyrmComponent.getComponentType());
        final Ref<EntityStore> root = archetypeChunk.getReferenceTo(index);
        if (worm == null) return;
        if (worm.getState() == DunewyrmState.TUNNEL || worm.getState() == DunewyrmState.DYING) return;

        // World ticks are single-threaded; a monotonic key per system pass is enough to share the set.
        final long tickKey = store.getExternalData().getWorld().getTick();
        if (tickKey != knockbackTickKey) {
            knockbackTickKey = tickKey;
            KNOCKBACK_THIS_TICK.clear();
        }

        final var variant = worm.getVariant();
        final int cause = DamageCause.getAssetMap().getIndex("Physical");

        // The ram runs every tick: at charge speed the head crosses a player in a few ticks, so a
        // 0.45 s damage interval simply missed them, and the collider then carried them along inside it.
        if (worm.getState() == DunewyrmState.CHARGE
            && worm.getStateTimer() >= DunewyrmTuning.CHARGE_WINDUP) {
            ram(worm, root, store, commandBuffer, variant, cause);
        }

        unstick(worm, store, commandBuffer);

        worm.setContactTimer(worm.getContactTimer() - dt);
        if (worm.getContactTimer() > 0f) return;
        worm.setContactTimer(DunewyrmTuning.CONTACT_INTERVAL);

        final float damageAmount = variant != null
            ? Math.max(DunewyrmTuning.CONTACT_DAMAGE, variant.getAttackDamage() * 0.35f)
            : DunewyrmTuning.CONTACT_DAMAGE;
        final float scaled = damageAmount * TitanConfig.get().getAttackDamageMultiplier() * worm.damageScale();
        hitPlayers.clear();

        // Players outer, segments inner: a spatial query around each segment returned that segment's own
        // voxels by the dozen, and there are only ever a few players to test against a dozen joints.
        DunewyrmPlayers.forEach(store, (victim, feet) -> {
            final int victimIndex = victim.getIndex();
            // Already took the ram this charge — don't stack the brush damage on top.
            if (worm.getRammedPlayers().contains(victimIndex)) return;

            for (final DunewyrmSegment segment : worm.getSegments()) {
                if (segment.getRole() != DunewyrmSegmentRole.BODY
                    && segment.getRole() != DunewyrmSegmentRole.HEAD) {
                    continue;
                }
                if (!within(feet, segment.getPosition(), DunewyrmTuning.CONTACT_RADIUS)) continue;
                if (!hitPlayers.add(victimIndex)) return;

                // Riders take no contact damage — the platform already threatens them with motion. A rider
                // has their feet above the segment's ground line; someone at ground level beside (or wedged
                // inside) the flank is standing next to climbable voxels too, and must not be spared.
                final boolean rider = feet.y - segment.getPosition().y >= DunewyrmTuning.RIDER_MIN_HEIGHT
                    && (TitanStandingOn.isAboveSegment(feet, segment.getPosition())
                    || TitanStandingOn.isOnClimbable(store, victim));
                if (rider) return;

                DamageSystems.executeDamage(victim, commandBuffer,
                    new Damage(new Damage.EntitySource(root), cause, scaled));
                applyKnockback(store, commandBuffer, victim, segment.getPosition(), segment.getYaw(), 1f);
                return;
            }
        });
    }

    /**
     * Every tick: anyone at ground level inside a body segment's footprint is put back on the sand beside
     * it. No damage or shove here (the timed brush pass does that); this only stops the collider carrying
     * a wedged player along inside the body until the next brush tick gets to them.
     */
    private void unstick(@Nonnull final DunewyrmComponent worm,
                         @Nonnull final Store<EntityStore> store,
                         @Nonnull final CommandBuffer<EntityStore> commandBuffer) {
        DunewyrmPlayers.forEach(store, (victim, feet) -> {
            for (final DunewyrmSegment segment : worm.getSegments()) {
                if (segment.getRole() != DunewyrmSegmentRole.BODY) continue;
                final Vector3d from = segment.getPosition();
                if (feet.y - from.y >= DunewyrmTuning.RIDER_MIN_HEIGHT || feet.y < from.y - 1.5) continue;
                final double fx = DunewyrmAiSystem.forwardX(segment.getYaw());
                final double fz = DunewyrmAiSystem.forwardZ(segment.getYaw());
                final double dx = feet.x - from.x;
                final double dz = feet.z - from.z;
                final double along = dx * fx + dz * fz;
                if (Math.abs(along) > DunewyrmTuning.SEGMENT_SPACING * 0.5) continue;
                final double across = fx * dz - fz * dx;
                if (Math.abs(across) >= DunewyrmTuning.BODY_EJECT_TRAP_WIDTH) continue;

                final var victimTransform = store.getComponent(victim, TransformComponent.getComponentType());
                if (victimTransform == null) return;
                final double sign = Math.abs(across) < 0.15
                    ? ((victim.getIndex() & 1) == 0 ? 1 : -1)
                    : Math.signum(across);
                push.set(-fz * sign, 0, fx * sign);
                probe.set(from.x + fx * along, from.y, from.z + fz * along);
                ejectBeside(store, commandBuffer, victim, victimTransform, probe, DunewyrmTuning.BODY_EJECT_DISTANCE);
                return;
            }
        });
    }

    /** Cylinder test matching the old spatial query: horizontal radius and the same vertical reach. */
    private static boolean within(@Nonnull final Vector3d point,
                                  @Nonnull final Vector3d centre,
                                  final double radius) {
        final double dx = point.x - centre.x;
        final double dz = point.z - centre.z;
        return dx * dx + dz * dz <= radius * radius && Math.abs(point.y - centre.y) <= radius;
    }

    /**
     * Head-on hit from a charging head: one full-damage strike per player per charge, and a shove
     * sideways off the charge line every tick they overlap the head, so they are thrown clear instead of
     * being dragged along inside the collider.
     */
    private void ram(@Nonnull final DunewyrmComponent worm,
                     @Nonnull final Ref<EntityStore> root,
                     @Nonnull final Store<EntityStore> store,
                     @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                     @Nullable final TitanVariantAsset variant,
                     final int cause) {

        final float yaw = worm.getYaw();
        final double fx = DunewyrmAiSystem.forwardX(yaw);
        final double fz = DunewyrmAiSystem.forwardZ(yaw);
        final Vector3d head = worm.getHeadPosition();
        // Lead the probe a little so the hit lands on the snout, not once the player is already inside.
        probe.set(head.x + fx * DunewyrmTuning.RAM_LEAD, head.y, head.z + fz * DunewyrmTuning.RAM_LEAD);

        final float amount = (variant != null ? variant.getAttackDamage() : DunewyrmTuning.CONTACT_DAMAGE)
            * TitanConfig.get().getAttackDamageMultiplier() * worm.damageScale();
        final boolean[] struck = {false};

        DunewyrmPlayers.forEach(store, (victim, feet) -> {
            if (!within(feet, probe, DunewyrmTuning.RAM_RADIUS)) return;
            // Only a player genuinely on top of the skull is spared. Ground level beside it is not
            // "riding", and neither is being lifted a block or two while wedged inside the mouth.
            if (feet.y - head.y >= DunewyrmTuning.RAM_SPARE_HEIGHT) return;

            final int victimIndex = victim.getIndex();
            if (worm.getRammedPlayers().add(victimIndex)) {
                DamageSystems.executeDamage(victim, commandBuffer,
                    new Damage(new Damage.EntitySource(root), cause, amount));
                struck[0] = true;
            }
            applySideKnockback(store, commandBuffer, victim, head, fx, fz);
        });

        if (struck[0]) {
            TitanSound.play(commandBuffer,
                variant != null ? variant.getImpactSound() : DunewyrmTuning.BREAK_SOUND, head);
        }
    }

    /** Throws the player off the charge line, to whichever side they are already on. */
    private void applySideKnockback(@Nonnull final Store<EntityStore> store,
                                    @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                    @Nonnull final Ref<EntityStore> victim,
                                    @Nonnull final Vector3d head,
                                    final double fx, final double fz) {
        final int victimIndex = victim.getIndex();
        if (!KNOCKBACK_THIS_TICK.add(victimIndex)) return;

        final var victimTransform = store.getComponent(victim, TransformComponent.getComponentType());
        if (victimTransform == null) return;

        // Side of the charge line the player is on: sign of the 2D cross product forward x toPlayer.
        final double dx = victimTransform.getPosition().x - head.x;
        final double dz = victimTransform.getPosition().z - head.z;
        double side = fx * dz - fz * dx;
        if (Math.abs(side) < 0.15) side = (victimIndex & 1) == 0 ? 1 : -1;
        final double sign = Math.signum(side);

        // Mostly sideways, a touch forward so they clear the snout rather than clipping its flank.
        push.set(-fz * sign + fx * 0.35, 0, fx * sign + fz * 0.35).normalize();

        // Already enveloped by the skull: velocity alone cannot win against a collider advancing a block a
        // tick from every side, so put them back on the ground beside the head first, then shove.
        final double horizSq = dx * dx + dz * dz;
        if (horizSq < DunewyrmTuning.RAM_EJECT_RADIUS * DunewyrmTuning.RAM_EJECT_RADIUS) {
            ejectBeside(store, commandBuffer, victim, victimTransform, head, DunewyrmTuning.RAM_EJECT_DISTANCE);
        }

        final float strength = DunewyrmTuning.RAM_KNOCKBACK * TitanConfig.get().getAttackKnockbackMultiplier();
        push.mul(strength);
        push.y = DunewyrmTuning.RAM_LIFT;
        writeKnockback(commandBuffer, victim);
    }

    private void applyKnockback(@Nonnull final Store<EntityStore> store,
                                @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                @Nonnull final Ref<EntityStore> victim,
                                @Nonnull final Vector3d from,
                                final float yaw,
                                final float multiplier) {

        final int victimIndex = victim.getIndex();
        if (!KNOCKBACK_THIS_TICK.add(victimIndex)) return;

        final var victimTransform = store.getComponent(victim, TransformComponent.getComponentType());
        if (victimTransform == null) return;

        // Where the player sits relative to the segment's axis: along it, and across it (signed side).
        final Vector3d feet = victimTransform.getPosition();
        final double fx = DunewyrmAiSystem.forwardX(yaw);
        final double fz = DunewyrmAiSystem.forwardZ(yaw);
        final double dx = feet.x - from.x;
        final double dz = feet.z - from.z;
        final double along = dx * fx + dz * fz;
        final double across = fx * dz - fz * dx;

        if (Math.abs(across) < DunewyrmTuning.BODY_EJECT_HALF_WIDTH
            && Math.abs(along) < DunewyrmTuning.SEGMENT_SPACING * 0.5 + 1.0
            && feet.y - from.y < DunewyrmTuning.RIDER_MIN_HEIGHT) {
            // Wedged in the flank between the core and the skin voxels. Shove straight out the side they
            // are already on; if they are deep enough that the collider will just carry them, teleport them
            // to the ground beside the body first (same treatment as the charging head).
            final double sign = Math.abs(across) < 0.15 ? ((victimIndex & 1) == 0 ? 1 : -1) : Math.signum(across);
            push.set(-fz * sign, 0, fx * sign);
            probe.set(from.x + fx * along, from.y, from.z + fz * along);
            if (Math.abs(across) < DunewyrmTuning.BODY_EJECT_TRAP_WIDTH) {
                ejectBeside(store, commandBuffer, victim, victimTransform, probe,
                    DunewyrmTuning.BODY_EJECT_DISTANCE);
            }
        } else {
            push.set(feet).sub(from);
            push.y = 0;
            if (push.lengthSquared() < 1e-4) {
                push.set(fx, 0, fz);
            } else {
                push.normalize();
            }
        }
        final float strength = DunewyrmTuning.CONTACT_KNOCKBACK * multiplier
            * TitanConfig.get().getAttackKnockbackMultiplier();
        push.mul(strength);
        push.y = Math.min(0.12f, strength * 0.35f);
        writeKnockback(commandBuffer, victim);
    }

    /**
     * Teleports the victim {@code distance} blocks from {@code anchor} along the current {@link #push}
     * direction, snapped to the ground there.
     */
    private void ejectBeside(@Nonnull final Store<EntityStore> store,
                             @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                             @Nonnull final Ref<EntityStore> victim,
                             @Nonnull final TransformComponent victimTransform,
                             @Nonnull final Vector3d anchor,
                             final double distance) {
        final double x = anchor.x + push.x * distance;
        final double z = anchor.z + push.z * distance;
        final double ground = GroundSampler.sample(
            store.getExternalData().getWorld().getChunkStore(), x, anchor.y + 2.0, z, 6, 8);
        eject.set(
            x,
            GroundSampler.isValid(ground) ? ground + 0.1 : Math.max(victimTransform.getPosition().y, anchor.y),
            z);
        commandBuffer.putComponent(victim, Teleport.getComponentType(),
            new Teleport(eject, victimTransform.getRotation()).withoutVelocityReset());
    }

    /** Queues a copy of {@link #push}; the scratch vector is reused for the next victim. */
    private void writeKnockback(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                @Nonnull final Ref<EntityStore> victim) {
        TitanImpulse.set(commandBuffer, victim, push);
    }
}
