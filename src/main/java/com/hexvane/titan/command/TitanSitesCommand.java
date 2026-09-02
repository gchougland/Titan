package com.hexvane.titan.command;

import com.hexvane.titan.asset.TitanSpawnRuleAsset;
import com.hexvane.titan.asset.TitanVariantAsset;
import com.hexvane.titan.bootstrap.TitanBootstrap;
import com.hexvane.titan.spawn.TitanSite;
import com.hexvane.titan.spawn.TitanSiteMemory;
import com.hexvane.titan.spawn.TitanTerrainProbe;
import com.hexvane.titan.system.TitanWorldSpawnSystem;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * {@code /titan sites [--cells=n] [--variant=id]}: reports what world generation has decided about the
 * ground around the caller.
 *
 * <p>Development aid for natural spawns, which are otherwise hard to reason about: a titan that never
 * appears could be unlucky rolls, the wrong biome, terrain too rough to stand on, a site somebody already
 * cleared, or a chunk that was never loaded, and from inside the game those look identical. Cells are
 * reported one per line in the same order the spawner checks them. Naming a variant narrows the listing to
 * the cells holding that one, and still counts the cells it could not read, so a search that found nothing
 * can be told apart from one that could not see far enough.
 *
 * <p>Every scan also goes to the server log, in more detail than chat carries: the rolls behind each
 * decision, the measurements behind each terrain rejection, and a tally of reasons at the end.
 */
public final class TitanSitesCommand extends AbstractPlayerCommand {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Largest half-width of a scan, in cells, so one command cannot ask for unbounded work. At the cap the
     * scan covers 17 by 17 cells.
     */
    private static final int MAX_CELL_REACH = 8;

    @Nonnull
    private static final SingleArgumentType<String> VARIANT =
        TitanCommandUtil.suggesting(TitanCommandUtil::enabledVariants);

    /** How many cells out from the caller to scan. The world is cut into cells holding one titan at most. */
    @Nonnull
    private final DefaultArg<Integer> cellsArg = withDefaultArg(
        "cells", "titan_commands.commands.titan.sites.cells.desc", ArgTypes.INTEGER, 2, "2");
    @Nonnull
    private final OptionalArg<String> variantArg =
        withOptionalArg("variant", "titan_commands.commands.titan.sites.variant.desc", VARIANT);

    public TitanSitesCommand() {
        super("sites", "titan_commands.commands.titan.sites.desc");
    }

    @Override
    protected void execute(@Nonnull final CommandContext context,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final Ref<EntityStore> ref,
                           @Nonnull final PlayerRef playerRef,
                           @Nonnull final World world) {

        @Nullable final String filter = variantArg.provided(context) ? variantArg.get(context) : null;
        if (filter != null && TitanVariantAsset.find(filter) == null) {
            context.sendMessage(Message.translation("titan_commands.commands.titan.unknownVariant")
                .param("variant", filter)
                .param("known", String.join(", ", TitanVariantAsset.ASSET_MAP.getAssetMap().keySet())));
            return;
        }

        final var transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return;

        final var memoryType = TitanBootstrap.getSiteMemoryType();
        @Nullable final TitanSiteMemory memory = memoryType == null ? null : store.getResource(memoryType);

        final var scan = new Scan(world.getChunkStore(), memory, world.getWorldConfig().getSeed(),
            transform.getPosition(), filter);
        final int reach = Math.max(0, Math.min(MAX_CELL_REACH, cellsArg.get(context)));

        scan.logHeader(world, reach, TitanWorldSpawnSystem.simulatedRadius(store, ref));
        scan.run(reach);
        scan.logSummary();
        scan.report(context);
    }

    /**
     * One run of the scan: the world it reads from, and the counters and chat lines it builds up.
     *
     * <p>Exists so each cell can be examined by a method of its own rather than inside a loop body that
     * has to thread half a dozen running totals through it.
     */
    private static final class Scan {

        @Nonnull
        private final ChunkStore chunkStore;
        @Nullable
        private final TitanSiteMemory memory;
        private final long seed;
        @Nonnull
        private final Vector3d origin;
        /** Variant the caller asked about, or {@code null} to report every cell. */
        @Nullable
        private final String filter;

        @Nonnull
        private final List<Message> lines = new ArrayList<>();
        @Nonnull
        private final Map<String, Integer> tally = new LinkedHashMap<>();
        @Nonnull
        private final TitanSite.Roll roll = new TitanSite.Roll();

        private int checked;
        private int ready;
        private int sited;
        private int unreadable;

        private Scan(@Nonnull final ChunkStore chunkStore,
                     @Nullable final TitanSiteMemory memory,
                     final long seed,
                     @Nonnull final Vector3d origin,
                     @Nullable final String filter) {
            this.chunkStore = chunkStore;
            this.memory = memory;
            this.seed = seed;
            this.origin = origin;
            this.filter = filter;
        }

