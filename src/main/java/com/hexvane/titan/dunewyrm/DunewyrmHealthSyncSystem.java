package com.hexvane.titan.dunewyrm;

import com.hexvane.titan.combat.TitanBattleMusic;
import com.hexvane.titan.combat.TitanBattleWeather;
import com.hexvane.titan.spawn.TitanPartBuilder;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.packets.interface_.UpdateBossBar;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the Dunewyrm root health bar aligned with encounter remaining HP and shows a boss bar / battle
 * music to nearby players. Clears both when the fight ends or the root is removed.
 */
public final class DunewyrmHealthSyncSystem extends EntityTickingSystem<EntityStore> {

    private static final double VIEW_RADIUS = 72.0;
    private static final int NO_ENTITY = 0;

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(
        DunewyrmComponent.getComponentType(),
        TransformComponent.getComponentType(),
        NetworkId.getComponentType());

    @Nonnull
    private final List<Ref<EntityStore>> engaged = new ArrayList<>();

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
        final var networkIdComp = archetypeChunk.getComponent(index, NetworkId.getComponentType());
        if (worm == null || transform == null || networkIdComp == null) return;

        final Ref<EntityStore> self = archetypeChunk.getReferenceTo(index);

        if (worm.getState() == DunewyrmState.DYING) {
            dismiss(commandBuffer, self, worm);
            return;
        }

        if (!worm.isPrimary()) {
            promoteIfNeeded(store, worm);
            if (!worm.isPrimary()) {
                dismiss(commandBuffer, self, worm);
                return;
            }
        }

        final DunewyrmEncounter encounter = DunewyrmEncounter.getOrCreate(worm.getEncounterId());
        final var stats = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
        if (stats != null) {
            final float max = Math.max(1f, encounter.getInitialBodyHealth());
            final float current = Math.max(0f, encounter.getRemainingBodyHealth());
            TitanPartBuilder.applyHealth(stats, max);
            stats.setStatValue(DefaultEntityStatTypes.getHealth(), current);
        }

