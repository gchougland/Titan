package com.hexvane.titan.system;

import com.hexvane.titan.asset.TitanSpawnRuleAsset;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.entity.TitanComponent;
import com.hexvane.titan.spawn.ColliderMode;
import com.hexvane.titan.spawn.TitanSite;
import com.hexvane.titan.spawn.TitanSiteMemory;
import com.hexvane.titan.spawn.TitanSpawner;
import com.hexvane.titan.spawn.TitanTerrainProbe;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Streams titans in and out of the world around players.
 *
 * <p>Where they stand is not decided here. {@link TitanSite} derives that from the world seed, so this
 * system only has to notice that a player has come within reach of a site the seed already placed, check
 * the terrain there is worth standing on, and build it. Because the build is seeded from the site too, the
 * titan that comes back after an unload, a relog or a server restart is the same one down to the placement
 * of its ore nodes. The single fact that cannot be recovered from the seed, that somebody already killed
 * one, is the only thing {@link TitanSiteMemory} writes to disk.
 *
 * <p>Assembly cannot be hidden from the player. Chunks stop loading and entities stop being sent at the
 * same distance, so any spot whose ground can be read is also a spot the player would be shown a titan
 * appearing in. Rather than pretend otherwise, each sweep builds the furthest valid site it can find, which
 * in normal play puts assembly out at the edge of view where it is easy to miss.
 */
public final class TitanWorldSpawnSystem extends TickingSystem<EntityStore> {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Seconds between sweeps. Titans are slow to matter and the scan touches chunk data, so this is lazy. */
    private static final float SCAN_INTERVAL = 2f;

    /**
     * Both bands are fractions of the distance out to which the engine keeps chunks around a player
     * simulated, not fixed block counts, because that distance depends on the player's view setting.
     *
     * <p>Everything has to happen inside it. Outside, the ground cannot be read, so there is nothing to
     * measure a site against; worse, a titan left out there is quietly destroyed, because the engine
     * discards entities in sections that stop ticking and a titan is not the kind of thing it can put in
     * cold storage. Building a little inside the edge and tearing down just short of it means the titan is
     * always taken down by us, on purpose, rather than by the engine behind our back.
     */
    private static final double SPAWN_FRACTION = 0.80;
    private static final double DESPAWN_FRACTION = 0.95;
    /** Only wide enough to keep one from being built on top of somebody. */
    private static final double SPAWN_MIN_RADIUS = 24;

    /** Hard ceiling per world. Each titan is roughly two hundred entities that move every tick. */
    private static final int MAX_ACTIVE = 3;
    /** At most one build per sweep, so a player crossing a dense stretch gets them fed in gradually. */
    private static final int MAX_SPAWNS_PER_SCAN = 1;

    /**
     * Footprint the ground has to accommodate, in blocks, and how uneven it may be across that span.
     *
     * <p>Generous on purpose. Natural terrain rolls, the legs are solved with inverse kinematics and the
     * body settles onto whatever is under it, so the check only has to rule out cliff edges and ravines.
     * Demanding genuinely flat ground rejects almost everywhere outside a desert.
     *
     * <p>These are the figures for a titan a few blocks across. A variant that needs more room says so
     * itself; see {@link #isBuildableFor}.
     */
    public static final int FOOTPRINT_RADIUS = 4;
    public static final int FOOTPRINT_RELIEF = 6;
    public static final int HEADROOM = 12;

    /** Per-world siting state. Worlds tick on their own threads, so each keeps its own. */
    @Nonnull
    private final Map<String, WorldSites> worlds = new ConcurrentHashMap<>();

    @Nonnull
    private final ResourceType<EntityStore, TitanSiteMemory> memoryType;

    public TitanWorldSpawnSystem(@Nonnull final ResourceType<EntityStore, TitanSiteMemory> memoryType) {
        this.memoryType = memoryType;
    }

    /** The transient half of one world's siting state; the persistent half lives in {@link TitanSiteMemory}. */
    private static final class WorldSites {
        @Nonnull
        private final Long2ObjectMap<Ref<EntityStore>> active = new Long2ObjectOpenHashMap<>();
        /** Cells the seed or the terrain has ruled out for good, so later sweeps skip them outright. */
        @Nonnull
        private final LongOpenHashSet barren = new LongOpenHashSet();
        /** Cells with a build already queued for the next tick. */
        @Nonnull
        private final LongOpenHashSet pending = new LongOpenHashSet();

        private float scanTimer;
    }

