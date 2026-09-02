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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * {@code /titan sites [--cells=n] [--variant=id]} — what world generation has decided about the ground
 * around you.
 *
 * <p>Natural spawns are otherwise almost impossible to reason about: a titan that never appears could be
 * unlucky rolls, the wrong biome, terrain too rough to stand on, a site somebody already cleared, or a
 * chunk that was never loaded, and they all look identical from inside the game. This spells out which it
 * was, cell by cell, in the same order the spawner checks them.
 *
 * <p>Naming a variant narrows it to the cells holding that one, which is the quick way to answer "is there
 * a temple anywhere near me" without reading every line. It still counts the cells it could not read, so a
 * search that found nothing can be told apart from one that could not see far enough to know.
 *
 * <p>Every scan also goes to the server log, in more detail than chat carries: the rolls behind each
 * decision, the measurements behind each terrain rejection, and a tally of reasons at the end. Chat has to
 * stay readable and cannot be copied out of; the log is what a "why is nothing spawning" question is
 * actually answered from.
 */
public final class TitanSitesCommand extends AbstractPlayerCommand {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    @Nonnull
    private static final SingleArgumentType<String> VARIANT =
        TitanCommandUtil.suggesting(TitanCommandUtil::enabledVariants);

    /** The world is cut into cells this wide and each holds at most one titan. */
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

        final var origin = transform.getPosition();
        final var chunkStore = world.getChunkStore();
        final long seed = world.getWorldConfig().getSeed();

        final var memoryType = TitanBootstrap.getSiteMemoryType();
        @Nullable final TitanSiteMemory memory = memoryType == null ? null : store.getResource(memoryType);

        final int reach = Math.max(0, Math.min(8, cellsArg.get(context)));
        final int centreX = TitanSite.cellOf(origin.x);
        final int centreZ = TitanSite.cellOf(origin.z);

        final var roll = new TitanSite.Roll();
        final var lines = new ArrayList<Message>();
        final var tally = new LinkedHashMap<String, Integer>();
        int checked = 0;
        int ready = 0;
        int sited = 0;
        int unreadable = 0;

        final double simulated = TitanWorldSpawnSystem.simulatedRadius(store, ref);
        LOGGER.at(Level.INFO).log(
            "/titan sites at (%.0f %.0f %.0f) in world '%s': %dx%d cells of %d blocks, seed %d, filter %s. "
                + "Chunks are simulated %.0f blocks out, so cells further than that read as unloaded.",
            origin.x, origin.y, origin.z, world.getName(),
            reach * 2 + 1, reach * 2 + 1, TitanSite.CELL_BLOCKS, seed,
            filter == null ? "none" : filter, simulated);

