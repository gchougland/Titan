package com.hexvane.titan.yaga;

import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.system.TitanWorldSpawnSystem;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Keeps a Baba Yaga house on disk, and puts it back when its owner returns.
 *
 * <p>A house is close to a thousand entities and the engine throws those away with the chunks they stand
 * in, so between visits the house does not exist — {@link YagaMemory} does. That makes this system the
 * whole of a house's lifetime outside of a player's company: it writes down every house that is standing,
 * takes down the ones whose owner has gone, and rebuilds the ones whose owner has come back.
 *
 * <p>Teardown is done here rather than left to the engine on purpose. A house discarded with its chunk is
 * discarded silently, and the last thing written down about it would be however stale the previous sweep
 * was; taking it down deliberately means the record is current first.
 *
 * <p>Modelled on {@link TitanWorldSpawnSystem}, which does the same streaming for the combat titans. The
 * difference is where the truth lives: a combat titan comes back out of the world seed and only its death
 * is worth saving, while nothing about a house can be derived from anything.
 */
public final class YagaRespawnSystem extends TickingSystem<EntityStore> {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Seconds between sweeps.
     *
     * <p>Also the most work a crash can lose: a player who fills a chest and pulls the plug within two
     * seconds loses what they put in it. Short enough that this is hard to do on purpose, long enough that
     * a parked house is not being measured constantly.
     */
    private static final float SWEEP_INTERVAL = 2f;

    /**
     * Fractions of the distance the engine keeps simulated around a player, within which a house is
     * rebuilt and beyond which it is taken down.
     *
     * <p>The gap between them is what stops a house being built and destroyed on alternate sweeps by an
     * owner standing near the boundary. Both sit inside the simulated distance, because a house built
     * outside it would be dropped by the engine before it could take a step.
     *
     * @see TitanWorldSpawnSystem#simulatedRadius
     */
    private static final double RESTORE_FRACTION = 0.55;
    private static final double KEEP_FRACTION = 0.90;

    /** Per-world sweep state. Worlds tick on their own threads, so each keeps its own. */
    @Nonnull
    private final Map<String, WorldHouses> worlds = new ConcurrentHashMap<>();

    @Nonnull
    private final ResourceType<EntityStore, YagaMemory> memoryType;

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(
        TitanComponent.getComponentType(),
        YagaComponent.getComponentType(),
        TransformComponent.getComponentType());

    public YagaRespawnSystem(@Nonnull final ResourceType<EntityStore, YagaMemory> memoryType) {
        this.memoryType = memoryType;
    }

    /** What one world's houses looked like at the end of the last sweep. */
    private static final class WorldHouses {

        /**
         * The summary of each house as it was last written down, by house id, so an unchanged house is not
         * written down again.
         *
         * <p>Kept here rather than in the record because it is a question about this run of the server:
         * after a restart every house is rewritten once, which costs one save and is the safe direction to
         * be wrong in.
         */
        @Nonnull
        private final Map<UUID, Integer> written = new HashMap<>();

        /** Houses already being rebuilt, so the next sweep does not start a second one. */
        @Nonnull
        private final Set<UUID> pending = new HashSet<>();

        private float sweepTimer;
    }

    /** A house that is standing, and where it is standing. */
    private record Standing(@Nonnull Ref<EntityStore> root,
                            @Nonnull YagaComponent yaga,
                            @Nonnull UUID owner,
                            @Nonnull Vector3d position,
                            float yaw) {
    }

    @Override
    public void tick(final float dt, final int systemIndex, @Nonnull final Store<EntityStore> store) {
        final World world = store.getExternalData().getWorld();
        final WorldHouses houses = worlds.computeIfAbsent(world.getName(), key -> new WorldHouses());

        houses.sweepTimer -= dt;
        if (houses.sweepTimer > 0f) return;
        houses.sweepTimer = SWEEP_INTERVAL;

        final YagaMemory memory = store.getResource(memoryType);
        final Map<UUID, Standing> standing = collectStanding(store);

        write(houses, memory, standing);
        reap(store, world, houses, standing);
        restore(store, world, houses, memory, standing);

        if (memory.consumeDirty()) {
            final var data = store.getRegistry().getData();
            store.getResourceStorage().save(store, data, memoryType, memory);
        }
    }