        /** Walks the square of cells centred on the caller, in the same order the spawner checks them. */
        private void run(final int reach) {
            final int centreX = TitanSite.cellOf(origin.x);
            final int centreZ = TitanSite.cellOf(origin.z);

            for (int cellX = centreX - reach; cellX <= centreX + reach; cellX++) {
                for (int cellZ = centreZ - reach; cellZ <= centreZ + reach; cellZ++) {
                    examine(cellX, cellZ);
                }
            }
        }

        /** Runs one cell through the same gates the spawner applies, recording why it stopped. */
        private void examine(final int cellX, final int cellZ) {
            TitanSite.roll(seed, cellX, cellZ, roll);
            checked++;

            final int blockX = (int) Math.floor(roll.x());
            final int blockZ = (int) Math.floor(roll.z());
            final double distance = Math.hypot(roll.x() - origin.x, roll.z() - origin.z);
            final long key = TitanSite.cellKey(cellX, cellZ);
            final String where = String.format("cell (%d,%d) site (%d,%d) d=%.0f", cellX, cellZ, blockX, blockZ, distance);

            final int surfaceY = TitanTerrainProbe.surfaceY(chunkStore, blockX, blockZ);
            if (surfaceY == TitanTerrainProbe.NO_SURFACE) {
                // Without the ground there is no environment, so no rule, so no way to know what this cell
                // would hold. The one outcome a filtered search cannot conclude anything from.
                unreadable++;
                count("chunk not loaded");
                // The rolls are pure functions of the seed, so they still say whether the seed placed
                // anything here even for a cell whose biome cannot be read.
                LOGGER.at(Level.INFO).log("  %s -> chunk not loaded (seed rolls stand regardless: "
                    + "occupancy=%.3f, variant=%.3f)", where, roll.occupancy(), roll.variant());
                if (filter == null) {
                    lines.add(entry(blockX, blockZ, distance, "chunk not loaded, walk closer", "-", "-"));
                }
                return;
            }

            final String environment = TitanTerrainProbe.environmentAt(chunkStore, blockX, surfaceY, blockZ);
            final TitanSpawnRuleAsset rule = TitanSpawnRuleAsset.findForEnvironment(environment);
            if (rule == null) {
                count("no rule for " + environment);
                LOGGER.at(Level.INFO).log("  %s surfaceY=%d env=%s -> no rule claims this environment",
                    where, surfaceY, environment);
                if (filter == null) {
                    lines.add(entry(blockX, blockZ, distance, "no titans in this biome", String.valueOf(environment), "-"));
                }
                return;
            }

            if (roll.occupancy() >= rule.getChance()) {
                count("seed left bare");
                LOGGER.at(Level.INFO).log("  %s surfaceY=%d env=%s rule=%s occupancy=%.3f >= chance=%.3f -> bare",
                    where, surfaceY, environment, rule.getId(), roll.occupancy(), rule.getChance());
                if (filter == null) {
                    lines.add(entry(blockX, blockZ, distance, "seed left this cell bare", environment, "-"));
                }
                return;
            }

            final String picked = rule.pickVariant(roll.variant());
            if (picked == null) {
                count("all variants disabled");
                LOGGER.at(Level.INFO).log("  %s surfaceY=%d env=%s rule=%s -> every variant disabled in config",
                    where, surfaceY, environment, rule.getId());
                if (filter == null) {
                    lines.add(entry(blockX, blockZ, distance, "every variant here is disabled in config.json", environment, "-"));
                }
                return;
            }

            // Past here the cell holds a titan and which one is known. Skipping non-matching cells scopes
            // the counters below to the filter, not just the listing.
            if (filter != null && !filter.equals(picked)) {
                probeHypothetical(where, blockX, blockZ, surfaceY, environment, rule, picked);
                return;
            }

            sited++;

            if (memory != null && memory.isCleared(key)) {
                count("killed, on cooldown");
                LOGGER.at(Level.INFO).log("  %s surfaceY=%d env=%s rule=%s variant=%s -> already killed, on cooldown",
                    where, surfaceY, environment, rule.getId(), picked);
                lines.add(entry(blockX, blockZ, distance, "already killed, still on cooldown", environment, picked));
                return;
            }

            final var ground = TitanWorldSpawnSystem.groundFor(chunkStore, blockX, surfaceY, blockZ, picked);
            final String verdict = describe(ground, picked);
            // surfaceY counts trees and groundY does not, so a gap between the two marks a wooded column
            // where a relief measurement would be reading the canopy. standY is where the body goes: the
            // lowest corner, for the long-legged variants.
            LOGGER.at(Level.INFO).log("  %s surfaceY=%d groundY=%d standY=%d env=%s rule=%s occupancy=%.3f < chance=%.3f "
                    + "variantRoll=%.3f variant=%s ground=%s(relief=%d, radius=%d, maxRelief=%d, headroom=%d) -> %s",
                where, surfaceY, ground.groundY(), TitanWorldSpawnSystem.standingY(ground, picked),
                environment, rule.getId(), roll.occupancy(), rule.getChance(),
                roll.variant(), picked, ground.verdict(), ground.relief(),
                TitanWorldSpawnSystem.footprintRadius(picked),
                TitanWorldSpawnSystem.footprintRelief(picked),
                TitanWorldSpawnSystem.headroom(picked), verdict);

            if (!ground.ok()) {
                count("ground rejected: " + ground.verdict());
                lines.add(entry(blockX, blockZ, distance, verdict, environment, picked));
                return;
            }

            ready++;
            count("ready");
            lines.add(entry(blockX, blockZ, distance, verdict, environment, picked));
        }