    /** A cell that has passed the cheap checks and is waiting to be measured. */
    private record Candidate(long key, double x, double z, float yaw,
                             double occupancyRoll, double variantRoll, double distance) {
    }

    /** A player, and how far around them the world is actually being simulated. */
    private record Watcher(@Nonnull Vector3d position, double simulated) {

        double spawnRadius() {
            return simulated * SPAWN_FRACTION;
        }

        double keepRadius() {
            return simulated * DESPAWN_FRACTION;
        }
    }

    @Override
    public void tick(final float dt, final int systemIndex, @Nonnull final Store<EntityStore> store) {
        final World world = store.getExternalData().getWorld();
        final WorldSites sites = worlds.computeIfAbsent(world.getName(), key -> new WorldSites());
        final TitanSiteMemory memory = store.getResource(memoryType);

        memory.expire(dt);

        sites.scanTimer -= dt;
        if (sites.scanTimer > 0f) return;
        sites.scanTimer = SCAN_INTERVAL;

        if (memory.consumeDirty()) {
            final var data = store.getRegistry().getData();
            store.getResourceStorage().save(store, data, memoryType, memory);
        }

        final List<Watcher> watchers = watchers(store, world);
        reap(store, world, sites, watchers);

        if (watchers.isEmpty() || !TitanSpawnRuleAsset.hasRules()) return;
        seed(store, world, sites, memory, watchers);
    }

    @Nonnull
    private static List<Watcher> watchers(@Nonnull final Store<EntityStore> store, @Nonnull final World world) {
        final var watchers = new ArrayList<Watcher>(world.getPlayerCount());
        for (final PlayerRef playerRef : world.getPlayerRefs()) {
            final Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) continue;

            final var transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) continue;

