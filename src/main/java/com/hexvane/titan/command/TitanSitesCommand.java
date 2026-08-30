package com.hexvane.titan.command;

import com.hexvane.titan.asset.TitanSpawnRuleAsset;
import com.hexvane.titan.bootstrap.TitanBootstrap;
import com.hexvane.titan.spawn.TitanSite;
import com.hexvane.titan.spawn.TitanSiteMemory;
import com.hexvane.titan.spawn.TitanTerrainProbe;
import com.hexvane.titan.system.TitanWorldSpawnSystem;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;

/**
 * {@code /titan sites [--cells=n]} — what world generation has decided about the ground around you.
 *
 * <p>Natural spawns are otherwise almost impossible to reason about: a titan that never appears could be
 * unlucky rolls, the wrong biome, terrain too rough to stand on, a site somebody already cleared, or a
 * chunk that was never loaded, and they all look identical from inside the game. This spells out which it
 * was, cell by cell, in the same order the spawner checks them.
 */
public final class TitanSitesCommand extends AbstractPlayerCommand {

    /** The world is cut into cells this wide and each holds at most one titan. */
    @Nonnull
    private final DefaultArg<Integer> cellsArg = withDefaultArg(
        "cells", "titan_commands.commands.titan.sites.cells.desc", ArgTypes.INTEGER, 2, "2");

    public TitanSitesCommand() {
        super("sites", "titan_commands.commands.titan.sites.desc");
    }

    @Override
    protected void execute(@Nonnull final CommandContext context,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final Ref<EntityStore> ref,
                           @Nonnull final PlayerRef playerRef,
                           @Nonnull final World world) {

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
        int ready = 0;
        int sited = 0;

        for (int cellX = centreX - reach; cellX <= centreX + reach; cellX++) {
            for (int cellZ = centreZ - reach; cellZ <= centreZ + reach; cellZ++) {
                TitanSite.roll(seed, cellX, cellZ, roll);

                final int blockX = (int) Math.floor(roll.x());
                final int blockZ = (int) Math.floor(roll.z());
                final double distance = Math.hypot(roll.x() - origin.x, roll.z() - origin.z);
                final long key = TitanSite.cellKey(cellX, cellZ);

                final int surfaceY = TitanTerrainProbe.surfaceY(chunkStore, blockX, blockZ);
                if (surfaceY == TitanTerrainProbe.NO_SURFACE) {
                    lines.add(entry(blockX, blockZ, distance, "chunk not loaded, walk closer", "-", "-"));
                    continue;
                }

                final String environment = TitanTerrainProbe.environmentAt(chunkStore, blockX, surfaceY, blockZ);
                final TitanSpawnRuleAsset rule = TitanSpawnRuleAsset.findForEnvironment(environment);
                if (rule == null) {
                    lines.add(entry(blockX, blockZ, distance, "no titans in this biome", String.valueOf(environment), "-"));
                    continue;
                }

                if (roll.occupancy() >= rule.getChance()) {
                    lines.add(entry(blockX, blockZ, distance, "seed left this cell bare", environment, "-"));
                    continue;
                }

                sited++;
                final String variant = String.valueOf(rule.pickVariant(roll.variant()));

                if (memory != null && memory.isCleared(key)) {
                    lines.add(entry(blockX, blockZ, distance, "already killed, still on cooldown", environment, variant));
                    continue;
                }

                if (!TitanTerrainProbe.isBuildable(chunkStore, blockX, surfaceY, blockZ,
                    TitanWorldSpawnSystem.FOOTPRINT_RADIUS,
                    TitanWorldSpawnSystem.FOOTPRINT_RELIEF,
                    TitanWorldSpawnSystem.HEADROOM)) {
                    lines.add(entry(blockX, blockZ, distance, "ground too rough to stand on", environment, variant));
                    continue;
                }

                ready++;
                lines.add(entry(blockX, blockZ, distance, "ready, will build shortly", environment, variant));
            }
        }

        lines.forEach(context::sendMessage);
        context.sendMessage(Message.translation("titan_commands.commands.titan.sites.count")
            .param("ready", ready)
            .param("sited", sited)
            .param("cells", lines.size())
            .param("size", TitanSite.CELL_BLOCKS));
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