        for (int cellX = centreX - reach; cellX <= centreX + reach; cellX++) {
            for (int cellZ = centreZ - reach; cellZ <= centreZ + reach; cellZ++) {
                TitanSite.roll(seed, cellX, cellZ, roll);
                checked++;

                final int blockX = (int) Math.floor(roll.x());
                final int blockZ = (int) Math.floor(roll.z());
                final double distance = Math.hypot(roll.x() - origin.x, roll.z() - origin.z);
                final long key = TitanSite.cellKey(cellX, cellZ);
                final String where = String.format("cell (%d,%d) site (%d,%d) d=%.0f", cellX, cellZ, blockX, blockZ, distance);

                final int surfaceY = TitanTerrainProbe.surfaceY(chunkStore, blockX, blockZ);
                if (surfaceY == TitanTerrainProbe.NO_SURFACE) {
                    // The one outcome a filtered search cannot draw a conclusion from: without the ground
                    // there is no environment, so no rule, so no way to know what this cell would hold.
                    unreadable++;
                    count(tally, "chunk not loaded");
                    // The rolls are pure functions of the seed, so they are still worth printing: they
                    // answer "did the seed even put something here" for a cell whose biome cannot be read,
                    // which is the half of the question that does not need the terrain.
                    LOGGER.at(Level.INFO).log("  %s -> chunk not loaded (seed rolls stand regardless: "
                        + "occupancy=%.3f, variant=%.3f)", where, roll.occupancy(), roll.variant());
                    if (filter == null) {
                        lines.add(entry(blockX, blockZ, distance, "chunk not loaded, walk closer", "-", "-"));
                    }
                    continue;
                }

                final String environment = TitanTerrainProbe.environmentAt(chunkStore, blockX, surfaceY, blockZ);
                final TitanSpawnRuleAsset rule = TitanSpawnRuleAsset.findForEnvironment(environment);
                if (rule == null) {
                    count(tally, "no rule for " + environment);
                    LOGGER.at(Level.INFO).log("  %s surfaceY=%d env=%s -> no rule claims this environment",
                        where, surfaceY, environment);
                    if (filter == null) {
                        lines.add(entry(blockX, blockZ, distance, "no titans in this biome", String.valueOf(environment), "-"));
                    }
                    continue;
                }

                if (roll.occupancy() >= rule.getChance()) {
                    count(tally, "seed left bare");
                    LOGGER.at(Level.INFO).log("  %s surfaceY=%d env=%s rule=%s occupancy=%.3f >= chance=%.3f -> bare",
                        where, surfaceY, environment, rule.getId(), roll.occupancy(), rule.getChance());
                    if (filter == null) {
                        lines.add(entry(blockX, blockZ, distance, "seed left this cell bare", environment, "-"));
                    }
                    continue;
                }

                final String picked = rule.pickVariant(roll.variant());
                if (picked == null) {
                    count(tally, "all variants disabled");
                    LOGGER.at(Level.INFO).log("  %s surfaceY=%d env=%s rule=%s -> every variant disabled in config",
                        where, surfaceY, environment, rule.getId());
                    if (filter == null) {
                        lines.add(entry(blockX, blockZ, distance, "every variant here is disabled in config.json", environment, "-"));
                    }
                    continue;
                }

                // Past here the cell holds a titan and we know which. Skipping the rest when it is not the
                // one asked about is what scopes every counter below to the filter as well as the listing.
                if (filter != null && !filter.equals(picked)) {
                    // Probed for the filter anyway, even though this cell rolled something else. A variant
                    // rare enough may never once be picked inside a scan, which leaves no way to tell a
                    // weight that is too low from a footprint that never fits. This reports the terrain
                    // answer for the one asked about, so the two can be told apart from a single run.
                    final var hypothetical = TitanWorldSpawnSystem.groundFor(chunkStore, blockX, surfaceY, blockZ, filter);
                    LOGGER.at(Level.INFO).log("  %s surfaceY=%d env=%s rule=%s occupancy=%.3f < chance=%.3f "
                            + "variantRoll=%.3f -> %s, not the one asked about; had %s been rolled here: "
                            + "%s(relief=%d, radius=%d, maxRelief=%d)",
                        where, surfaceY, environment, rule.getId(), roll.occupancy(), rule.getChance(),
                        roll.variant(), picked, filter, hypothetical.verdict(), hypothetical.relief(),
                        TitanWorldSpawnSystem.footprintRadius(filter),
                        TitanWorldSpawnSystem.footprintRelief(filter));
                    count(tally, hypothetical.ok()
                        ? "rolled another variant; " + filter + " would have fitted"
                        : "rolled another variant; " + filter + " would have been rejected");
                    continue;
                }

                sited++;
                final String variant = picked;

                if (memory != null && memory.isCleared(key)) {
                    count(tally, "killed, on cooldown");
                    LOGGER.at(Level.INFO).log("  %s surfaceY=%d env=%s rule=%s variant=%s -> already killed, on cooldown",
                        where, surfaceY, environment, rule.getId(), variant);
                    lines.add(entry(blockX, blockZ, distance, "already killed, still on cooldown", environment, variant));
                    continue;
                }

                final var ground = TitanWorldSpawnSystem.groundFor(chunkStore, blockX, surfaceY, blockZ, variant);
                final String verdict = describe(ground, variant);
                // surfaceY counts trees and groundY does not, so a gap between the two is a wooded column
                // and tells you the canopy is what the old four-corner relief check was measuring. standY
                // is where the body actually goes, which is the lowest corner for the long-legged variants.
                LOGGER.at(Level.INFO).log("  %s surfaceY=%d groundY=%d standY=%d env=%s rule=%s occupancy=%.3f < chance=%.3f "
                        + "variantRoll=%.3f variant=%s ground=%s(relief=%d, radius=%d, maxRelief=%d, headroom=%d) -> %s",
                    where, surfaceY, ground.groundY(), TitanWorldSpawnSystem.standingY(ground, variant),
                    environment, rule.getId(), roll.occupancy(), rule.getChance(),
                    roll.variant(), variant, ground.verdict(), ground.relief(),
                    TitanWorldSpawnSystem.footprintRadius(variant),
                    TitanWorldSpawnSystem.footprintRelief(variant),
                    TitanWorldSpawnSystem.headroom(variant), verdict);

                if (!ground.ok()) {
                    count(tally, "ground rejected: " + ground.verdict());
                    lines.add(entry(blockX, blockZ, distance, verdict, environment, variant));
                    continue;
                }

                ready++;
                count(tally, "ready");
                lines.add(entry(blockX, blockZ, distance, verdict, environment, variant));
            }
        }

        final var summary = new StringBuilder();
        tally.forEach((reason, n) -> summary.append(summary.isEmpty() ? "" : ", ").append(reason).append(" x").append(n));
        LOGGER.at(Level.INFO).log("/titan sites summary: %d cells, %d unreadable, %d sited, %d ready. %s",
            checked, unreadable, sited, ready, summary);

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

        // Said separately, because the per-cell lines that would have shown it are the ones being hidden.
        if (unreadable > 0) {
            context.sendMessage(Message.translation("titan_commands.commands.titan.sites.unreadable")
                .param("unreadable", unreadable));
        }
    }

    private static void count(@Nonnull final Map<String, Integer> tally, @Nonnull final String reason) {
        tally.merge(reason, 1, Integer::sum);
    }

    /** The one line that says what the terrain check decided, in the same words for chat and log. */
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
