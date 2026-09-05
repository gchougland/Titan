package com.hexvane.titan.system;

import com.hexvane.titan.combat.TitanBattleMusic;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.entity.TitanState;
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
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shows a boss bar and plays the battle music for everyone engaged with a titan.
 *
 * <p>The engine's bar works by naming an entity: the client finds that entity's Health and draws the bar
 * from it. A titan has no health of its own, only the ore nodes bolted to its back, so the invisible root
 * carries a pooled total that this system keeps level with what is left of the nodes, and one bar reads as
 * the whole creature rather than as a single lump of ore.
 *
 * <p>The bar only goes up while the titan is on its feet, since a sleeping one is meant to pass for a
 * boulder. The music goes to the same players and is tracked by the same list: working out who is engaged
 * is the hard part and is already done here.
 */
public final class TitanBossBarSystem extends EntityTickingSystem<EntityStore> {

    /** {@link NetworkId} value meaning the root is not being replicated, so no bar can point at it. */
    private static final int NO_ENTITY = 0;

    /**
     * How far from a titan the bar stays up, in blocks. Past the range a titan will chase to, so backing off
     * for a breather does not flicker the bar, but short enough to clear once the player leaves the fight.
     */
    private static final double VIEW_RADIUS = 72.0;

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(
        TitanComponent.getComponentType(),
        TransformComponent.getComponentType());

    @Nonnull
    private final List<Ref<EntityStore>> engaged = new ArrayList<>();
    @Nonnull
    private final List<Ref<EntityStore>> nodes = new ArrayList<>();
    /** Node healths for one titan, sorted, reused between ticks so the bar costs no allocations. */
    @Nonnull
    private float[] healths = new float[16];

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

        final var titan = archetypeChunk.getComponent(index, TitanComponent.getComponentType());
        final var transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        if (titan == null || transform == null) return;

        // A pet is not a fight, so it gets no bar and no music. Its shell voxels are still weakpoints
        // while it is an egg, which is what would otherwise put a boss bar over a nest.
        final var variant = titan.getVariant();
        if (variant != null && variant.isPet()) return;

        titan.copyWeakpoints(nodes);
        syncPooledHealth(store, archetypeChunk, index, titan, nodes);

        engaged.clear();
        if (isFighting(titan)) collectNearbyPlayers(store, transform.getPosition(), engaged);

