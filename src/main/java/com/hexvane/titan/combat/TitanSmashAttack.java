package com.hexvane.titan.combat;

import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.entity.TitanPartComponent;
import com.hexvane.titan.entity.TitanWeakpointComponent;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The ground slam that lands at the end of an arm swing.
 *
 * <p>Damage is a cylinder rather than a sphere so standing on higher ground near the impact does not save
 * you, and the knockback is aimed outwards and up to fling players clear.
 */
public final class TitanSmashAttack {

    /** Vertical extent of the damage cylinder, as a multiple of the blast radius. */
    private static final double HEIGHT_FACTOR = 1.2;
    /** Fraction of the knockback that goes straight up. */
    private static final double VERTICAL_SHARE = 0.45;

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

        final double radius = variant.getAttackRadius();
        final int damageCauseIndex = DamageCause.getAssetMap().getIndex("Physical");

        // The spatial query hands back a shared thread-local list, and dealing damage can run queries of its
        // own, so take a snapshot before touching anything.
        final var victims = new java.util.ArrayList<>(
            TargetUtil.getAllEntitiesInCylinder(impactPoint, radius, radius * HEIGHT_FACTOR, store));

        int hits = 0;
        for (final Ref<EntityStore> victim : victims) {
            if (!isValidVictim(store, victim, titanRef)) continue;

            DamageSystems.executeDamage(victim, commandBuffer,
                new Damage(new Damage.EntitySource(titanRef), damageCauseIndex, variant.getAttackDamage()));

            applyKnockback(store, commandBuffer, victim, impactPoint, variant.getAttackKnockback());
            hits++;
        }

        playEffects(store, commandBuffer, variant, impactPoint);
        return hits;
    }

    private static boolean isValidVictim(@Nonnull final Store<EntityStore> store,
                                         @Nonnull final Ref<EntityStore> victim,
                                         @Nonnull final Ref<EntityStore> titanRef) {
        if (!victim.isValid() || victim.getIndex() == titanRef.getIndex()) return false;
        // The titan's own voxels and ore nodes sit inside the blast; hitting them would kill the boss on
        // its own attack.
        if (store.getComponent(victim, TitanPartComponent.getComponentType()) != null) return false;
        if (store.getComponent(victim, TitanWeakpointComponent.getComponentType()) != null) return false;
        return store.getComponent(victim, EntityStatMap.getComponentType()) != null;
    }

    private static void applyKnockback(@Nonnull final Store<EntityStore> store,
                                       @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                       @Nonnull final Ref<EntityStore> victim,
                                       @Nonnull final Vector3d impactPoint,
                                       final float strength) {
        final var transform = store.getComponent(victim, TransformComponent.getComponentType());
        if (transform == null) return;

        final var away = new Vector3d(transform.getPosition()).sub(impactPoint);
        away.y = 0;
        if (away.lengthSquared() < 1.0e-4) {
            away.set(0, 0, 1);
        } else {
            away.normalize();
        }
        away.mul(strength * (1.0 - VERTICAL_SHARE));
        away.y = strength * VERTICAL_SHARE;

        // One-shot: KnockbackSystems re-applies the velocity every tick until the timer passes the duration,
        // so a non-zero duration would multiply the impulse by the tick count and launch the victim into
        // unloaded terrain. Duration zero is the engine's own convention for a single impulse.
        final var knockback = commandBuffer.ensureAndGetComponent(victim, KnockbackComponent.getComponentType());
        knockback.setVelocity(away);
        knockback.setVelocityType(ChangeVelocityType.Set);
        knockback.setDuration(0f);
        knockback.setTimer(0f);
    }

    private static void playEffects(@Nonnull final Store<EntityStore> store,
                                    @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                    @Nonnull final TitanVariantAsset variant,
                                    @Nonnull final Vector3d impactPoint) {
        final String particle = variant.getImpactParticle();
        if (particle != null && !particle.isEmpty()) {
            ParticleUtil.spawnParticleEffect(particle, impactPoint, commandBuffer);
        }

        final String sound = variant.getImpactSound();
        if (sound != null && !sound.isEmpty()) {
            final int index = SoundEvent.getAssetMap().getIndex(sound);
            if (index != SoundEvent.EMPTY_ID) {
                SoundUtil.playSoundEvent3d(null, index, impactPoint.x, impactPoint.y, impactPoint.z, commandBuffer);
            }
        }
    }

    /**
     * Where a smash should land: just in front of the titan, on the line towards its target.
     */
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