        engaged.clear();
        collectNearbyPlayers(store, transform.getPosition(), engaged);
        updateViewers(commandBuffer, self, worm, engaged);
    }

    /** Tears down bar + music for every player still listed on this snake. */
    public static void dismiss(@Nonnull final ComponentAccessor<EntityStore> accessor,
                               @Nonnull final Ref<EntityStore> self,
                               @Nonnull final DunewyrmComponent worm) {
        if (worm.getBarViewers().isEmpty()) return;
        final int networkId = networkIdOf(accessor, self);
        final int music = TitanBattleMusic.resolve(worm.getVariant());
        final int weather = TitanBattleWeather.resolve(worm.getVariant());
        for (final Ref<EntityStore> viewer : worm.getBarViewers()) {
            if (networkId != NO_ENTITY) hide(accessor, viewer, networkId);
            TitanBattleMusic.clear(accessor, viewer, music);
            TitanBattleWeather.clear(accessor, viewer, weather);
        }
        worm.getBarViewers().clear();
    }

    private static void updateViewers(@Nonnull final ComponentAccessor<EntityStore> accessor,
                                      @Nonnull final Ref<EntityStore> self,
                                      @Nonnull final DunewyrmComponent worm,
                                      @Nonnull final List<Ref<EntityStore>> engaged) {

        final int networkId = networkIdOf(accessor, self);
        final var viewers = worm.getBarViewers();
        final var variant = worm.getVariant();
        final int music = TitanBattleMusic.resolve(variant);
        final int weather = TitanBattleWeather.resolve(variant);
        final String name = variant != null && variant.getDisplayName() != null
            ? variant.getDisplayName()
            : "Dunewyrm";

        for (int i = viewers.size() - 1; i >= 0; i--) {
            final Ref<EntityStore> viewer = viewers.get(i);
            if (engaged.contains(viewer)) continue;
            viewers.remove(i);
            if (networkId != NO_ENTITY) hide(accessor, viewer, networkId);
            TitanBattleMusic.clear(accessor, viewer, music);
            TitanBattleWeather.clear(accessor, viewer, weather);
        }

        for (final Ref<EntityStore> player : engaged) {
            if (!player.isValid()) continue;
            TitanBattleMusic.apply(accessor, player, music);
            TitanBattleWeather.apply(accessor, player, weather);
            if (viewers.contains(player)) continue;
            if (networkId != NO_ENTITY) {
                write(accessor, player, new UpdateBossBar(networkId, Message.raw(name).getFormattedMessage(), false));
            }
            viewers.add(player);
        }
    }

    private static int networkIdOf(@Nonnull final ComponentAccessor<EntityStore> accessor,
                                   @Nonnull final Ref<EntityStore> ref) {
        if (!ref.isValid()) return NO_ENTITY;
        final var networkId = accessor.getComponent(ref, NetworkId.getComponentType());
        return networkId != null ? networkId.getId() : NO_ENTITY;
    }

    private static void hide(@Nonnull final ComponentAccessor<EntityStore> accessor,
                             @Nonnull final Ref<EntityStore> playerRef,
                             final int networkId) {
        write(accessor, playerRef, new UpdateBossBar(networkId, null, true));
    }

    private static void promoteIfNeeded(@Nonnull final Store<EntityStore> store,
                                        @Nonnull final DunewyrmComponent worm) {
        final boolean[] hasPrimary = {false};
        store.forEachChunk(
            Archetype.of(DunewyrmComponent.getComponentType()),
            (chunk, ignored) -> {
                for (int i = 0; i < chunk.size(); i++) {
                    final var other = chunk.getComponent(i, DunewyrmComponent.getComponentType());
                    if (other == null) continue;
                    if (!other.getEncounterId().equals(worm.getEncounterId())) continue;
                    if (other.isPrimary() && other.getState() != DunewyrmState.DYING) {
                        hasPrimary[0] = true;
                        return;
                    }
                }
            });
        if (!hasPrimary[0]) worm.setPrimary(true);
    }

    private static void collectNearbyPlayers(@Nonnull final Store<EntityStore> store,
                                             @Nonnull final Vector3d position,
                                             @Nonnull final List<Ref<EntityStore>> out) {
        DunewyrmPlayers.collectWithin(store, position, VIEW_RADIUS, out);
    }

    private static void write(@Nonnull final ComponentAccessor<EntityStore> accessor,
                              @Nonnull final Ref<EntityStore> playerRef,
                              @Nonnull final UpdateBossBar packet) {
        if (!playerRef.isValid()) return;
        final var player = accessor.getComponent(playerRef, PlayerRef.getComponentType());
        if (player == null) return;
        player.getPacketHandler().writeNoCache(packet);
    }

    /** Clears bar + music when a Dunewyrm root is removed without a dying tick. */
    public static final class Removal extends HolderSystem<EntityStore> {

        @Nonnull
        private final Query<EntityStore> query = Archetype.of(DunewyrmComponent.getComponentType());

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public void onEntityAdd(@Nonnull final Holder<EntityStore> holder,
                                @Nonnull final AddReason reason,
                                @Nonnull final Store<EntityStore> store) {
        }

        @Override
        public void onEntityRemoved(@Nonnull final Holder<EntityStore> holder,
                                    @Nonnull final RemoveReason reason,
                                    @Nonnull final Store<EntityStore> store) {
            final var worm = holder.getComponent(DunewyrmComponent.getComponentType());
            if (worm == null) return;

            if (!worm.getBarViewers().isEmpty()) {
                final var networkId = holder.getComponent(NetworkId.getComponentType());
                final int music = TitanBattleMusic.resolve(worm.getVariant());
                final int weather = TitanBattleWeather.resolve(worm.getVariant());
                for (final Ref<EntityStore> viewer : worm.getBarViewers()) {
                    if (networkId != null) hide(store, viewer, networkId.getId());
                    TitanBattleMusic.clear(store, viewer, music);
                    TitanBattleWeather.clear(store, viewer, weather);
                }
                worm.getBarViewers().clear();
            }

            // Unload / reap — site stays uncleared so Maintain can respawn a fresh snake. Splits and deaths
            // already did their own bookkeeping.
            if (worm.getState() != DunewyrmState.DYING && !worm.isEncounterReleased()) {
                final DunewyrmEncounter encounter = DunewyrmEncounter.getOrCreate(worm.getEncounterId());
                encounter.removeSnake();
                if (encounter.getLivingSnakes() <= 0 && encounter.getRemainingBodyHealth() > 0.5f) {
                    DunewyrmEncounter.remove(worm.getEncounterId());
                }
            }
        }
    }
}