        updateViewers(store, archetypeChunk.getReferenceTo(index), titan, engaged);
    }

    /**
     * Copies what is left of the ore nodes onto the root's own Health, which the bar is drawn from.
     *
     * <p>What is left means the work still owed, not the rock still standing. A titan may carry more nodes
     * than a kill costs, so summing all of them would leave the bar with a tail it can never spend. The
     * total is instead the cheapest way out from here: the weakest nodes still needed, as many of them as
     * there are breaks left to owe, measured against a full bar of the same size.
     *
     * <p>That total only ever falls. Damage either lowers a node already counted or pulls it into the count
     * below a dearer one, and a break drops both a term and the node that was cheapest to lose. So the bar
     * drains while a node is being worked on, steps down as each one goes, and bottoms out on the break that
     * kills, and spreading damage across every node moves it just as far.
     */
    private void syncPooledHealth(@Nonnull final Store<EntityStore> store,
                                  @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                                  final int index,
                                  @Nonnull final TitanComponent titan,
                                  @Nonnull final List<Ref<EntityStore>> nodes) {

        final var stats = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
        if (stats == null || titan.getWeakpointsTotal() <= 0) return;

        final int healthIndex = DefaultEntityStatTypes.getHealth();

        // Zero health reads as death to the engine and the root has to outlive the collapse, so the value
        // is held above a floor while the titan is still standing.
        final float floor = Math.min(1f, titan.getTotalHealth());
        final int needed = titan.getWeakpointsStillNeeded();
        if (needed <= 0) {
            stats.setStatValue(healthIndex, floor);
            return;
        }

        if (healths.length < nodes.size()) healths = new float[nodes.size()];

        int found = 0;
        for (final Ref<EntityStore> node : nodes) {
            if (node == null || !node.isValid()) continue;
            final var nodeStats = store.getComponent(node, EntityStatMap.getComponentType());
            final var health = nodeStats != null ? nodeStats.get(healthIndex) : null;
            if (health != null) healths[found++] = Math.max(0f, health.get());
        }

        Arrays.sort(healths, 0, found);

        float remaining = 0f;
        for (int i = 0, count = Math.min(needed, found); i < count; i++) remaining += healths[i];

        stats.setStatValue(healthIndex, Math.max(floor, remaining));
    }

    /** @return whether the titan is on its feet and able to fight back. Asleep or dying, there is nothing to show. */
    private static boolean isFighting(@Nonnull final TitanComponent titan) {
        final TitanState state = titan.getState();
        return state != TitanState.SLEEPING && state != TitanState.DYING && titan.getWeakpointsTotal() > 0;
    }

    private static void collectNearbyPlayers(@Nonnull final Store<EntityStore> store,
                                             @Nonnull final Vector3d position,
                                             @Nonnull final List<Ref<EntityStore>> out) {
        for (final Ref<EntityStore> candidate : TargetUtil.getAllEntitiesInSphere(position, VIEW_RADIUS, store)) {
            if (!candidate.isValid()) continue;
            if (store.getComponent(candidate, Player.getComponentType()) == null) continue;
            out.add(candidate);
        }
    }

    /**
     * Brings the set of players this titan is announcing itself to in line with the set that should be,
     * sending a packet only to those whose state actually changed.
     *
     * <p>The bar and the music go to the same players and are remembered by the same list, but neither is
     * conditional on the other: a root with no network id can carry no bar yet still needs the music, and a
     * variant naming no track still gets its bar. Membership is decided here, once, so whatever was turned
     * on for a player is turned off again when they leave.
     */
    private static void updateViewers(@Nonnull final ComponentAccessor<EntityStore> accessor,
                                      @Nonnull final Ref<EntityStore> self,
                                      @Nonnull final TitanComponent titan,
                                      @Nonnull final List<Ref<EntityStore>> engaged) {

        final int networkId = networkIdOf(accessor, self);
        final var viewers = titan.getBarViewers();
        final var variant = titan.getVariant();
        final int music = TitanBattleMusic.resolve(variant);

        for (int i = viewers.size() - 1; i >= 0; i--) {
            final Ref<EntityStore> viewer = viewers.get(i);
            if (engaged.contains(viewer)) continue;
            viewers.remove(i);
            if (networkId != NO_ENTITY) hide(accessor, viewer, networkId);
            TitanBattleMusic.clear(accessor, viewer, music);
        }

        final String name = variant != null ? variant.getDisplayName() : "Titan";

        for (final Ref<EntityStore> player : engaged) {
            if (!player.isValid()) continue;

            // Restated every tick, not just on joining, so overlapping fights cannot leave a player in
            // silence: the titan still engaged puts the track straight back.
            TitanBattleMusic.apply(accessor, player, music);

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

    private static boolean write(@Nonnull final ComponentAccessor<EntityStore> accessor,
                                 @Nonnull final Ref<EntityStore> playerRef,
                                 @Nonnull final UpdateBossBar packet) {
        if (!playerRef.isValid()) return false;
        final var player = accessor.getComponent(playerRef, PlayerRef.getComponentType());
        if (player == null) return false;

        player.getPacketHandler().writeNoCache(packet);
        return true;
    }

    /**
     * Takes the bar down when a titan leaves the world without dying.
     *
     * <p>Normally the tick above notices the titan has stopped fighting, but an outright removal, by an
     * unloading site or by {@code /titan kill instant}, never gives it that chance.
     */
    public static final class Removal extends HolderSystem<EntityStore> {

        @Nonnull
        private final Query<EntityStore> query = Archetype.of(TitanComponent.getComponentType());

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

            final var titan = holder.getComponent(TitanComponent.getComponentType());
            if (titan == null || titan.getBarViewers().isEmpty()) return;

            // Read off the holder rather than a Ref: by now the entity is out of the store.
            final var networkId = holder.getComponent(NetworkId.getComponentType());
            final int music = TitanBattleMusic.resolve(titan.getVariant());

            for (final Ref<EntityStore> viewer : titan.getBarViewers()) {
                if (networkId != null) hide(store, viewer, networkId.getId());
                TitanBattleMusic.clear(store, viewer, music);
            }
            titan.getBarViewers().clear();
        }
    }
}
