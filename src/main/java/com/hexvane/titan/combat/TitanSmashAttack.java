package com.hexvane.titan.combat;

import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.entity.TitanPartComponent;
import com.hexvane.titan.entity.TitanWeakpointComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;

/**
 * Area damage shared by every titan impact, whether it comes from a fist, the whole body, or a thrown
 * boulder.
 *
 * <p>The hit volume is a cylinder rather than a sphere, so higher ground near the impact offers no cover.
 * Knockback is aimed outwards and up; the vertical share is what separates the attacks, since a smash
 * mostly shoves outwards while a ground pound throws almost straight up.
 *
 * <p>Knockback values are speeds in blocks per second on every axis. See {@link #write} for how the
 * horizontal axes are normalized to make that hold.
 */
public final class TitanSmashAttack {

    /** Vertical extent of the damage cylinder, as a multiple of the blast radius. */
    private static final double HEIGHT_FACTOR = 1.2;
    /** Fraction of the knockback that goes straight up, for the attacks that do not say otherwise. */
    public static final double VERTICAL_SHARE = 0.45;

    private TitanSmashAttack() {
    }

    /**
     * Applies the area damage, knockback and effects for one impact.
     *
     * @param titanRef the titan dealing the damage, excluded along with all of its own parts
     * @return the number of entities hit
     */
    public static int execute(@Nonnull final Store<EntityStore> store,
                              @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                              @Nonnull final Ref<EntityStore> titanRef,
                              @Nonnull final TitanVariantAsset variant,
                              @Nonnull final Vector3d impactPoint) {
        return execute(store, commandBuffer, titanRef, variant, impactPoint, variant.getAttackRadius());
    }

    /** As {@link #execute}, with the blast radius overridden, since a body slam covers more ground than a fist. */
    public static int execute(@Nonnull final Store<EntityStore> store,
                              @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                              @Nonnull final Ref<EntityStore> titanRef,
                              @Nonnull final TitanVariantAsset variant,
                              @Nonnull final Vector3d impactPoint,
                              final double radius) {

        return execute(store, commandBuffer, titanRef, impactPoint, radius,
            variant.getAttackDamage(), variant.getAttackKnockback(), VERTICAL_SHARE,
            variant.getImpactParticle(), variant.getImpactSound());
    }

    /**
     * The impact every other overload delegates to, taking raw numbers instead of a variant.
     *
     * <p>Passing the numbers lets a boulder land correctly after the titan that threw it has died, and
     * lets the ground pound override the vertical share without affecting a smash.
     *
     * @param source        who to credit the damage to, and whose parts to spare. An invalid reference
     *                      means the attacker is gone, so the effects still play but nothing is hurt.
     * @param damage        before the server's damage multiplier
     * @param knockback     before the server's knockback multiplier
     * @param verticalShare how much of the knockback goes straight up, from 0 to 1
     * @return the number of entities hit
     */
    public static int execute(@Nonnull final Store<EntityStore> store,
                              @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                              @Nullable final Ref<EntityStore> source,
                              @Nonnull final Vector3d impactPoint,
                              final double radius,
                              final float damage,
                              final float knockback,
                              final double verticalShare,
                              @Nullable final String particle,
                              @Nullable final String sound) {

        int hits = 0;
        if (source != null && source.isValid()) {
            final int damageCauseIndex = DamageCause.getAssetMap().getIndex("Physical");
            final var config = TitanConfig.get();
            final float scaledDamage = damage * config.getAttackDamageMultiplier();
            final float scaledKnockback = knockback * config.getAttackKnockbackMultiplier();

            // The spatial query returns a shared thread-local list and dealing damage can run queries of its
            // own, so snapshot the results first.
            final var victims = new ArrayList<>(
                TargetUtil.getAllEntitiesInCylinder(impactPoint, radius, radius * HEIGHT_FACTOR, store));

            for (final Ref<EntityStore> victim : victims) {
                if (!isValidVictim(store, victim, source)) continue;

                DamageSystems.executeDamage(victim, commandBuffer,
                    new Damage(new Damage.EntitySource(source), damageCauseIndex, scaledDamage));

                applyKnockback(store, commandBuffer, victim, impactPoint, scaledKnockback, verticalShare);
                hits++;
            }
        }

        playEffects(commandBuffer, particle, sound, impactPoint);
        return hits;
    }

