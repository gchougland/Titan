package com.hexvane.titan.combat;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.modules.physics.util.PhysicsMath;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembershipSystems;
import com.hypixel.hytale.server.flock.FlockPlugin;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * Signature ability for the Scarab Battleaxe: spawns a friendly scorpion near the wielder.
 *
 * <p>Registered as {@code TitanScarabSummon}. The JSON chain gates SignatureEnergy and plays the cast
 * animation; this interaction places the ally and tries to flock it to the summoner (Adventure mode).
 * Lifetime is timer-only via {@code Template_Titan_Summoned_Ally} so Creative summoners still keep it.
 *
 * <p>Spawning is deferred through {@code commandBuffer.run} — the same pattern as vanilla
 * {@code SpawnNPCInteraction} — because {@code Store.addEntity} cannot run while the interaction
 * tick system is already processing the store.
 */
public final class TitanScarabSummonInteraction extends SimpleInstantInteraction {

    @Nonnull
    public static final String TYPE = "TitanScarabSummon";

    @Nonnull
    public static final String NPC_ROLE = "Titan_Summoned_Scorpion";

    /** Horizontal offset ahead of the player so the scorpion does not spawn inside them. */
    private static final double SPAWN_FORWARD = 2.0;

    @Nonnull
    private static final String SPAWN_PARTICLE_GROUND = "Praetorian_Summon_Ground";

    @Nonnull
    private static final String SPAWN_PARTICLE_SAND = "Block_Break_Sand";

    @Nonnull
    private static final String SPAWN_PARTICLE_BURST = "Praetorian_Summon_Spawn";

    @Nonnull
    private static final String SPAWN_SOUND_SCORPION = "SFX_Scorpion_Alerted";

    @Nonnull
    private static final String SPAWN_SOUND_EARTH = "SFX_Golem_Earth_Alerted";

    @Nonnull
    public static final BuilderCodec<TitanScarabSummonInteraction> CODEC = BuilderCodec
        .builder(TitanScarabSummonInteraction.class, TitanScarabSummonInteraction::new, SimpleInstantInteraction.CODEC)
        .documentation("Spawns a Titan_Summoned_Scorpion ally near the player holding the Scarab Battleaxe.")
        .build();

    @Override
    protected void firstRun(@Nonnull final InteractionType type,
                            @Nonnull final InteractionContext context,
                            @Nonnull final CooldownHandler cooldownHandler) {

        final var commandBuffer = context.getCommandBuffer();
        if (commandBuffer == null) return;

        final var entity = context.getEntity();
        if (entity == null || !entity.isValid()) return;

        final var transform = commandBuffer.getComponent(entity, TransformComponent.getComponentType());
        if (transform == null) return;

        final Vector3d pos = transform.getPosition();
        final float yaw = transform.getRotation().yaw();

        final Vector3d spawnPos = new Vector3d(
            pos.x + PhysicsMath.headingX(yaw) * SPAWN_FORWARD,
            pos.y,
            pos.z + PhysicsMath.headingZ(yaw) * SPAWN_FORWARD);
        final Rotation3f spawnRot = new Rotation3f(0, yaw, 0);

        // Queue outside the interaction tick — Store.addEntity is illegal while the store is processing.
        commandBuffer.run(store -> {
            final var spawned = NPCPlugin.get().spawnNPC(store, NPC_ROLE, null, spawnPos, spawnRot);
            ParticleUtil.spawnParticleEffect(SPAWN_PARTICLE_GROUND, spawnPos, store);
            ParticleUtil.spawnParticleEffect(SPAWN_PARTICLE_SAND, spawnPos, store);
            ParticleUtil.spawnParticleEffect(SPAWN_PARTICLE_BURST, spawnPos, store);
            TitanSound.play(store, SPAWN_SOUND_EARTH, spawnPos);
            TitanSound.play(store, SPAWN_SOUND_SCORPION, spawnPos);

            if (spawned != null && entity.isValid()) {
                tryJoinPlayerFlock(entity, spawned.first(), store);
            }
        });
    }

    /**
     * Mirrors ActionFlockJoin: player first (becomes leader in Adventure), then the ally.
     * Creative summoners are rejected by the engine; the ally still lives on the timer.
     */
    private static void tryJoinPlayerFlock(@Nonnull final Ref<EntityStore> player,
                                           @Nonnull final Ref<EntityStore> ally,
                                           @Nonnull final Store<EntityStore> store) {
        if (!player.isValid() || !ally.isValid()) return;

        final var npc = store.getComponent(ally, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null) return;

        final var flock = FlockPlugin.createFlock(store, npc.getRole());
        FlockMembershipSystems.join(player, flock, store);
        FlockMembershipSystems.join(ally, flock, store);
    }

    @Nonnull
    @Override
    public String toString() {
        return "TitanScarabSummonInteraction{} " + super.toString();
    }
}