        /**
         * Probes a cell for the filtered variant even though it rolled something else.
         *
         * <p>A rare variant may never be picked inside a scan, which would otherwise leave no way to tell a
         * weight that is too low from a footprint that never fits anywhere.
         */
        private void probeHypothetical(@Nonnull final String where,
                                       final int blockX,
                                       final int blockZ,
                                       final int surfaceY,
                                       @Nonnull final String environment,
                                       @Nonnull final TitanSpawnRuleAsset rule,
                                       @Nonnull final String picked) {

            final var hypothetical = TitanWorldSpawnSystem.groundFor(chunkStore, blockX, surfaceY, blockZ, filter);
            LOGGER.at(Level.INFO).log("  %s surfaceY=%d env=%s rule=%s occupancy=%.3f < chance=%.3f "
                    + "variantRoll=%.3f -> %s, not the one asked about; had %s been rolled here: "
                    + "%s(relief=%d, radius=%d, maxRelief=%d)",
                where, surfaceY, environment, rule.getId(), roll.occupancy(), rule.getChance(),
                roll.variant(), picked, filter, hypothetical.verdict(), hypothetical.relief(),
                TitanWorldSpawnSystem.footprintRadius(filter),
                TitanWorldSpawnSystem.footprintRelief(filter));
            count(hypothetical.ok()
                ? "rolled another variant; " + filter + " would have fitted"
                : "rolled another variant; " + filter + " would have been rejected");
        }

        private void logHeader(@Nonnull final World world, final int reach, final double simulated) {
            LOGGER.at(Level.INFO).log(
                "/titan sites at (%.0f %.0f %.0f) in world '%s': %dx%d cells of %d blocks, seed %d, filter %s. "
                    + "Chunks are simulated %.0f blocks out, so cells further than that read as unloaded.",
                origin.x, origin.y, origin.z, world.getName(),
                reach * 2 + 1, reach * 2 + 1, TitanSite.CELL_BLOCKS, seed,
                filter == null ? "none" : filter, simulated);
        }

        private void logSummary() {
            final var summary = new StringBuilder();
            tally.forEach((reason, n) -> summary.append(summary.isEmpty() ? "" : ", ").append(reason).append(" x").append(n));
            LOGGER.at(Level.INFO).log("/titan sites summary: %d cells, %d unreadable, %d sited, %d ready. %s",
                checked, unreadable, sited, ready, summary);
        }

        /** Sends the per-cell lines and the closing totals to the caller. */
        private void report(@Nonnull final CommandContext context) {
            lines.forEach(context::sendMessage);

            if (filter == null) {
                context.sendMessage(Message.translation("titan_commands.commands.titan.sites.count")
                    .param("ready", ready)
                    .param("sited", sited)
                    .param("cells", checked)
                    .param("size", TitanSite.CELL_BLOCKS));
                return;
            }

            context.sendMessage(Message.translation("titan_commands.commands.titan.sites.filtered")
                .param("variant", filter)
                .param("ready", ready)
                .param("sited", sited)
                .param("cells", checked)
                .param("size", TitanSite.CELL_BLOCKS));

            // Reported separately, because the per-cell lines that would have shown it are the ones the
            // filter hides.
            if (unreadable > 0) {
                context.sendMessage(Message.translation("titan_commands.commands.titan.sites.unreadable")
                    .param("unreadable", unreadable));
            }
        }

        private void count(@Nonnull final String reason) {
            tally.merge(reason, 1, Integer::sum);
        }
    }

    /** @return the terrain verdict as one line, worded identically for chat and the log. */
    @Nonnull
    private static String describe(@Nonnull final TitanTerrainProbe.Ground ground, @Nonnull final String variant) {
        final int radius = TitanWorldSpawnSystem.footprintRadius(variant);
        return switch (ground.verdict()) {
            case OK -> "ready, will build shortly";
            case CORNER_UNLOADED -> "footprint corner not loaded; it needs " + radius + " blocks read each way";
            case TOO_STEEP -> "ground too rough: " + ground.relief() + " blocks of relief across "
                + radius * 2 + ", limit " + TitanWorldSpawnSystem.footprintRelief(variant);
            case SUBMERGED -> "under water";
            case OBSTRUCTED -> "blocked overhead at y" + ground.obstructionY() + "; it needs "
                + TitanWorldSpawnSystem.headroom(variant) + " clear";
        };
    }

    @Nonnull
    private static Message entry(final int x, final int z, final double distance,
                                 @Nonnull final String status,
                                 @Nonnull final String environment,
                                 @Nonnull final String variant) {
        return Message.translation("titan_commands.commands.titan.sites.entry")
            .param("x", x)
            .param("z", z)
            .param("distance", distance)
            .param("status", status)
            .param("environment", environment)
            .param("variant", variant);
    }
}