    private static boolean isValidVictim(@Nonnull final Store<EntityStore> store,
                                         @Nonnull final Ref<EntityStore> victim,
                                         @Nonnull final Ref<EntityStore> titanRef) {
        if (!victim.isValid() || victim.getIndex() == titanRef.getIndex()) return false;
        // The titan's own voxels and ore nodes sit inside the blast, and hitting them would kill the boss
        // with its own attack. Shattered boulder voxels carry the same part marker, so rubble is spared too.
        if (store.getComponent(victim, TitanPartComponent.getComponentType()) != null) return false;
        if (store.getComponent(victim, TitanWeakpointComponent.getComponentType()) != null) return false;
        return store.getComponent(victim, EntityStatMap.getComponentType()) != null;
    }

    /**
     * Throws one entity along a given vector, ignoring where it is standing.
     *
     * <p>Separate from the blast knockback because not every throw radiates from a point. Scraping riders
     * off a titan's back uses a single shared direction for everyone up there.
     */
    public static void impulse(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                               @Nonnull final Ref<EntityStore> victim,
                               @Nonnull final Vector3d velocity) {
        if (!victim.isValid()) return;

        write(commandBuffer, victim, new Vector3d(velocity).mul(TitanConfig.get().getAttackKnockbackMultiplier()));
    }

    private static void applyKnockback(@Nonnull final Store<EntityStore> store,
                                       @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                       @Nonnull final Ref<EntityStore> victim,
                                       @Nonnull final Vector3d impactPoint,
                                       final float strength,
                                       final double verticalShare) {
        final var transform = store.getComponent(victim, TransformComponent.getComponentType());
        if (transform == null) return;

        final var away = new Vector3d(transform.getPosition()).sub(impactPoint);
        away.y = 0;
        if (away.lengthSquared() < 1.0e-4) {
            away.set(0, 0, 1);
        } else {
            away.normalize();
        }
        away.mul(strength * (1.0 - verticalShare));
        away.y = strength * verticalShare;

        write(commandBuffer, victim, away);
    }

    /**
     * Hands one throw to the engine, in blocks per second.
     *
     * <p>The horizontal components are divided back down on the way out. {@link
     * DamageSystems.HackKnockbackValues} multiplies X and Z by {@code PLAYER_KNOCKBACK_SCALE} before the
     * velocity system sees them and leaves Y untouched, so without this a knockback of {@code 6} would
     * mean a small hop upwards and a hundred and fifty blocks sideways. Dividing it out keeps all three
     * axes in the same units.
     *
     * <p>Duration is zero because {@code KnockbackSystems} re-applies the velocity every tick until the
     * timer passes the duration, so any larger value would multiply the throw by the tick count. Zero is
     * the engine's convention for a single impulse.
     */
    private static void write(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                              @Nonnull final Ref<EntityStore> victim,
                              @Nonnull final Vector3d velocity) {

        final float hack = DamageSystems.HackKnockbackValues.PLAYER_KNOCKBACK_SCALE;
        if (hack > 0f) {
            velocity.x /= hack;
            velocity.z /= hack;
        }

        final var knockback = commandBuffer.ensureAndGetComponent(victim, KnockbackComponent.getComponentType());
        knockback.setVelocity(velocity);
        knockback.setVelocityType(ChangeVelocityType.Set);
        knockback.setDuration(0f);
        knockback.setTimer(0f);
    }

    private static void playEffects(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                    @Nullable final String particle,
                                    @Nullable final String sound,
                                    @Nonnull final Vector3d impactPoint) {
        if (particle != null && !particle.isEmpty()) {
            ParticleUtil.spawnParticleEffect(particle, impactPoint, commandBuffer);
        }
        TitanSound.play(commandBuffer, sound, impactPoint);
    }

    /** Where a smash should land: just in front of the titan, on the line towards its target. */
    @Nonnull
    public static Vector3d resolveImpactPoint(@Nonnull final Vector3d titanPosition,
                                              @Nullable final Vector3d targetPosition,
                                              final float yaw,
                                              final double reach,
                                              @Nonnull final Vector3d dest) {
        if (targetPosition != null) {
            dest.set(targetPosition).sub(titanPosition);
            dest.y = 0;
            final double length = dest.length();
            if (length > 1.0e-4) {
                dest.div(length).mul(Math.min(length, reach)).add(titanPosition);
                dest.y = targetPosition.y;
                return dest;
            }
        }
        // No target: slam straight ahead. Forward is -Z at yaw 0, matching the engine's look convention.
        return dest.set(
            titanPosition.x - Math.sin(yaw) * reach,
            titanPosition.y,
            titanPosition.z - Math.cos(yaw) * reach
        );
    }
}
