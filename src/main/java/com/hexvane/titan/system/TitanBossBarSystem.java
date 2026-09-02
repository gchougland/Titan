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
 * Puts a boss bar across the top of the screen, and the fight's music in the ears, of anyone fighting a
 * titan.
 *
 * <p>The bar is the engine's own, and it works by naming an entity: the client finds that entity's Health
 * and draws the bar from it. A titan has no health of its own, only the ore nodes bolted to its back, so the
 * invisible root carries a pooled total and this system keeps it level with what is left of the nodes. One
 * bar then reads as the whole creature rather than as whichever lump you happen to be hitting.
 *
 * <p>It only goes up while the titan is on its feet. A sleeping one is meant to pass for a boulder, and
 * announcing it with a boss bar would give the disguise away from across the field.
 *
 * <p>The music rides on exactly the same set of players. Working out who is engaged with a titan is the
 * hard part and this system already does it, so the battle track goes to whoever the bar goes to and stops
 * when it does.
 */
public final class TitanBossBarSystem extends EntityTickingSystem<EntityStore> {

    /** {@link NetworkId} value meaning the root is not being replicated, so no bar can point at it. */
    private static final int NO_ENTITY = 0;

    /**
     * How far from a titan the bar stays up, in blocks.
     *
     * <p>Past the range a titan will chase to, so backing off for a breather does not make the bar flicker,
     * but short enough that it goes away once you have genuinely left the fight.
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

        titan.copyWeakpoints(nodes);
        syncPooledHealth(store, archetypeChunk, index, titan, nodes);

        engaged.clear();
        if (isFighting(titan)) collectNearbyPlayers(store, transform.getPosition(), engaged);

        updateViewers(store, archetypeChunk.getReferenceTo(index), titan, engaged);
    }

    /**
     * Copies what is left of the ore nodes onto the root's own Health, which is what the bar is drawn from.
     *
     * <p>What is left means the work still owed, not the rock still standing. A titan may carry more nodes
     * than a kill costs, and summing all of them would leave the bar with a tail it can never spend: break
     * the five that kill a seven-node temple and two full nodes would still be sitting in the total. So the
     * bar is the cheapest way out from here — the weakest nodes still needed, as many of them as there are
     * breaks left to owe — measured against a full bar of the same size.
     *
     * <p>That only ever falls. Damaging a node either lowers one already counted or drags it into the count
     * below something dearer, and breaking one drops both a term and the node that was cheapest to lose. So
     * it drains smoothly while a node is being worked on, steps down as each one goes, and reaches the
     * bottom on the break that kills. Spreading damage across every node instead of finishing them one at a
     * time moves the bar just as far; it is the same total either way.
     */
    private void syncPooledHealth(@Nonnull final Store<EntityStore> store,
                                  @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
                                  final int index,
                                  @Nonnull final TitanComponent titan,
                                  @Nonnull final List<Ref<EntityStore>> nodes) {

        final var stats = archetypeChunk.getComponent(index, EntityStatMap.getComponentType());
        if (stats == null || titan.getWeakpointsTotal() <= 0) return;

        final int healthIndex = DefaultEntityStatTypes.getHealth();

        // Never let it reach the floor while the titan is still standing: zero health is what the engine
        // reads as death, and the root has to outlive its own collapse.
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

    /** A titan on its feet and able to fight back. Asleep or already dying, there is nothing to show. */
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
     * conditional on the other. A root the client has no network id for cannot be pointed at by a bar, and
     * that is precisely the case where the fight most needs the music to still say it is happening; and a
     * variant naming no track still gets its bar. What the list has to guarantee is only that whatever was
     * turned on for a player is turned off again when they leave, which is why membership is decided once,
     * here, rather than by whichever of the two happened to succeed.
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

            // Restated every tick rather than only on joining, so two titans whose fights overlap cannot
            // leave a player in silence: whichever one is still engaged puts the track straight back.
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
     * <p>The ordinary route out is the tick above noticing the titan is no longer fighting, but a titan can
     * also be removed outright, by an unloading rig or by {@code /titan kill instant}. That gives the tick no
     * chance to run, and would leave whoever was fighting it staring at a bar for a creature that is gone.
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