    /**
     * Every house currently built in this world, by house id.
     *
     * <p>Ownerless houses are skipped rather than counted. One can only exist because something spawned a
     * house outside the normal paths, and there is nobody it could be rebuilt for.
     */
    @Nonnull
    private Map<UUID, Standing> collectStanding(@Nonnull final Store<EntityStore> store) {
        final var standing = new HashMap<UUID, Standing>();

        store.forEachChunk(query, (chunk, commandBuffer) -> {
            for (int index = 0; index < chunk.size(); index++) {
                final var titan = chunk.getComponent(index, TitanComponent.getComponentType());
                final var yaga = chunk.getComponent(index, YagaComponent.getComponentType());
                final var transform = chunk.getComponent(index, TransformComponent.getComponentType());
                if (titan == null || yaga == null || transform == null) continue;

                final UUID owner = yaga.getOwnerUuid();
                if (owner == null) continue;

                standing.put(yaga.getHouseId(), new Standing(chunk.getReferenceTo(index), yaga, owner,
                    new Vector3d(transform.getPosition()), titan.getYaw()));
            }
        });

        return standing;
    }

    /** Writes down every standing house whose summary has changed since it was last written. */
    private static void write(@Nonnull final WorldHouses houses,
                              @Nonnull final YagaMemory memory,
                              @Nonnull final Map<UUID, Standing> standing) {

        for (final var entry : standing.entrySet()) {
            remember(houses, memory, entry.getKey(), entry.getValue());
        }
    }

    /**
     * Writes one house down, unless nothing about it has moved or changed.
     *
     * <p>The containers go into the record by reference, so what is saved is whatever is in the chests at
     * the moment the file is written rather than a copy taken here. It matters when the house is torn down
     * in the same sweep: the record outlives the component, and a copy would have to be taken at exactly
     * the right moment to be worth anything.
     */
    private static void remember(@Nonnull final WorldHouses houses,
                                 @Nonnull final YagaMemory memory,
                                 @Nonnull final UUID houseId,
                                 @Nonnull final Standing house) {

        final YagaComponent yaga = house.yaga();
        final YagaFurnace furnace = yaga.getFurnace();
        final boolean resting = yaga.getMode() == YagaComponent.Mode.RESTING;

        // Position is quantised to a block. A house standing still still drifts a fraction as the body
        // settles onto the ground under it, and saving because of that would never stop.
        //
        // The furnace counts by what is in it and not by how far along the smelt is, so a house left
        // burning is written down when the ore goes in and when the bar comes out rather than every couple
        // of seconds in between. What that costs is the progress bar's position on a crash, which the
        // furnace would have made up from the clock anyway.
        final int summary = 31 * (31 * (31 * (31 * (31 * yaga.getStage().hashCode()
            + Boolean.hashCode(resting))
            + (int) Math.floor(house.position().x) * 31 + (int) Math.floor(house.position().z))
            + (int) Math.floor(house.position().y))
            + YagaInventory.fingerprint(yaga.getInventories()))
            + (furnace == null ? 0 : YagaInventory.fingerprint(furnace.containers()));

        // The record's absence overrides the summary. /titan yaga forget throws a record away without
        // touching the summaries, and a house rebuilt afterwards would otherwise look unchanged and never
        // be written down again.
        final Integer written = houses.written.get(houseId);
        if (written != null && written == summary && memory.get(houseId) != null) return;

        memory.remember(houseId, new YagaRecord(houseId, house.owner(), yaga.getStage(),
            house.position(), house.yaw(), resting, yaga.getInventories(),
            furnace == null ? null : furnace.getState()));
        houses.written.put(houseId, summary);
    }

