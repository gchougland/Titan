package com.hexvane.titan.dunewyrm;

import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.combat.TitanSound;
import com.hexvane.titan.combat.TitanTelegraph;
import com.hexvane.titan.ik.GroundSampler;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dunewyrm state machine: wide orbit slither, charge, cobra poison breath, tunnel dig, tongue flicker.
 */
public final class DunewyrmAiSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(
        DunewyrmComponent.getComponentType(),
        TransformComponent.getComponentType());

    @Nonnull
    private final Vector3d scratch = new Vector3d();
    @Nonnull
    private final Vector3d avoid = new Vector3d();
    @Nonnull
    private final Vector3d orbitGoal = new Vector3d();

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
        final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (worm == null || transform == null) return;
        if (worm.getState() == DunewyrmState.DYING || worm.isPendingStructural()) return;

        final TitanVariantAsset variant = worm.getVariant();
        if (variant == null) return;

        worm.tickAttackCooldown(dt);
        worm.tickFleeTimer(dt);
        worm.addStateTimer(dt);
        worm.addSinePhase(dt);
        tickTongue(worm, dt, commandBuffer);
        tickPoisonCloud(worm, store, commandBuffer, dt);

        acquireTarget(worm, store, variant);
        maybeStartTunnel(worm, commandBuffer);

        if (worm.getFleeTimer() > 0f) {
            tickFlee(worm, store, variant, dt);
        } else {
            switch (worm.getState()) {
                case IDLE -> tickIdle(worm, commandBuffer, variant);
                case SLITHER -> tickSlither(worm, store, commandBuffer, variant, dt);
                case CHARGE -> tickCharge(worm, store, commandBuffer, variant, dt);
                case COBRA -> tickCobra(worm, store, commandBuffer, variant, dt);
                case TUNNEL -> tickTunnel(worm, store, commandBuffer, variant, dt);
                default -> {
                }
            }
        }

        worm.getPath().push(
            worm.getHeadPosition().x, worm.getHeadPosition().y, worm.getHeadPosition().z, worm.getYaw());
        DunewyrmSpawner.layoutAlongPath(worm);
        worm.setSegmentsDirty(true);

        transform.getPosition().set(worm.getHeadPosition());
        transform.getRotation().set(0, worm.getYaw(), 0);
    }

    private void acquireTarget(@Nonnull final DunewyrmComponent worm,
                               @Nonnull final Store<EntityStore> store,
                               @Nonnull final TitanVariantAsset variant) {
        final Ref<EntityStore> current = worm.getTarget();
        if (current != null && current.isValid()) {
            final var t = store.getComponent(current, TransformComponent.getComponentType());
            if (t != null
                && t.getPosition().distanceSquared(worm.getHeadPosition())
                <= variant.getLoseTargetRadius() * variant.getLoseTargetRadius()) {
                return;
            }
        }
        worm.setTarget(null);

        final double wake = variant.getWakeRadius();
        Ref<EntityStore> best = null;
        double bestDist = wake * wake;
        for (final Ref<EntityStore> candidate : TargetUtil.getAllEntitiesInCylinder(
            worm.getHeadPosition(), wake, wake, store)) {
            if (store.getComponent(candidate, Player.getComponentType()) == null) continue;
            final var t = store.getComponent(candidate, TransformComponent.getComponentType());
            if (t == null) continue;
            final double d = t.getPosition().distanceSquared(worm.getHeadPosition());
            if (d < bestDist) {
                bestDist = d;
                best = candidate;
            }
        }
        worm.setTarget(best);
    }

    private void tickIdle(@Nonnull final DunewyrmComponent worm,
                          @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                          @Nonnull final TitanVariantAsset variant) {
        if (worm.getTarget() != null) {
            worm.setState(DunewyrmState.SLITHER);
            TitanSound.play(commandBuffer, variant.getWakeSound(), worm.getHeadPosition());
        }
    }

    private void tickFlee(@Nonnull final DunewyrmComponent worm,
                          @Nonnull final Store<EntityStore> store,
                          @Nonnull final TitanVariantAsset variant,
                          final float dt) {
        final float yaw = worm.getYaw();
        final float speed = variant.getMoveSpeed() * 1.35f;
        final Vector3d head = worm.getHeadPosition();
        final double ox = head.x;
        final double oy = head.y;
        final double oz = head.z;
        if (!tryPlaceHead(worm, store,
            ox + forwardX(yaw) * speed * dt,
            oy,
            oz + forwardZ(yaw) * speed * dt,
            true)) {
            head.set(ox, oy, oz);
        }
        worm.setCobraRise(approach(worm.getCobraRise(), 0f, 8f * dt));
        worm.setCobraLean(approach(worm.getCobraLean(), 0f, 8f * dt));
        worm.setTunnelDepth(approach(worm.getTunnelDepth(), 0f, 5f * dt));
    }

    private void tickSlither(@Nonnull final DunewyrmComponent worm,
                             @Nonnull final Store<EntityStore> store,
                             @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                             @Nonnull final TitanVariantAsset variant,
                             final float dt) {

        final Ref<EntityStore> target = worm.getTarget();
        if (target == null || !target.isValid()) {
            worm.setState(DunewyrmState.IDLE);
            return;
        }

        final var targetTransform = store.getComponent(target, TransformComponent.getComponentType());
        if (targetTransform == null) {
            worm.setTarget(null);
            worm.setState(DunewyrmState.IDLE);
            return;
        }

        final Vector3d targetPos = targetTransform.getPosition();
        worm.addOrbitAngle(DunewyrmTuning.ORBIT_SPEED * dt);

        final float radius = worm.getOrbitRadius();
        orbitGoal.set(
            targetPos.x + Math.cos(worm.getOrbitAngle()) * radius,
            worm.getHeadPosition().y,
            targetPos.z + Math.sin(worm.getOrbitAngle()) * radius);

        // Softly keep a standoff: if too close, push the goal further out on the same radial.
        final double distToPlayer = horizontalDistance(worm.getHeadPosition(), targetPos);
        if (distToPlayer < radius * 0.55) {
            final double dx = worm.getHeadPosition().x - targetPos.x;
            final double dz = worm.getHeadPosition().z - targetPos.z;
            final double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1e-4) {
                orbitGoal.set(
                    targetPos.x + dx / len * radius * 1.2,
                    worm.getHeadPosition().y,
                    targetPos.z + dz / len * radius * 1.2);
            }
        }

        float desired = yawToward(worm.getHeadPosition(), orbitGoal);
        desired = blendSelfAvoidance(worm, desired);
        worm.setYaw(turnToward(worm.getYaw(), desired, turnStep(variant, dt)));

        final float sine = (float) Math.sin(worm.getSinePhase() * DunewyrmTuning.SLITHER_SINE_FREQ)
            * DunewyrmTuning.SLITHER_SINE_AMP;
        final float yaw = worm.getYaw();
        final double forwardX = forwardX(yaw);
        final double forwardZ = forwardZ(yaw);
        final double sideX = -forwardZ;
        final double sideZ = forwardX;
        advanceHead(worm, store, forwardX, forwardZ, sideX, sideZ, variant.getMoveSpeed(), sine, dt);

        worm.setCobraRise(approach(worm.getCobraRise(), 0f, 6f * dt));
        worm.setCobraLean(approach(worm.getCobraLean(), 0f, 6f * dt));
        worm.setJawOpen(approach(worm.getJawOpen(), 0f, 4f * dt));
        worm.setTunnelDepth(approach(worm.getTunnelDepth(), 0f, 5f * dt));

        if (worm.getAttackCooldown() > 0f) return;

        final double dist = horizontalDistance(worm.getHeadPosition(), targetPos);
        final float chargeRange = variant.getAttackRange();
        final float facingPlayer = yawToward(worm.getHeadPosition(), targetPos);
        final float facingDelta = Math.abs(wrapAngle(facingPlayer - yaw));
        final float roll = ThreadLocalRandom.current().nextFloat();
        final boolean canPoison = worm.bodyCount() >= DunewyrmTuning.MIN_BODIES_FOR_POISON
            && dist <= DunewyrmTuning.POISON_ATTACK_RANGE;

        // Charge on a lined-up pass in charge range; poison only when a player is inside the spray.
        if (facingDelta < 0.7f && dist <= chargeRange && roll < DunewyrmTuning.CHARGE_CHANCE) {
            worm.setChargeYaw(yaw);
            worm.setState(DunewyrmState.CHARGE);
            worm.setAttackCooldown(DunewyrmTuning.ATTACK_COOLDOWN);
            TitanSound.play(commandBuffer, variant.getTelegraphSound(), worm.getHeadPosition());
        } else if (canPoison && facingDelta < 1.0f && roll < DunewyrmTuning.COBRA_CHANCE) {
            worm.setState(DunewyrmState.COBRA);
            worm.setAttackCooldown(DunewyrmTuning.ATTACK_COOLDOWN + DunewyrmTuning.COBRA_COOLDOWN_EXTRA);
            TitanSound.play(commandBuffer, variant.getTelegraphSound(), worm.getHeadPosition());
        }
    }

    private void tickCharge(@Nonnull final DunewyrmComponent worm,
                            @Nonnull final Store<EntityStore> store,
                            @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                            @Nonnull final TitanVariantAsset variant,
                            final float dt) {

        final float t = worm.getStateTimer();
        if (t < DunewyrmTuning.CHARGE_WINDUP) {
            telegraphCharge(worm, store, commandBuffer, variant, DunewyrmTuning.CHARGE_WINDUP - t, dt);
            worm.setCobraRise(approach(worm.getCobraRise(), 0f, 8f * dt));
            worm.setCobraLean(approach(worm.getCobraLean(), 0f, 8f * dt));
            worm.setTunnelDepth(approach(worm.getTunnelDepth(), 0f, 5f * dt));
            return;
        }

        // Stay committed to the charge heading; only nudge slightly around bodies.
        float yaw = turnToward(worm.getYaw(), worm.getChargeYaw(), turnStep(variant, dt));
        final float avoided = blendSelfAvoidance(worm, yaw);
        yaw = turnToward(yaw, avoided, DunewyrmTuning.MAX_TURN_STEP * 0.35f);
        worm.setYaw(yaw);
        final float speed = variant.getMoveSpeed() * DunewyrmTuning.CHARGE_SPEED_MULT;
        final Vector3d head = worm.getHeadPosition();
        final double ox = head.x;
        final double oy = head.y;
        final double oz = head.z;
        if (!tryPlaceHead(worm, store,
            ox + forwardX(yaw) * speed * dt,
            oy,
            oz + forwardZ(yaw) * speed * dt,
            true)) {
            head.set(ox, oy, oz);
        }
        worm.setCobraRise(approach(worm.getCobraRise(), 0f, 8f * dt));
        worm.setCobraLean(approach(worm.getCobraLean(), 0f, 8f * dt));
        worm.setTunnelDepth(approach(worm.getTunnelDepth(), 0f, 5f * dt));

        if (t >= DunewyrmTuning.CHARGE_WINDUP + DunewyrmTuning.CHARGE_DURATION) {
            worm.setState(DunewyrmState.SLITHER);
            // Small orbit shift — wide arc into the next pass, not a half-circle whip.
            worm.addOrbitAngle((float) (Math.PI * 0.25));
        }
    }

    private void telegraphCharge(@Nonnull final DunewyrmComponent worm,
                                 @Nonnull final Store<EntityStore> store,
                                 @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                 @Nonnull final TitanVariantAsset variant,
                                 final float remaining,
                                 final float dt) {
        if (!worm.consumePulse(dt, TitanTelegraph.pulseInterval(remaining))) return;

        final float yaw = worm.getChargeYaw();
        final double beam = DunewyrmTuning.CHARGE_TELEGRAPH_LENGTH;
        // Centre the strip half a beam ahead of the snout so none of it draws behind the head.
        scratch.set(
            worm.getHeadPosition().x + forwardX(yaw) * beam * 0.5,
            worm.getHeadPosition().y,
            worm.getHeadPosition().z + forwardZ(yaw) * beam * 0.5);
        TitanTelegraph.ring(
            commandBuffer,
            store.getExternalData().getWorld().getChunkStore(),
            variant.getTelegraphLineParticle(),
            scratch,
            1.0,
            yaw);
    }

    private void tickCobra(@Nonnull final DunewyrmComponent worm,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                           @Nonnull final TitanVariantAsset variant,
                           final float dt) {

        final float t = worm.getStateTimer();

        // Wind-up: rear up, pull the head back into an S, telegraph the poison circle.
        if (t < DunewyrmTuning.COBRA_WINDUP) {
            worm.setCobraRise(approach(worm.getCobraRise(), DunewyrmTuning.COBRA_RISE, 14f * dt));
            worm.setJawOpen(approach(worm.getJawOpen(), DunewyrmTuning.JAW_OPEN_ANGLE * 0.4f, 2f * dt));
            worm.setCobraLean(approach(worm.getCobraLean(), -DunewyrmTuning.COBRA_RECOIL, 10f * dt));
            telegraphPoison(worm, store, commandBuffer, variant,
                DunewyrmTuning.COBRA_WINDUP - t,
                Math.min(1f, t / Math.max(0.05f, DunewyrmTuning.COBRA_WINDUP)),
                dt);
        } else if (t < DunewyrmTuning.COBRA_SPRAY_END) {
            // Strike forward and dump poison.
            worm.setCobraRise(approach(worm.getCobraRise(), DunewyrmTuning.COBRA_RISE * 0.85f, 6f * dt));
            worm.setCobraLean(approach(worm.getCobraLean(), DunewyrmTuning.COBRA_LEAN, 8f * dt));
            worm.setJawOpen(approach(worm.getJawOpen(), DunewyrmTuning.JAW_OPEN_ANGLE, 4f * dt));

            if (t >= DunewyrmTuning.COBRA_SPRAY_START) {
                if (worm.getPoisonCloud() == null) {
                    final Vector3d cloud = mouthPoint(worm, 5.0);
                    cloud.y = worm.getHeadPosition().y + 0.05;
                    worm.setPoisonCloud(cloud);
                    worm.setPoisonTimer(DunewyrmTuning.POISON_LINGER);
                }
                sprayPoisonBreath(worm, commandBuffer, dt);
            }
        } else {
            worm.setCobraLean(approach(worm.getCobraLean(), 0f, 5f * dt));
            worm.setJawOpen(approach(worm.getJawOpen(), 0f, 3f * dt));
            worm.setCobraRise(approach(worm.getCobraRise(), DunewyrmTuning.COBRA_RISE * 0.35f, 4f * dt));
        }

        // Face the target while breathing.
        final Ref<EntityStore> target = worm.getTarget();
        if (target != null && target.isValid()) {
            final var tt = store.getComponent(target, TransformComponent.getComponentType());
            if (tt != null) {
                worm.setYaw(turnToward(worm.getYaw(),
                    yawToward(worm.getHeadPosition(), tt.getPosition()),
                    turnStep(variant, dt) * 0.7f));
            }
        }

        if (t >= DunewyrmTuning.COBRA_DURATION) {
            worm.setState(DunewyrmState.SLITHER);
            worm.setJawOpen(0f);
            worm.setCobraLean(0f);
            worm.addOrbitAngle((float) (Math.PI * 0.2));
        }
    }

    private void telegraphPoison(@Nonnull final DunewyrmComponent worm,
                                 @Nonnull final Store<EntityStore> store,
                                 @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                 @Nonnull final TitanVariantAsset variant,
                                 final float remaining,
                                 final float fillProgress,
                                 final float dt) {
        if (!worm.consumePulse(dt, TitanTelegraph.pulseInterval(remaining))) return;

        final Vector3d centre = mouthPoint(worm, 4.0);
        final var chunkStore = store.getExternalData().getWorld().getChunkStore();
        final double radius = DunewyrmTuning.POISON_RADIUS;
        // Outer outline stays full-size; fill grows into it over the windup.
        TitanTelegraph.ring(commandBuffer, chunkStore, variant.getTelegraphRingParticle(),
            centre, radius, worm.getYaw());
        final double fillRadius = Math.max(0.35, radius * Math.max(0.08, fillProgress));
        TitanTelegraph.ring(commandBuffer, chunkStore, variant.getTelegraphFillParticle(),
            centre, fillRadius, worm.getYaw());
    }

    private void sprayPoisonBreath(@Nonnull final DunewyrmComponent worm,
                                   @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                   final float dt) {
        final float yaw = worm.getYaw();
        final Vector3d mouth = mouthPoint(worm, DunewyrmTuning.TONGUE_MOUTH + 0.8);

        ParticleUtil.spawnParticleEffect(
            DunewyrmTuning.POISON_CONE_PARTICLE, mouth, yaw, -0.2f, 0f,
            DunewyrmTuning.POISON_CONE_SCALE, 1.4f, commandBuffer);
        ParticleUtil.spawnParticleEffect(
            DunewyrmTuning.POISON_CONE_PARTICLE, mouth, yaw + 0.25f, 0f, 0f,
            DunewyrmTuning.POISON_CONE_SCALE * 0.9f, 1.1f, commandBuffer);
        ParticleUtil.spawnParticleEffect(
            DunewyrmTuning.POISON_CONE_PARTICLE, mouth, yaw - 0.25f, 0f, 0f,
            DunewyrmTuning.POISON_CONE_SCALE * 0.9f, 1.1f, commandBuffer);

        final int puffs = 2 + (ThreadLocalRandom.current().nextFloat() < 0.5f ? 1 : 0);
        for (int i = 0; i < puffs; i++) {
            final float side = (ThreadLocalRandom.current().nextFloat() - 0.5f) * 1.1f;
            final float dist = 2.5f + ThreadLocalRandom.current().nextFloat() * 5.5f;
            final double fx = forwardX(yaw + side);
            final double fz = forwardZ(yaw + side);
            scratch.set(
                mouth.x + fx * dist,
                worm.getHeadPosition().y + 0.15,
                mouth.z + fz * dist);
            spawnPoisonParticle(commandBuffer, scratch, yaw + side);
        }
        if (ThreadLocalRandom.current().nextFloat() < dt * 2f) {
            TitanSound.play(commandBuffer, DunewyrmTuning.COBRA_SOUND, worm.getHeadPosition());
        }
    }

    private static void spawnPoisonParticle(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                            @Nonnull final Vector3d pos,
                                            final float yaw) {
        ParticleUtil.spawnParticleEffect(
            DunewyrmTuning.POISON_PARTICLE, pos, yaw, 0f, 0f,
            DunewyrmTuning.POISON_PARTICLE_SCALE, 2.2f, commandBuffer);
    }

    @Nonnull
    private static Vector3d mouthPoint(@Nonnull final DunewyrmComponent worm, final double ahead) {
        final float yaw = worm.getYaw();
        return new Vector3d(
            worm.getHeadPosition().x + forwardX(yaw) * (ahead + worm.getCobraLean()),
            worm.getHeadPosition().y + 0.5 + worm.getCobraRise() * 0.85,
            worm.getHeadPosition().z + forwardZ(yaw) * (ahead + worm.getCobraLean()));
    }

    private void tickTunnel(@Nonnull final DunewyrmComponent worm,
                            @Nonnull final Store<EntityStore> store,
                            @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                            @Nonnull final TitanVariantAsset variant,
                            final float dt) {

        worm.setTunnelDepth(approach(worm.getTunnelDepth(), DunewyrmTuning.TUNNEL_DEPTH, 4f * dt));
        worm.setCobraRise(approach(worm.getCobraRise(), 0f, 8f * dt));
        worm.setCobraLean(approach(worm.getCobraLean(), 0f, 8f * dt));

        final float speed = variant.getMoveSpeed() * DunewyrmTuning.TUNNEL_SPEED_MULT;
        float yaw = worm.getYaw();
        final Ref<EntityStore> target = worm.getTarget();
        if (target != null && target.isValid()) {
            final var t = store.getComponent(target, TransformComponent.getComponentType());
            if (t != null) {
                yaw = turnToward(yaw, yawToward(worm.getHeadPosition(), t.getPosition()),
                    turnStep(variant, dt) * 0.55f);
            }
        }
        final float avoided = blendSelfAvoidance(worm, yaw);
        yaw = turnToward(yaw, avoided, turnStep(variant, dt));
        worm.setYaw(yaw);
        worm.getHeadPosition().add(forwardX(yaw) * speed * dt, 0, forwardZ(yaw) * speed * dt);

        worm.setDigParticleTimer(worm.getDigParticleTimer() - dt);
        if (worm.getDigParticleTimer() <= 0f) {
            worm.setDigParticleTimer(0.18f);
            // Keep the burst near the buried head, not floating at full tunnel-depth surface.
            final Vector3d digAt = new Vector3d(
                worm.getHeadPosition().x,
                worm.getHeadPosition().y + DunewyrmTuning.DIG_PARTICLE_HEIGHT,
                worm.getHeadPosition().z);
            ParticleUtil.spawnParticleEffect(
                DunewyrmTuning.DIG_PARTICLE, digAt, worm.getYaw(), 0f, 0f,
                DunewyrmTuning.DIG_PARTICLE_SCALE, 0.9f, commandBuffer);
            // Widen the disturbed area with a couple of side bursts.
            final double sideX = -forwardZ(worm.getYaw());
            final double sideZ = forwardX(worm.getYaw());
            scratch.set(digAt.x + sideX * 2.2, digAt.y, digAt.z + sideZ * 2.2);
            ParticleUtil.spawnParticleEffect(
                DunewyrmTuning.DIG_PARTICLE, scratch, worm.getYaw(), 0f, 0f,
                DunewyrmTuning.DIG_PARTICLE_SCALE * 0.75f, 0.7f, commandBuffer);
            scratch.set(digAt.x - sideX * 2.2, digAt.y, digAt.z - sideZ * 2.2);
            ParticleUtil.spawnParticleEffect(
                DunewyrmTuning.DIG_PARTICLE, scratch, worm.getYaw(), 0f, 0f,
                DunewyrmTuning.DIG_PARTICLE_SCALE * 0.75f, 0.7f, commandBuffer);
            TitanSound.play(commandBuffer, DunewyrmTuning.DIG_SOUND, digAt);
        }

        final float spawnEvery = DunewyrmTuning.TUNNEL_DURATION / Math.max(1, DunewyrmTuning.SCORPIONS_PER_TUNNEL);
        if (worm.getScorpionBudget() > 0f && worm.getStateTimer() >=
            (DunewyrmTuning.SCORPIONS_PER_TUNNEL - worm.getScorpionBudget() + 1) * spawnEvery) {
            worm.setScorpionBudget(worm.getScorpionBudget() - 1f);
            final Vector3d spawnPos = new Vector3d(worm.getHeadPosition().x,
                worm.getHeadPosition().y + worm.getTunnelDepth() + 0.5,
                worm.getHeadPosition().z);
            final var world = store.getExternalData().getWorld();
            final float spawnYaw = worm.getYaw();
            world.execute(() -> NPCPlugin.get().spawnNPC(
                store, "Scorpion", null, spawnPos, new Rotation3f(0, spawnYaw, 0)));
        }

        if (worm.getStateTimer() >= DunewyrmTuning.TUNNEL_DURATION) {
            worm.setState(DunewyrmState.SLITHER);
            worm.setTunnelDepth(0f);
            worm.addOrbitAngle((float) (Math.PI * 0.35));
        }
    }

    private void maybeStartTunnel(@Nonnull final DunewyrmComponent worm,
                                  @Nonnull final CommandBuffer<EntityStore> commandBuffer) {
        if (worm.getState() == DunewyrmState.TUNNEL || worm.getState() == DunewyrmState.DYING) return;
        if (worm.getFleeTimer() > 0f) return;
        final DunewyrmEncounter encounter = DunewyrmEncounter.getOrCreate(worm.getEncounterId());
        final float frac = encounter.remainingFraction();
        boolean start = false;
        if (!encounter.isTunnelHighUsed() && frac <= DunewyrmTuning.TUNNEL_THRESHOLD_HIGH
            && frac > DunewyrmTuning.TUNNEL_THRESHOLD_LOW) {
            encounter.setTunnelHighUsed(true);
            start = true;
        } else if (!encounter.isTunnelLowUsed() && frac <= DunewyrmTuning.TUNNEL_THRESHOLD_LOW) {
            encounter.setTunnelLowUsed(true);
            start = true;
        }
        if (!start) return;
        worm.setState(DunewyrmState.TUNNEL);
        worm.setScorpionBudget(DunewyrmTuning.SCORPIONS_PER_TUNNEL);
        worm.setDigParticleTimer(0f);
        TitanSound.play(commandBuffer, DunewyrmTuning.DIG_SOUND, worm.getHeadPosition());
    }

    private void tickTongue(@Nonnull final DunewyrmComponent worm,
                            final float dt,
                            @Nonnull final CommandBuffer<EntityStore> commandBuffer) {
        if (worm.getTongueActive() > 0f) {
            worm.setTongueActive(worm.getTongueActive() - dt);
            return;
        }
        worm.setTongueTimer(worm.getTongueTimer() - dt);
        if (worm.getTongueTimer() > 0f) return;
        worm.setTongueActive(DunewyrmTuning.TONGUE_DURATION);
        worm.setTongueTimer(DunewyrmTuning.TONGUE_INTERVAL_MIN
            + ThreadLocalRandom.current().nextFloat()
            * (DunewyrmTuning.TONGUE_INTERVAL_MAX - DunewyrmTuning.TONGUE_INTERVAL_MIN));
        TitanSound.play(commandBuffer, DunewyrmTuning.TONGUE_SOUND, worm.getHeadPosition());
    }

    private void tickPoisonCloud(@Nonnull final DunewyrmComponent worm,
                                 @Nonnull final Store<EntityStore> store,
                                 @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                 final float dt) {
        if (worm.getPoisonCloud() == null) return;
        worm.setPoisonTimer(worm.getPoisonTimer() - dt);
        if (worm.getPoisonTimer() <= 0f) {
            worm.setPoisonCloud(null);
            return;
        }

        // Sparse ground wisps across the linger radius — readable hazard, not a fog wall.
        if (ThreadLocalRandom.current().nextFloat() < dt * 2.5f) {
            final double ang = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            final double rad = ThreadLocalRandom.current().nextDouble() * DunewyrmTuning.POISON_RADIUS;
            scratch.set(
                worm.getPoisonCloud().x + Math.cos(ang) * rad,
                worm.getPoisonCloud().y,
                worm.getPoisonCloud().z + Math.sin(ang) * rad);
            spawnPoisonParticle(commandBuffer, scratch, (float) ang);
        }

        final EntityEffect poison = EntityEffect.getAssetMap().getAsset(DunewyrmTuning.POISON_EFFECT);
        if (poison == null) return;

        for (final Ref<EntityStore> candidate : TargetUtil.getAllEntitiesInCylinder(
            worm.getPoisonCloud(), DunewyrmTuning.POISON_RADIUS, 4.0, store)) {
            if (store.getComponent(candidate, Player.getComponentType()) == null) continue;
            final var effects = commandBuffer.getComponent(candidate, EffectControllerComponent.getComponentType());
            if (effects != null) {
                effects.addEffect(candidate, poison, commandBuffer);
            }
        }
    }

    private float blendSelfAvoidance(@Nonnull final DunewyrmComponent worm, final float desiredYaw) {
        avoid.set(0, 0, 0);
        int bodySeen = 0;
        final Vector3d head = worm.getHeadPosition();
        final double radius = DunewyrmTuning.SELF_AVOID_RADIUS;
        final double radiusSq = radius * radius;

        for (final DunewyrmSegment segment : worm.getSegments()) {
            if (segment.getRole() != DunewyrmSegmentRole.BODY) continue;
            bodySeen++;
            if (bodySeen <= 2) continue;

            final double dx = head.x - segment.getPosition().x;
            final double dz = head.z - segment.getPosition().z;
            final double distSq = dx * dx + dz * dz;
            if (distSq >= radiusSq || distSq < 1e-4) continue;

            final double dist = Math.sqrt(distSq);
            final double weight = (radius - dist) / radius;
            avoid.x += (dx / dist) * weight;
            avoid.z += (dz / dist) * weight;
        }

        if (avoid.lengthSquared() < 1e-6) return desiredYaw;

        final float avoidYaw = (float) Math.atan2(-avoid.x, -avoid.z);
        final float blend = Math.min(1f, (float) Math.sqrt(avoid.lengthSquared()));
        // Soft nudge only — strong blends here were causing whip turns.
        return lerpAngle(desiredYaw, avoidYaw, blend * 0.28f);
    }

    private static float turnStep(@Nonnull final TitanVariantAsset variant, final float dt) {
        return Math.min(variant.getTurnSpeed() * dt, DunewyrmTuning.MAX_TURN_RATE * dt);
    }

    private void advanceHead(@Nonnull final DunewyrmComponent worm,
                             @Nonnull final Store<EntityStore> store,
                             final double forwardX, final double forwardZ,
                             final double sideX, final double sideZ,
                             final float speed, final float sine, final float dt) {
        final Vector3d head = worm.getHeadPosition();
        final double ox = head.x;
        final double oy = head.y;
        final double oz = head.z;
        final double nx = ox + (forwardX * speed + sideX * sine * 0.15) * dt;
        final double nz = oz + (forwardZ * speed + sideZ * sine * 0.15) * dt;

        int bodySeen = 0;
        final double minSq = (DunewyrmTuning.SELF_AVOID_RADIUS * 0.55)
            * (DunewyrmTuning.SELF_AVOID_RADIUS * 0.55);
        boolean blockedByBody = false;
        for (final DunewyrmSegment segment : worm.getSegments()) {
            if (segment.getRole() != DunewyrmSegmentRole.BODY) continue;
            bodySeen++;
            if (bodySeen <= 2) continue;
            final double dx = nx - segment.getPosition().x;
            final double dz = nz - segment.getPosition().z;
            if (dx * dx + dz * dz < minSq) {
                blockedByBody = true;
                break;
            }
        }

        if (blockedByBody) {
            tryPlaceHead(worm, store, ox + sideX * speed * dt, oy, oz + sideZ * speed * dt, true);
            return;
        }
        if (!tryPlaceHead(worm, store, nx, oy, nz, true)) {
            // Tall step / pillar — don't climb it; keep footing.
            head.set(ox, oy, oz);
        }
    }

    /**
     * Places the head on walkable ground. Returns false when {@code blockTallClimbs} is set and the
     * surface under the new column is more than {@link DunewyrmTuning#MAX_CLIMB} above the current height.
     */
    private boolean tryPlaceHead(@Nonnull final DunewyrmComponent worm,
                                 @Nonnull final Store<EntityStore> store,
                                 final double x,
                                 final double y,
                                 final double z,
                                 final boolean blockTallClimbs) {
        final ChunkStore chunks = store.getExternalData().getWorld().getChunkStore();
        final double ground = GroundSampler.sample(chunks, x, y, z, 5, 10);
        if (!GroundSampler.isValid(ground)) {
            worm.getHeadPosition().set(x, y, z);
            return true;
        }
        if (blockTallClimbs && ground > y + DunewyrmTuning.MAX_CLIMB) {
            return false;
        }
        final double newY = Math.max(y - DunewyrmTuning.MAX_DROP, Math.min(y + DunewyrmTuning.MAX_CLIMB, ground));
        worm.getHeadPosition().set(x, newY, z);
        return true;
    }

    static double forwardX(final float yaw) {
        return -Math.sin(yaw);
    }

    static double forwardZ(final float yaw) {
        return -Math.cos(yaw);
    }

    private static double horizontalDistance(@Nonnull final Vector3d a, @Nonnull final Vector3d b) {
        final double dx = a.x - b.x;
        final double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static float turnToward(final float current, final float desired, final float maxStep) {
        float delta = wrapAngle(desired - current);
        if (Math.abs(delta) <= maxStep) return desired;
        return current + Math.copySign(maxStep, delta);
    }

    private static float lerpAngle(final float from, final float to, final float t) {
        return from + wrapAngle(to - from) * Math.max(0f, Math.min(1f, t));
    }

    private static float wrapAngle(float delta) {
        while (delta > Math.PI) delta -= (float) (Math.PI * 2);
        while (delta < -Math.PI) delta += (float) (Math.PI * 2);
        return delta;
    }

    private static float yawToward(@Nonnull final Vector3d from, @Nonnull final Vector3d to) {
        return (float) Math.atan2(-(to.x - from.x), -(to.z - from.z));
    }

    private static float approach(final float current, final float target, final float maxDelta) {
        if (current < target) return Math.min(target, current + maxDelta);
        return Math.max(target, current - maxDelta);
    }
}