            watchers.add(new Watcher(new Vector3d(transform.getPosition()), simulatedRadius(store, ref)));
        }
        return watchers;
    }

    /**
     * How far around a player the engine keeps chunk sections ticking, in blocks.
     *
     * <p>The player's own view setting, capped by the server's ceiling on how far it will simulate.
     */
    public static double simulatedRadius(@Nonnull final Store<EntityStore> store, @Nonnull final Ref<EntityStore> ref) {
        final var player = store.getComponent(ref, Player.getComponentType());
        final int chunks = player == null
            ? Player.DEFAULT_VIEW_RADIUS_CHUNKS
            : Math.min(ChunkTracker.MAX_HOT_LOADED_RADIUS, player.getViewRadius());
        return (double) Math.max(ChunkTracker.MIN_LOADED_RADIUS, chunks) * ChunkUtil.SIZE;
    }

    /**
     * Takes down titans nobody is near any more.
     *
     * <p>Kills are not detected here. A titan can go missing for reasons that have nothing to do with a
     * player beating it, and treating those as kills would leave sites empty all over the world for no
     * reason, so the AI reports a death at the moment it happens and anything else is just an unload.
     */
    private static void reap(@Nonnull final Store<EntityStore> store,
                             @Nonnull final World world,
                             @Nonnull final WorldSites sites,
                             @Nonnull final List<Watcher> watchers) {

        final var iterator = sites.active.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            final var entry = iterator.next();
            final Ref<EntityStore> root = entry.getValue();

            if (!root.isValid()) {
                iterator.remove();
                continue;
            }

            final var transform = store.getComponent(root, TransformComponent.getComponentType());
            if (transform == null) {
                iterator.remove();
                continue;
            }

            if (isWatched(watchers, transform.getPosition())) continue;

            // The site is only being unloaded, and the seed rebuilds the same titan there on the next
            // visit. Removing the root is enough, since parts and ore nodes drop themselves once their
            // owner is gone.
            world.execute(() -> {
                if (root.isValid()) store.removeEntity(root, RemoveReason.REMOVE);
            });
            iterator.remove();
        }
    }

    private static void seed(@Nonnull final Store<EntityStore> store,
                             @Nonnull final World world,
                             @Nonnull final WorldSites sites,
                             @Nonnull final TitanSiteMemory memory,
                             @Nonnull final List<Watcher> watchers) {

        int budget = Math.min(MAX_SPAWNS_PER_SCAN, MAX_ACTIVE - sites.active.size() - sites.pending.size());
        if (budget <= 0) return;

        final List<Candidate> candidates = collectCandidates(world, sites, memory, watchers);
        if (candidates.isEmpty()) return;

        // Furthest first. Assembly is visible whatever we do, so the best available answer is to do it as
        // far away as the loaded terrain allows and let the player walk up to something already standing.
        candidates.sort(Comparator.comparingDouble(Candidate::distance).reversed());

        final ChunkStore chunkStore = world.getChunkStore();
        final long worldSeed = world.getWorldConfig().getSeed();

        for (final Candidate candidate : candidates) {
            if (trySpawn(store, world, sites, chunkStore, worldSeed, candidate) && --budget <= 0) return;
        }
    }

    /** Cells near a player that the cheap, purely arithmetic checks have not already ruled out. */
    @Nonnull
    private static List<Candidate> collectCandidates(@Nonnull final World world,
                                                     @Nonnull final WorldSites sites,
                                                     @Nonnull final TitanSiteMemory memory,
                                                     @Nonnull final List<Watcher> watchers) {

        final long worldSeed = world.getWorldConfig().getSeed();
        final var roll = new TitanSite.Roll();
        final var candidates = new ArrayList<Candidate>();
        final var seen = new LongOpenHashSet();

        for (final Watcher watcher : watchers) {
            final Vector3d at = watcher.position();
            final double reach = watcher.spawnRadius();

            final int minCellX = TitanSite.cellOf(at.x - reach);
            final int maxCellX = TitanSite.cellOf(at.x + reach);
            final int minCellZ = TitanSite.cellOf(at.z - reach);
            final int maxCellZ = TitanSite.cellOf(at.z + reach);

            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                    final long key = TitanSite.cellKey(cellX, cellZ);
                    if (!seen.add(key)) continue;
                    if (sites.barren.contains(key) || sites.active.containsKey(key)
                        || sites.pending.contains(key) || memory.isCleared(key)) continue;

                    TitanSite.roll(worldSeed, cellX, cellZ, roll);

                    final double distance = Math.hypot(roll.x() - at.x, roll.z() - at.z);
                    if (distance > reach || distance < SPAWN_MIN_RADIUS) continue;

                    candidates.add(new Candidate(
                        key, roll.x(), roll.z(), roll.yaw(), roll.occupancy(), roll.variant(), distance));
                }
            }
        }
        return candidates;
    }

    /**
     * Whether the ground at a site will hold the variant that site rolled.
     *
     * <p>Falls back to the shared figures above for anything that has not asked for more, which is every
     * titan small enough for them to mean the same thing.
     */
    public static boolean isBuildableFor(@Nonnull final ChunkStore chunkStore,
                                         final int blockX,
                                         final int surfaceY,
                                         final int blockZ,
                                         @Nullable final String variantId) {

        return groundFor(chunkStore, blockX, surfaceY, blockZ, variantId).ok();
    }

    /** {@link #isBuildableFor}, but reporting which check decided it. For {@code /titan sites}. */
    @Nonnull
    public static TitanTerrainProbe.Ground groundFor(@Nonnull final ChunkStore chunkStore,
                                                     final int blockX,
                                                     final int surfaceY,
                                                     final int blockZ,
                                                     @Nullable final String variantId) {

        return TitanTerrainProbe.probe(chunkStore, blockX, surfaceY, blockZ,
            footprintRadius(variantId), footprintRelief(variantId), headroom(variantId));
    }

    /**
     * The Y a variant's body should sit over: the lowest ground in the footprint for something on legs long
     * enough to want that, the middle of it otherwise.
     *
     * @see TitanVariantAsset#isSpawnLevelToLowest
     */
    public static int standingY(@Nonnull final TitanTerrainProbe.Ground ground, @Nullable final String variantId) {
        final TitanVariantAsset variant = variantId == null ? null : TitanVariantAsset.find(variantId);
        return variant != null && variant.isSpawnLevelToLowest() ? ground.lowestY() : ground.groundY();
    }

    public static int footprintRadius(@Nullable final String variantId) {
        final TitanVariantAsset variant = variantId == null ? null : TitanVariantAsset.find(variantId);
        return variant != null && variant.getSpawnFootprintRadius() > 0
            ? variant.getSpawnFootprintRadius() : FOOTPRINT_RADIUS;
    }

    public static int footprintRelief(@Nullable final String variantId) {
        final TitanVariantAsset variant = variantId == null ? null : TitanVariantAsset.find(variantId);
        return variant != null && variant.getSpawnFootprintRelief() > 0
            ? variant.getSpawnFootprintRelief() : FOOTPRINT_RELIEF;
    }

    public static int headroom(@Nullable final String variantId) {
        final TitanVariantAsset variant = variantId == null ? null : TitanVariantAsset.find(variantId);
        return variant != null && variant.getSpawnHeadroom() > 0
            ? variant.getSpawnHeadroom() : HEADROOM;
    }

    /**
     * Runs one candidate through the gates, cheapest first: terrain has to be loaded before it can be
     * identified, identified before the rarity roll means anything, and only a cell that has cleared all of
     * that is worth measuring for flatness.
     *
     * @return whether a build was queued
     */
    private static boolean trySpawn(@Nonnull final Store<EntityStore> store,
                                    @Nonnull final World world,
                                    @Nonnull final WorldSites sites,
                                    @Nonnull final ChunkStore chunkStore,
                                    final long worldSeed,
                                    @Nonnull final Candidate candidate) {

        final int blockX = (int) Math.floor(candidate.x());
        final int blockZ = (int) Math.floor(candidate.z());
        final long key = candidate.key();

        final int surfaceY = TitanTerrainProbe.surfaceY(chunkStore, blockX, blockZ);
        if (surfaceY == TitanTerrainProbe.NO_SURFACE) return false;

        final String environment = TitanTerrainProbe.environmentAt(chunkStore, blockX, surfaceY, blockZ);
        final TitanSpawnRuleAsset rule = TitanSpawnRuleAsset.findForEnvironment(environment);
        if (rule == null) {
            // Terrain never changes biome, so this answer holds for the rest of the session.
            if (environment != null) sites.barren.add(key);
            return false;
        }

        if (candidate.occupancyRoll() >= rule.getChance()) {
            sites.barren.add(key);
            return false;
        }

        // Picked before the ground is measured, because how much ground has to be measured depends on
        // which titan it is. The roll is the cell's, so this is the same variant either way round.
        final String variantId = rule.pickVariant(candidate.variantRoll());
        if (variantId == null) {
            // Nothing this rule is allowed to spawn. Either it was authored without any variants, which is a
            // mistake worth pointing out, or the owner has turned all of them off, which is the config
            // working as intended and should stay quiet.
            if (rule.getVariants().length == 0) {
                LOGGER.at(Level.WARNING).log("Titan spawn rule '%s' declares no variants", rule.getId());
            }
            sites.barren.add(key);
            return false;
        }

        final TitanTerrainProbe.Ground ground = groundFor(chunkStore, blockX, surfaceY, blockZ, variantId);
        if (!ground.ok()) {
            // Deliberately not marked barren: players reshape terrain, and a hillside that is too steep
            // today may be flattened tomorrow.
            return false;
        }

        // The probe's ground rather than the heightmap, which counts trees: siting off the raw surface is
        // what would stand a titan on a canopy, since there is open air above one for the headroom check.
        final var position = new Vector3d(blockX + 0.5, standingY(ground, variantId) + 1.0, blockZ + 0.5);
        final float yaw = candidate.yaw();
        // Seeding the build from the cell is what makes the rebuilt titan the same titan: same ore count,
        // same nodes, in the same places, every time this site is visited.
        final long buildSeed = worldSeed ^ key;

        sites.pending.add(key);
        world.execute(() -> {
            sites.pending.remove(key);

            final TitanSpawner.Result result = TitanSpawner.spawn(
                store, variantId, position, yaw, ColliderMode.DEFAULT, buildSeed);
            if (!result.ok()) {
                LOGGER.at(Level.WARNING).log("Failed to place titan '%s' at %s: %s", variantId, position, result.error());
                return;
            }

            final var titan = store.getComponent(result.root(), TitanComponent.getComponentType());
            if (titan != null) titan.setSiteCell(key);

            sites.active.put(key, result.root());
            LOGGER.at(Level.INFO).log("Placed titan '%s' in %s at %s (%d parts)",
                variantId, environment, position, result.parts());
        });
        return true;
    }

    /** Whether anyone is close enough that the titan's ground is still being simulated for them. */
    private static boolean isWatched(@Nonnull final List<Watcher> watchers, @Nonnull final Vector3d position) {
        for (final Watcher watcher : watchers) {
            if (watcher.position().distance(position) <= watcher.keepRadius()) return true;
        }
        return false;
    }

    /**
     * Forgets the cells written off as barren, so cells ruled out under the old rules get another look.
     *
     * <p>Live titans are left alone: they are already standing in the world and dropping our handle on them
     * would strand them there untracked.
     */
    public void onRulesReloaded() {
        for (final WorldSites sites : worlds.values()) {
            sites.barren.clear();
        }
    }
}