    /**
     * Takes down houses whose owner is not around to see them.
     *
     * <p>Nothing is forgotten here. The house has just been written down, and taking it down is only the
     * cluster of entities going away until the owner comes back for it.
     */
    private static void reap(@Nonnull final Store<EntityStore> store,
                             @Nonnull final World world,
                             @Nonnull final WorldHouses houses,
                             @Nonnull final Map<UUID, Standing> standing) {

        final var iterator = standing.entrySet().iterator();
        while (iterator.hasNext()) {
            final var entry = iterator.next();
            final Standing house = entry.getValue();

            final Ref<EntityStore> owner = playerIn(world, house.owner());
            if (owner != null && isWithin(store, owner, house.position(), KEEP_FRACTION)) continue;

            final Ref<EntityStore> root = house.root();

            // Deferred, because removing an entity writes to the store and a tick may not.
            // Removing the root is enough; the parts drop themselves once their owner is gone.
            world.execute(() -> {
                if (root.isValid()) store.removeEntity(root, RemoveReason.REMOVE);
            });

            // Dropped from the summaries too, so the rebuild writes the house down again rather than
            // trusting a figure taken before it was taken apart.
            houses.written.remove(entry.getKey());
            iterator.remove();
        }
    }

    /** Rebuilds houses whose owner has come back within reach of where they left them. */
    private static void restore(@Nonnull final Store<EntityStore> store,
                                @Nonnull final World world,
                                @Nonnull final WorldHouses houses,
                                @Nonnull final YagaMemory memory,
                                @Nonnull final Map<UUID, Standing> standing) {

        final List<YagaRecord> records = memory.snapshot();
        if (records.isEmpty()) return;

        // Collected first, because rebuilding runs on a later tick and a record cannot be examined then:
        // the sweep would have to hold the memory open across the gap.
        final var due = new ArrayList<YagaRecord>();

        for (final YagaRecord record : records) {
            final UUID houseId = record.id();
            final UUID owner = record.ownerUuid();
            if (houseId == null || owner == null) continue;
            if (standing.containsKey(houseId) || houses.pending.contains(houseId)) continue;

            final Ref<EntityStore> player = playerIn(world, owner);
            if (player == null || !isWithin(store, player, record.position(), RESTORE_FRACTION)) continue;

            houses.pending.add(houseId);
            due.add(record);
        }

        if (due.isEmpty()) return;

        // Deferred, because a house is spawned entity by entity and writing to the store during a tick is
        // not allowed.
        world.execute(() -> {
            for (final YagaRecord record : due) {
                final UUID houseId = record.id();
                if (houseId == null) continue;

                houses.pending.remove(houseId);

                final YagaSpawn.Result result = YagaSpawn.restore(store, record);
                if (!result.ok()) {
                    LOGGER.at(Level.WARNING).log("Could not rebuild the Baba Yaga house belonging to %s: %s",
                        record.ownerUuid(), result.error());
                }
            }
        });
    }

    /**
     * The owner's entity, if they are logged in and in this world.
     *
     * <p>By UUID rather than by a saved reference, since a player who logs out and back in is a different
     * entity while the house is the same house.
     */
    @Nullable
    private static Ref<EntityStore> playerIn(@Nonnull final World world, @Nonnull final UUID owner) {

        for (final PlayerRef playerRef : world.getPlayerRefs()) {
            if (!owner.equals(playerRef.getUuid())) continue;

            final Ref<EntityStore> ref = playerRef.getReference();
            return ref != null && ref.isValid() ? ref : null;
        }
        return null;
    }

    /** Whether {@code position} is inside {@code fraction} of how far the engine simulates around a player. */
    private static boolean isWithin(@Nonnull final Store<EntityStore> store,
                                    @Nonnull final Ref<EntityStore> player,
                                    @Nonnull final Vector3d position,
                                    final double fraction) {

        final var transform = store.getComponent(player, TransformComponent.getComponentType());
        if (transform == null) return false;

        final double reach = TitanWorldSpawnSystem.simulatedRadius(store, player) * fraction;
        return transform.getPosition().distanceSquared(position) <= reach * reach;
    }
}
