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
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
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
        worm.tickObstacleTurnCooldown(dt);
        worm.addStateTimer(dt);
        worm.addSinePhase(dt);
        tickTongue(worm, dt, commandBuffer);
        tickPoisonCloud(worm, store, commandBuffer, dt);

        acquireTarget(worm, store, variant);
        if (worm.getTarget() != null)
            com.hexvane.titan.compat.LevelingCompatibility.engageWorm(store, worm);
        maybeStartTunnel(worm, store, commandBuffer);
        maybeUnstickFromCave(worm, store);

        if (worm.getState() == DunewyrmState.FLAIL) {
            tickFlail(worm, store, variant, dt);
        } else if (worm.getFleeTimer() > 0f) {
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
        DunewyrmSpawner.layoutAlongPath(worm, store.getExternalData().getWorld().getChunkStore(), dt);
        refreshViewerDistances(worm, store);

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

        worm.setTarget(DunewyrmPlayers.nearest(store, worm.getHeadPosition(), variant.getWakeRadius()));
    }

    /**
     * Records how far each segment is from the nearest player so the part sync can slow down for the parts
     * nobody is close to. Players × segments is a handful of distance checks.
     */
    private static void refreshViewerDistances(@Nonnull final DunewyrmComponent worm,
                                               @Nonnull final Store<EntityStore> store) {
        for (final DunewyrmSegment segment : worm.getSegments()) {
            segment.setViewerDistance(Double.MAX_VALUE);
        }
        DunewyrmPlayers.forEach(store, (player, position) -> {
            for (final DunewyrmSegment segment : worm.getSegments()) {
                final double d = segment.getPosition().distanceSquared(position);
                if (d < segment.getViewerDistance()) segment.setViewerDistance(d);
            }
        });
        final long tick = store.getExternalData().getWorld().getTick();
        final List<DunewyrmSegment> segments = worm.getSegments();
        for (int i = 0; i < segments.size(); i++) {
            final DunewyrmSegment segment = segments.get(i);
            final double d = segment.getViewerDistance();
            final double distance = d == Double.MAX_VALUE ? d : Math.sqrt(d);
            segment.setViewerDistance(distance);
            final int stride = distance > DunewyrmTuning.PART_SYNC_FAR_DISTANCE
                ? DunewyrmTuning.PART_SYNC_FAR_STRIDE
                : distance > DunewyrmTuning.PART_SYNC_MID_DISTANCE
                ? DunewyrmTuning.PART_SYNC_MID_STRIDE
                : 1;
            segment.setSyncThisTick(stride <= 1 || (tick + i) % stride == 0);
        }
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
        final float speed = moveSpeed(worm, variant) * 1.35f;
        final Vector3d head = worm.getHeadPosition();
        final double ox = head.x;
        final double oy = head.y;
        final double oz = head.z;
        if (!tryPlaceHead(worm, store,
            ox + forwardX(yaw) * speed * dt,
            oy,
            oz + forwardZ(yaw) * speed * dt,
            true)) {
            turnFromObstacle(worm, store, yaw);
            head.set(ox, oy, oz);
        }
        worm.setCobraRise(approach(worm.getCobraRise(), 0f, 8f * dt));
        worm.setCobraLean(approach(worm.getCobraLean(), 0f, 8f * dt));
        worm.setTunnelDepth(approach(worm.getTunnelDepth(), 0f, 5f * dt));
    }

    private void tickFlail(@Nonnull final DunewyrmComponent worm,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final TitanVariantAsset variant,
                           final float dt) {
        // Slow forward crawl while the body thrashes in layoutAlongPath.
        final float yaw = worm.getYaw();
        final float speed = moveSpeed(worm, variant) * 0.35f;
        final Vector3d head = worm.getHeadPosition();
        final double ox = head.x;
        final double oy = head.y;
        final double oz = head.z;
        if (!tryPlaceHead(worm, store,
            ox + forwardX(yaw) * speed * dt,
            oy,
            oz + forwardZ(yaw) * speed * dt,
            true)) {
            turnFromObstacle(worm, store, yaw);
            head.set(ox, oy, oz);
        }
        worm.setCobraRise(0f);
        worm.setCobraLean(0f);
        worm.setTunnelDepth(0f);
        if (worm.getStateTimer() >= DunewyrmTuning.FLAIL_DURATION) {
            worm.setState(DunewyrmState.SLITHER);
        }
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
        final boolean riding = isPlayerOnSnake(worm, targetPos);

        worm.addOrbitAngle(DunewyrmTuning.ORBIT_SPEED * dt);

        final boolean flanking = isPlayerBesideBody(worm, targetPos);
        float radius = worm.getOrbitRadius();
        if (flanking) {
            radius *= DunewyrmTuning.ORBIT_FLANK_MULT;
        }
        // Cooldown over: stop circling and bear straight down on the player. Orbiting only ever pointed the
        // head tangentially, so the "must be facing the player" gates below almost never opened.
        final boolean hunting = !riding && worm.getAttackCooldown() <= 0f;
        float speed = moveSpeed(worm, variant);
        if (hunting) {
            orbitGoal.set(targetPos);
            speed *= DunewyrmTuning.HUNT_SPEED_MULT;
        } else if (riding) {
            // Carrying someone: ease off so the platform (and its turn rate, which follows speed) stays
            // gentle enough to stand on.
            speed = Math.min(speed, DunewyrmTuning.RIDER_SPEED_CAP);
            // Don't chase a rider — hold distance around home / last orbit ring.
            orbitGoal.set(
                worm.getHome().x + Math.cos(worm.getOrbitAngle()) * radius,
                worm.getHeadPosition().y,
                worm.getHome().z + Math.sin(worm.getOrbitAngle()) * radius);
        } else {
            orbitGoal.set(
                targetPos.x + Math.cos(worm.getOrbitAngle()) * radius,
                worm.getHeadPosition().y,
                targetPos.z + Math.sin(worm.getOrbitAngle()) * radius);

            // Softly keep a standoff: if too close, push the goal further out on the same radial.
            final double distToPlayer = horizontalDistance(worm.getHeadPosition(), targetPos);
            final float standoff = flanking ? radius : radius * 0.55f;
            if (distToPlayer < standoff) {
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
        }

        // Soft leash toward spawn home.
        final double homeDist = horizontalDistance(worm.getHeadPosition(), worm.getHome());
        if (homeDist > DunewyrmTuning.HOME_LEASH) {
            orbitGoal.set(worm.getHome().x, worm.getHeadPosition().y, worm.getHome().z);
        }

        float desired = yawToward(worm.getHeadPosition(), orbitGoal);
        desired = steerClear(worm, desired);
        if (worm.getObstacleTurnCooldown()<=0f)
            worm.setYaw(turnToward(worm.getYaw(), desired, turnStep(variant, speed, dt)));

        final float sine = (float) Math.sin(worm.getSinePhase() * DunewyrmTuning.SLITHER_SINE_FREQ)
            * DunewyrmTuning.SLITHER_SINE_AMP;
        final float yaw = worm.getYaw();
        final double forwardX = forwardX(yaw);
        final double forwardZ = forwardZ(yaw);
        final double sideX = -forwardZ;
        final double sideZ = forwardX;
        advanceHead(worm, store, forwardX, forwardZ, sideX, sideZ, speed, sine, dt);
        spawnSlitherDust(worm, commandBuffer, dt);

        worm.setCobraRise(approach(worm.getCobraRise(), 0f, 6f * dt));
        worm.setCobraLean(approach(worm.getCobraLean(), 0f, 6f * dt));
        worm.setJawOpen(approach(worm.getJawOpen(), 0f, 4f * dt));
        worm.setTunnelDepth(approach(worm.getTunnelDepth(), 0f, 5f * dt));

        if (!hunting) return;

        final double dist = horizontalDistance(worm.getHeadPosition(), targetPos);
        final float chargeRange = variant.getAttackRange();
        final float facingPlayer = yawToward(worm.getHeadPosition(), targetPos);
        final float facingDelta = Math.abs(wrapAngle(facingPlayer - yaw));
        final boolean canPoison = worm.bodyCount() >= DunewyrmTuning.MIN_BODIES_FOR_POISON
            && dist <= DunewyrmTuning.POISON_ATTACK_RANGE;

        // Separate rolls so charge chance cannot permanently starve poison. Cobra swings onto the player
        // during its own windup, so its facing gate is loose; charge aims itself during windup too.
        if (canPoison && facingDelta < DunewyrmTuning.COBRA_FACING
            && ThreadLocalRandom.current().nextFloat() < DunewyrmTuning.COBRA_CHANCE) {
            worm.setState(DunewyrmState.COBRA);
            worm.setAttackCooldown(DunewyrmTuning.ATTACK_COOLDOWN + DunewyrmTuning.COBRA_COOLDOWN_EXTRA);
            TitanSound.play(commandBuffer, variant.getTelegraphSound(), worm.getHeadPosition());
        } else if (facingDelta < DunewyrmTuning.CHARGE_FACING && dist <= chargeRange
            && ThreadLocalRandom.current().nextFloat() < DunewyrmTuning.CHARGE_CHANCE) {
            // Aim at the player, not down the current heading.
            worm.setChargeYaw(facingPlayer);
            worm.setState(DunewyrmState.CHARGE);
            worm.setAttackCooldown(DunewyrmTuning.ATTACK_COOLDOWN);
            TitanSound.play(commandBuffer, variant.getTelegraphSound(), worm.getHeadPosition());
        }
    }

    private void tickCharge(@Nonnull final DunewyrmComponent worm,
                            @Nonnull final Store<EntityStore> store,
                            @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                            @Nonnull final TitanVariantAsset variant,
                            final float dt) {

        final float t = worm.getStateTimer();
        final Vector3d head = worm.getHeadPosition();
        final Ref<EntityStore> target = worm.getTarget();
        final var targetTransform = target != null && target.isValid()
            ? store.getComponent(target, TransformComponent.getComponentType())
            : null;

        // chargeYaw was fixed on the player's position when the charge was rolled. The windup swings the
        // head onto that line and the run holds it: the telegraph is a promise, and a player who reads it
        // and sidesteps is meant to be rewarded, not chased.
        if (t < DunewyrmTuning.CHARGE_WINDUP) {
            worm.setYaw(turnToward(worm.getYaw(), worm.getChargeYaw(), DunewyrmTuning.CHARGE_AIM_RATE * dt));
            telegraphCharge(worm, store, commandBuffer, variant, DunewyrmTuning.CHARGE_WINDUP - t, dt);
            worm.setCobraRise(approach(worm.getCobraRise(), 0f, 8f * dt));
            worm.setCobraLean(approach(worm.getCobraLean(), 0f, 8f * dt));
            worm.setTunnelDepth(approach(worm.getTunnelDepth(), 0f, 5f * dt));
            return;
        }

        final float speed = moveSpeed(worm, variant) * DunewyrmTuning.CHARGE_SPEED_MULT;
        float yaw = turnToward(worm.getYaw(), worm.getChargeYaw(), DunewyrmTuning.CHARGE_AIM_RATE * dt);
        // Only bend around the body if it is actually in the way.
        final float avoided = steerClear(worm, yaw);
        yaw = turnToward(yaw, avoided, turnStep(variant, speed, dt));
        worm.setYaw(yaw);
        final double ox = head.x;
        final double oy = head.y;
        final double oz = head.z;
        if (!tryPlaceHead(worm, store,
            ox + forwardX(yaw) * speed * dt,
            oy,
            oz + forwardZ(yaw) * speed * dt,
            true)) {
            turnFromObstacle(worm, store, yaw);
            head.set(ox, oy, oz);
        }
        worm.setCobraRise(approach(worm.getCobraRise(), 0f, 8f * dt));
        worm.setCobraLean(approach(worm.getCobraLean(), 0f, 8f * dt));
        worm.setTunnelDepth(approach(worm.getTunnelDepth(), 0f, 5f * dt));

        if (t >= DunewyrmTuning.CHARGE_WINDUP + DunewyrmTuning.CHARGE_DURATION) {
            worm.setState(DunewyrmState.SLITHER);
            // Re-seat the orbit ring ahead of the head so the next goal is a wide arc onward, not a U-turn
            // back through the body it just dragged past the player.
            if (targetTransform != null) {
                final Vector3d tp = targetTransform.getPosition();
                final float radial = (float) Math.atan2(head.z - tp.z, head.x - tp.x);
                // Continue around in whichever direction keeps turning away from the tail.
                final float side = turnAwayFromBodySign(worm);
                worm.setOrbitAngle(radial + side * 0.9f);
            } else {
                worm.addOrbitAngle((float) (Math.PI * 0.25));
            }
        }
    }

    /** +1 / -1: which way (in orbit angle) turning keeps the head furthest from its own body. */
    private float turnAwayFromBodySign(@Nonnull final DunewyrmComponent worm) {
        final float yaw = worm.getYaw();
        final float left = probeClearance(worm, yaw + 1.2f);
        final float right = probeClearance(worm, yaw - 1.2f);
        // Orbit angle increases counter-clockwise in XZ; yaw+ is a left turn in this basis.
        return left >= right ? 1f : -1f;
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
                    // Same ground centre the telegraph ring was drawn on, so the hazard lands where it was
                    // promised — and every breath particle below is placed relative to this same point.
                    final Vector3d cloud = poisonCentre(worm);
                    worm.setPoisonCloud(cloud);
                    worm.setPoisonTimer(DunewyrmTuning.POISON_LINGER);
                    ParticleUtil.spawnParticleEffect(
                        DunewyrmTuning.POISON_CLOUD_PARTICLE, cloud, worm.getYaw(), 0f, 0f,
                        2.2f, 2.5f, commandBuffer);
                }
                sprayPoisonBreath(worm, commandBuffer, dt);
                poisonAlongJet(worm, store, commandBuffer);
            }
        } else {
            worm.setCobraLean(approach(worm.getCobraLean(), 0f, 5f * dt));
            worm.setJawOpen(approach(worm.getJawOpen(), 0f, 3f * dt));
            worm.setCobraRise(approach(worm.getCobraRise(), DunewyrmTuning.COBRA_RISE * 0.35f, 4f * dt));
        }

        // Face the target while winding up; once the breath is out the head commits to where the cloud
        // was dropped so the jet, the head and the hitbox all agree.
        final Ref<EntityStore> target = worm.getTarget();
        if (t < DunewyrmTuning.COBRA_SPRAY_START && target != null && target.isValid()) {
            final var tt = store.getComponent(target, TransformComponent.getComponentType());
            if (tt != null) {
                worm.setYaw(turnToward(worm.getYaw(),
                    yawToward(worm.getHeadPosition(), tt.getPosition()),
                    DunewyrmTuning.COBRA_TRACK_RATE * dt));
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

        final Vector3d centre = poisonCentre(worm);
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
        final Vector3d cloud = worm.getPoisonCloud();
        if (cloud == null) return;
        final Vector3d mouth = mouthPoint(worm, DunewyrmTuning.TONGUE_MOUTH + 0.8);

        // Jets aim from the mouth at the cloud centre (not straight ahead), pitched down to reach it.
        final float yaw = yawToward(mouth, cloud);
        final double jx = cloud.x - mouth.x;
        final double jz = cloud.z - mouth.z;
        final float pitch = (float) Math.atan2(cloud.y + 0.5 - mouth.y, Math.sqrt(jx * jx + jz * jz));

        // Vanilla Impact_Poison is known-visible; custom cone/mist as backup colour wash.
        ParticleUtil.spawnParticleEffect(
            DunewyrmTuning.POISON_CONE_PARTICLE, mouth, yaw, pitch, 0f,
            DunewyrmTuning.POISON_CONE_SCALE, 1.6f, commandBuffer);
        ParticleUtil.spawnParticleEffect(
            DunewyrmTuning.POISON_MIST_PARTICLE, mouth, yaw, pitch, 0f,
            DunewyrmTuning.POISON_PARTICLE_SCALE, 1.8f, commandBuffer);
        ParticleUtil.spawnParticleEffect(
            DunewyrmTuning.POISON_CONE_PARTICLE, mouth, yaw + 0.3f, pitch, 0f,
            DunewyrmTuning.POISON_CONE_SCALE * 0.85f, 1.2f, commandBuffer);
        ParticleUtil.spawnParticleEffect(
            DunewyrmTuning.POISON_CONE_PARTICLE, mouth, yaw - 0.3f, pitch, 0f,
            DunewyrmTuning.POISON_CONE_SCALE * 0.85f, 1.2f, commandBuffer);

        // Puffs land inside the hazard circle itself, so what you see billowing is exactly what poisons.
        final int puffs = 3 + (ThreadLocalRandom.current().nextFloat() < 0.5f ? 1 : 0);
        for (int i = 0; i < puffs; i++) {
            final double ang = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            final double rad = Math.sqrt(ThreadLocalRandom.current().nextDouble())
                * DunewyrmTuning.POISON_RADIUS * DunewyrmTuning.POISON_PUFF_SPREAD;
            scratch.set(
                cloud.x + Math.cos(ang) * rad,
                cloud.y + 0.4 + ThreadLocalRandom.current().nextDouble() * 1.6,
                cloud.z + Math.sin(ang) * rad);
            spawnPoisonParticle(commandBuffer, scratch, (float) ang);
        }
        if (ThreadLocalRandom.current().nextFloat() < dt * 2f) {
            TitanSound.play(commandBuffer, DunewyrmTuning.COBRA_SOUND, worm.getHeadPosition());
        }
    }

    /**
     * While the breath is out, anyone inside the jet between the mouth and the cloud is poisoned too —
     * standing on the neck or jumping through the stream is not a safe spot just because it is above the
     * ground circle.
     */
    private void poisonAlongJet(@Nonnull final DunewyrmComponent worm,
                                @Nonnull final Store<EntityStore> store,
                                @Nonnull final CommandBuffer<EntityStore> commandBuffer) {
        final Vector3d cloud = worm.getPoisonCloud();
        if (cloud == null) return;
        final EntityEffect poison = EntityEffect.getAssetMap().getAsset(DunewyrmTuning.POISON_EFFECT);
        if (poison == null) return;

        final Vector3d mouth = mouthPoint(worm, DunewyrmTuning.TONGUE_MOUTH + 0.8);
        final double ax = cloud.x - mouth.x;
        final double ay = cloud.y + 1.0 - mouth.y;
        final double az = cloud.z - mouth.z;
        final double len2 = ax * ax + ay * ay + az * az;
        final double r2 = DunewyrmTuning.POISON_JET_RADIUS * DunewyrmTuning.POISON_JET_RADIUS;

        DunewyrmPlayers.forEach(store, (candidate, feet) -> {
            // Closest point on the mouth→cloud segment to the player's chest.
            final double px = feet.x - mouth.x;
            final double py = feet.y + 0.9 - mouth.y;
            final double pz = feet.z - mouth.z;
            final double u = len2 < 1e-6 ? 0.0 : Math.max(0.0, Math.min(1.0, (px * ax + py * ay + pz * az) / len2));
            final double dx = px - ax * u;
            final double dy = py - ay * u;
            final double dz = pz - az * u;
            if (dx * dx + dy * dy + dz * dz > r2) return;
            final var effects = commandBuffer.getComponent(candidate, EffectControllerComponent.getComponentType());
            if (effects != null) {
                effects.addEffect(candidate, poison, commandBuffer);
            }
        });
    }

    /** Ground point the poison attack is aimed at: a fixed distance ahead of the head, independent of lean. */
    @Nonnull
    private static Vector3d poisonCentre(@Nonnull final DunewyrmComponent worm) {
        final float yaw = worm.getYaw();
        return new Vector3d(
            worm.getHeadPosition().x + forwardX(yaw) * DunewyrmTuning.POISON_CENTRE_AHEAD,
            worm.getHeadPosition().y + 0.05,
            worm.getHeadPosition().z + forwardZ(yaw) * DunewyrmTuning.POISON_CENTRE_AHEAD);
    }

    private static void spawnPoisonParticle(@Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                            @Nonnull final Vector3d pos,
                                            final float yaw) {
        ParticleUtil.spawnParticleEffect(
            DunewyrmTuning.POISON_PARTICLE, pos, yaw, 0f, 0f,
            DunewyrmTuning.POISON_PARTICLE_SCALE, 2.0f, commandBuffer);
        ParticleUtil.spawnParticleEffect(
            DunewyrmTuning.POISON_MIST_PARTICLE, pos, yaw, 0f, 0f,
            DunewyrmTuning.POISON_PARTICLE_SCALE * 0.9f, 1.5f, commandBuffer);
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

        // Dig down until the dwell is over; after that the emerge branch below lifts it back out.
        final boolean digging = worm.getStateTimer() < DunewyrmTuning.TUNNEL_DURATION;
        if (digging) {
            worm.setTunnelDepth(approach(worm.getTunnelDepth(), DunewyrmTuning.TUNNEL_DEPTH, 4f * dt));
        }
        worm.setCobraRise(approach(worm.getCobraRise(), 0f, 8f * dt));
        worm.setCobraLean(approach(worm.getCobraLean(), 0f, 8f * dt));

        final float speed = moveSpeed(worm, variant) * DunewyrmTuning.TUNNEL_SPEED_MULT;
        float yaw = worm.getYaw();
        final Ref<EntityStore> target = worm.getTarget();
        if (target != null && target.isValid()) {
            final var t = store.getComponent(target, TransformComponent.getComponentType());
            if (t != null) {
                yaw = turnToward(yaw, yawToward(worm.getHeadPosition(), t.getPosition()),
                    turnStep(variant, speed, dt) * 0.55f);
            }
        }
        final float avoided = steerClear(worm, yaw);
        yaw = turnToward(yaw, avoided, turnStep(variant, speed, dt));
        worm.setYaw(yaw);
        worm.getHeadPosition().add(forwardX(yaw) * speed * dt, 0, forwardZ(yaw) * speed * dt);

        if (digging) {
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
        }

        final float spawnEvery = DunewyrmTuning.TUNNEL_DURATION / Math.max(1, worm.getScorpionsThisTunnel());
        if (worm.getScorpionBudget() > 0f && worm.getStateTimer() >=
            (worm.getScorpionsThisTunnel() - worm.getScorpionBudget() + 1) * spawnEvery) {
            worm.setScorpionBudget(worm.getScorpionBudget() - 1f);
            final Vector3d spawnPos = new Vector3d(worm.getHeadPosition().x,
                worm.getHeadPosition().y + worm.getTunnelDepth() + 0.5,
                worm.getHeadPosition().z);
            final var world = store.getExternalData().getWorld();
            final float spawnYaw = worm.getYaw();
            world.execute(() -> {
                final var leveling = com.hexvane.titan.compat.LevelingCompatibility.near(store, spawnPos, 100);
                var npc = NPCPlugin.get().spawnNPC(store, "Titan_Dunewyrm_Minion", null, spawnPos, new Rotation3f(0, spawnYaw, 0));
                if (npc != null) com.hexvane.titan.compat.TitanMinionScaling.apply(store, npc.first(), 124, leveling);
            });
        }

        if (worm.getStateTimer() >= DunewyrmTuning.TUNNEL_DURATION) {
            // Climb back out: tunnelDepth drives visual Y, so lerping it to 0 is the emerge animation.
            worm.setTunnelDepth(approach(worm.getTunnelDepth(), 0f, DunewyrmTuning.TUNNEL_EMERGE_RATE * dt));
            worm.setDigParticleTimer(worm.getDigParticleTimer() - dt);
            if (worm.getDigParticleTimer() <= 0f) {
                worm.setDigParticleTimer(0.16f);
                final Vector3d digAt = new Vector3d(
                    worm.getHeadPosition().x,
                    worm.getHeadPosition().y + DunewyrmTuning.DIG_PARTICLE_HEIGHT,
                    worm.getHeadPosition().z);
                ParticleUtil.spawnParticleEffect(
                    DunewyrmTuning.DIG_PARTICLE, digAt, worm.getYaw(), 0f, 0f,
                    DunewyrmTuning.DIG_PARTICLE_SCALE, 0.9f, commandBuffer);
                ParticleUtil.spawnParticleEffect(
                    DunewyrmTuning.SLITHER_PARTICLE, digAt, worm.getYaw(), 0f, 0f,
                    DunewyrmTuning.SLITHER_DUST_SCALE, 0.7f, commandBuffer);
            }
            if (worm.getTunnelDepth() <= 0.08f) {
                finishEmerge(worm, store);
            }
        }
    }

    /** Leaves the dig on the real surface without punching a shaft through the world. */
    private void finishEmerge(@Nonnull final DunewyrmComponent worm,
                              @Nonnull final Store<EntityStore> store) {
        final ChunkStore chunks = store.getExternalData().getWorld().getChunkStore();
        final Vector3d head = worm.getHeadPosition();
        final double ground = DunewyrmTerrain.surface(chunks,head.x,head.z);
        if (GroundSampler.isValid(ground)) {
            head.y = ground;
        }
        worm.setTunnelDepth(0f);
        worm.setState(DunewyrmState.SLITHER);
        worm.addOrbitAngle((float) (Math.PI * 0.35));
        // Brief steering grace after emerging.
        worm.setObstacleTurnCooldown(1.25f);
    }

    private void maybeStartTunnel(@Nonnull final DunewyrmComponent worm,
                                  @Nonnull final Store<EntityStore> store,
                                  @Nonnull final CommandBuffer<EntityStore> commandBuffer) {
        if (worm.getState() == DunewyrmState.TUNNEL
            || worm.getState() == DunewyrmState.DYING
            || worm.getState() == DunewyrmState.FLAIL) return;
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
        dropRidersBeforeDig(worm, store, commandBuffer);
        worm.setState(DunewyrmState.TUNNEL);
        worm.startScorpionWave(com.hexvane.titan.combat.TitanEncounterScale.minionCount(
            DunewyrmTuning.SCORPIONS_PER_TUNNEL,
            com.hexvane.titan.combat.TitanEncounterScale.countPlayers(store, worm.getHeadPosition(), 100)));
        worm.setDigParticleTimer(0f);
        TitanSound.play(commandBuffer, DunewyrmTuning.DIG_SOUND, worm.getHeadPosition());
    }

    /**
     * Puts anyone standing on the snake onto the surface before the body sinks, so anchoring cannot
     * drag them underground.
     */
    private void dropRidersBeforeDig(@Nonnull final DunewyrmComponent worm,
                                     @Nonnull final Store<EntityStore> store,
                                     @Nonnull final CommandBuffer<EntityStore> commandBuffer) {
        final ChunkStore chunks = store.getExternalData().getWorld().getChunkStore();
        for (final DunewyrmSegment segment : worm.getSegments()) {
            if (segment.getRole() != DunewyrmSegmentRole.BODY
                && segment.getRole() != DunewyrmSegmentRole.HEAD) {
                continue;
            }
            final Vector3d centre = segment.getPosition();
            final double reach = DunewyrmTuning.CONTACT_RADIUS + 1.0;
            DunewyrmPlayers.forEach(store, (victim, pos) -> {
                final double dx = pos.x - centre.x;
                final double dz = pos.z - centre.z;
                if (dx * dx + dz * dz > reach * reach
                    || Math.abs(pos.y - centre.y) > DunewyrmTuning.CONTACT_RADIUS + 2.0) {
                    return;
                }
                if (!com.hexvane.titan.combat.TitanStandingOn.isAboveSegment(pos, centre)
                    && !com.hexvane.titan.combat.TitanStandingOn.isOnClimbable(store, victim)) {
                    return;
                }
                final double ground = GroundSampler.sample(chunks, pos.x, pos.y + 2.0, pos.z, 6, 8);
                if (GroundSampler.isValid(ground)) {
                    pos.y = ground + 0.05;
                } else {
                    pos.y = centre.y + 2.5;
                }
            });
        }
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
        if (ThreadLocalRandom.current().nextFloat() < dt * 4.0f) {
            final double ang = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            final double rad = ThreadLocalRandom.current().nextDouble() * DunewyrmTuning.POISON_RADIUS;
            scratch.set(
                worm.getPoisonCloud().x + Math.cos(ang) * rad,
                worm.getPoisonCloud().y + 0.05,
                worm.getPoisonCloud().z + Math.sin(ang) * rad);
            ParticleUtil.spawnParticleEffect(
                DunewyrmTuning.POISON_CLOUD_PARTICLE, scratch, (float) ang, 0f, 0f,
                1.0f, 1.0f, commandBuffer);
        }

        final EntityEffect poison = EntityEffect.getAssetMap().getAsset(DunewyrmTuning.POISON_EFFECT);
        if (poison == null) return;

        final Vector3d cloud = worm.getPoisonCloud();
        final double r2 = DunewyrmTuning.POISON_RADIUS * DunewyrmTuning.POISON_RADIUS;
        DunewyrmPlayers.forEach(store, (candidate, feet) -> {
            final double dx = feet.x - cloud.x;
            final double dz = feet.z - cloud.z;
            if (dx * dx + dz * dz > r2
                || feet.y < cloud.y - DunewyrmTuning.POISON_DEPTH
                || feet.y > cloud.y + DunewyrmTuning.POISON_HEIGHT) {
                return;
            }
            final var effects = commandBuffer.getComponent(candidate, EffectControllerComponent.getComponentType());
            if (effects != null) {
                effects.addEffect(candidate, poison, commandBuffer);
            }
        });
    }

    /**
     * Picks the heading closest to {@code desiredYaw} whose forward probe stays clear of the body.
     *
     * <p>Replaces the old "blend toward away-vector" nudge, which could not see the body ahead of the head
     * and let the head steer straight into a loop of itself. Each candidate is scored on the smallest
     * distance any point along its look-ahead ray comes to a trailing body segment; the neck is exempt.
     */
    private float steerClear(@Nonnull final DunewyrmComponent worm, final float desiredYaw) {
        final float straight = probeClearance(worm, desiredYaw);
        if (straight >= (float) DunewyrmTuning.SELF_AVOID_RADIUS) return desiredYaw;

        float bestYaw = desiredYaw;
        float bestScore = score(straight, 0f);
        for (final float offset : AVOID_OFFSETS) {
            for (int sign = -1; sign <= 1; sign += 2) {
                final float yaw = desiredYaw + sign * offset;
                final float clearance = probeClearance(worm, yaw);
                final float s = score(clearance, offset);
                if (s > bestScore) {
                    bestScore = s;
                    bestYaw = yaw;
                }
            }
        }
        return bestYaw;
    }

    private static final float[] AVOID_OFFSETS = {0.3f, 0.6f, 0.9f, 1.25f, 1.6f, 2.0f, 2.5f, 3.0f};

    /** Clear space is worth more than heading fidelity, but a full reverse still costs something. */
    private static float score(final float clearance, final float offset) {
        final float capped = Math.min(clearance, (float) DunewyrmTuning.SELF_AVOID_RADIUS);
        return capped - offset * 2.2f;
    }

    /** Smallest horizontal distance from any look-ahead probe point to a trailing body/tail segment. */
    private float probeClearance(@Nonnull final DunewyrmComponent worm, final float yaw) {
        final Vector3d head = worm.getHeadPosition();
        final double fx = forwardX(yaw);
        final double fz = forwardZ(yaw);
        final double look = DunewyrmTuning.AVOID_LOOKAHEAD;
        double minSq = Double.MAX_VALUE;

        for (int i = 1; i <= 4; i++) {
            final double d = look * i / 4.0;
            final double px = head.x + fx * d;
            final double pz = head.z + fz * d;
            int bodySeen = 0;
            for (final DunewyrmSegment segment : worm.getSegments()) {
                final DunewyrmSegmentRole role = segment.getRole();
                if (role != DunewyrmSegmentRole.BODY && role != DunewyrmSegmentRole.TAIL) continue;
                if (role == DunewyrmSegmentRole.BODY) {
                    bodySeen++;
                    if (bodySeen <= 2) continue;
                }
                final double dx = px - segment.getPosition().x;
                final double dz = pz - segment.getPosition().z;
                final double distSq = dx * dx + dz * dz;
                if (distSq < minSq) minSq = distSq;
            }
        }
        return minSq == Double.MAX_VALUE ? Float.MAX_VALUE : (float) Math.sqrt(minSq);
    }

    /** Base movement speed for this snake — the variant's, scaled down as it loses body segments. */
    private static float moveSpeed(@Nonnull final DunewyrmComponent worm, @Nonnull final TitanVariantAsset variant) {
        return variant.getMoveSpeed() * worm.speedScale();
    }

    /**
     * Yaw change allowed this tick. Bounded by the variant, the global cap, and the arc the body can
     * physically follow at this speed — turning tighter than {@link DunewyrmTuning#MIN_TURN_RADIUS} lays
     * the path back over itself and the segments stack up.
     */
    private static float turnStep(@Nonnull final TitanVariantAsset variant, final float speed, final float dt) {
        final float byRadius = (float) (Math.max(0.5f, speed) / DunewyrmTuning.MIN_TURN_RADIUS) * dt;
        return Math.min(Math.min(variant.getTurnSpeed() * dt, DunewyrmTuning.MAX_TURN_RATE * dt), byRadius);
    }

    /**
     * Moves the head forward along its current yaw. Never stops to turn in place: with the body laid out
     * along the head's path, a stationary head means a stationary body and a kinked path that piles the
     * segments onto each other. Steering away from the body is the job of {@link #steerClear}.
     */
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

        if (!tryPlaceHead(worm, store, nx, oy, nz, true)) {
            turnFromObstacle(worm, store, worm.getYaw());
            float yaw=worm.getYaw();
            if (!tryPlaceHead(worm,store,ox+forwardX(yaw)*speed*dt,oy,oz+forwardZ(yaw)*speed*dt,true)) {
                if (!tryPlaceHead(worm,store,ox+sideX*speed*dt,oy,oz+sideZ*speed*dt,true))
                    tryPlaceHead(worm,store,ox-sideX*speed*dt,oy,oz-sideZ*speed*dt,true);
            }
        }
    }

    private void turnFromObstacle(@Nonnull final DunewyrmComponent worm,
                                  @Nonnull final Store<EntityStore> store, final float yaw) {
        if (worm.getObstacleTurnCooldown() > 0f) return;
        worm.setObstacleTurnCooldown(0.75f);
        var head=worm.getHeadPosition();
        var chunks=store.getExternalData().getWorld().getChunkStore();
        // Check the entire route, including the first step out of an existing overlap.
        // A clear endpoint alone can select a route straight through the same tree.
        for (float angle : new float[]{0.55f,-0.55f,1.1f,-1.1f,1.65f,-1.65f,(float)Math.PI}) {
            float next=yaw+angle;
            double surface=DunewyrmTerrain.movementHeight(chunks,head.x,head.y,head.z,
                head.x+forwardX(next)*4,head.z+forwardZ(next)*4,true);
            if (GroundSampler.isValid(surface)) {
                worm.setYaw(next);
                worm.setChargeYaw(next);
                return;
            }
        }
        // Dense trees may not leave a full four block route. Take a safe short
        // retreat instead of staying trapped until a complete route opens.
        for (int i=1;i<=16;i++) {
            float next=yaw+(float)(i*Math.PI/8);
            if (GroundSampler.isValid(DunewyrmTerrain.movementHeight(chunks,head.x,head.y,head.z,
                    head.x+forwardX(next)*.5,head.z+forwardZ(next)*.5,true))) {
                worm.setYaw(next);worm.setChargeYaw(next);return;
            }
        }
    }

    private void maybeUnstickFromCave(@Nonnull final DunewyrmComponent worm,
                                      @Nonnull final Store<EntityStore> store) {
        if (worm.getState()==DunewyrmState.TUNNEL || worm.getState()==DunewyrmState.DYING) return;
        var head=worm.getHeadPosition();
        double surface=DunewyrmTerrain.headSurface(store.getExternalData().getWorld().getChunkStore(),head.x,head.z);
        // Spawned underground or covered by a new roof: recover above it instead of digging a shaft.
        if (GroundSampler.isValid(surface) && head.y<surface) head.y=surface;
    }

    private void spawnSlitherDust(@Nonnull final DunewyrmComponent worm,
                                  @Nonnull final CommandBuffer<EntityStore> commandBuffer,
                                  final float dt) {
        worm.setDigParticleTimer(worm.getDigParticleTimer() - dt);
        if (worm.getDigParticleTimer() > 0f) return;
        worm.setDigParticleTimer(DunewyrmTuning.SLITHER_DUST_INTERVAL);

        final Vector3d digAt = new Vector3d(
            worm.getHeadPosition().x,
            worm.getHeadPosition().y + 0.35,
            worm.getHeadPosition().z);
        ParticleUtil.spawnParticleEffect(
            DunewyrmTuning.SLITHER_PARTICLE, digAt, worm.getYaw(), 0f, 0f,
            DunewyrmTuning.SLITHER_DUST_SCALE, 0.55f, commandBuffer);
        ParticleUtil.spawnParticleEffect(
            DunewyrmTuning.DIG_PARTICLE, digAt, worm.getYaw(), 0f, 0f,
            DunewyrmTuning.SLITHER_DUST_SCALE * 0.85f, 0.45f, commandBuffer);

        int bodySeen = 0;
        for (final DunewyrmSegment segment : worm.getSegments()) {
            if (segment.getRole() != DunewyrmSegmentRole.BODY) continue;
            bodySeen++;
            // Sparse along the body so it reads as a trail, not a fog bank.
            if ((bodySeen & 1) == 0) continue;
            scratch.set(segment.getPosition().x, segment.getPosition().y + 0.25, segment.getPosition().z);
            ParticleUtil.spawnParticleEffect(
                DunewyrmTuning.SLITHER_PARTICLE, scratch, segment.getYaw(), 0f, 0f,
                DunewyrmTuning.SLITHER_DUST_SCALE * 0.7f, 0.4f, commandBuffer);
        }
    }

    private static boolean isPlayerOnSnake(@Nonnull final DunewyrmComponent worm,
                                           @Nonnull final Vector3d playerPos) {
        for (final DunewyrmSegment segment : worm.getSegments()) {
            if (segment.getRole() != DunewyrmSegmentRole.BODY
                && segment.getRole() != DunewyrmSegmentRole.HEAD) {
                continue;
            }
            if (com.hexvane.titan.combat.TitanStandingOn.isAboveSegment(playerPos, segment.getPosition())) {
                return true;
            }
        }
        return false;
    }

    /** True when the player is fighting from beside the body rather than in front of the head. */
    private static boolean isPlayerBesideBody(@Nonnull final DunewyrmComponent worm,
                                              @Nonnull final Vector3d playerPos) {
        final Vector3d head = worm.getHeadPosition();
        final double toPlayerX = playerPos.x - head.x;
        final double toPlayerZ = playerPos.z - head.z;
        final double ahead = toPlayerX * forwardX(worm.getYaw()) + toPlayerZ * forwardZ(worm.getYaw());
        if (ahead > 6.0) return false;

        final double rangeSq = DunewyrmTuning.FLANK_BODY_RANGE * DunewyrmTuning.FLANK_BODY_RANGE;
        for (final DunewyrmSegment segment : worm.getSegments()) {
            if (segment.getRole() != DunewyrmSegmentRole.BODY) continue;
            final double dx = playerPos.x - segment.getPosition().x;
            final double dz = playerPos.z - segment.getPosition().z;
            if (dx * dx + dz * dz <= rangeSq) return true;
        }
        return false;
    }

    /**
     * Places the head on the outdoor surface. Tall climbs and obstructed headroom require a detour.
     */
    private boolean tryPlaceHead(@Nonnull final DunewyrmComponent worm,
                                 @Nonnull final Store<EntityStore> store,
                                 final double x,
                                 final double y,
                                 final double z,
                                 final boolean blockTallClimbs) {
        final ChunkStore chunks = store.getExternalData().getWorld().getChunkStore();
        var head=worm.getHeadPosition();
        double newY=DunewyrmTerrain.movementHeight(chunks,head.x,y,head.z,x,z,blockTallClimbs);
        if (!GroundSampler.isValid(newY)) return false;
        worm.getHeadPosition().set(x,newY,z);
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
