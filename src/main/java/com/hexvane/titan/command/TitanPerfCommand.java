package com.hexvane.titan.command;

import com.hexvane.titan.config.TitanConfig;
import com.hexvane.titan.system.TitanSyncStats;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * {@code /titan perf}
 *
 * <p>Reports how much of last tick went out to the clients as part transforms, which is the number that
 * decides whether a large titan flickers as it walks. Read it standing still, then while the titan moves,
 * then again after changing one of the tolerances it prints: the point of the command is that the effect of
 * a knob can be measured rather than guessed at. Every reading also goes to the server log on one line, so
 * a series of them can be pulled out and compared.
 *
 * <p>This is the server's half of the picture. The engine's own {@code /entity tracker <player>} is the
 * other half — its {@code visibleCount} says how many of those parts a client is actually being sent, and
 * a non-zero removed count while a titan is in plain view means the tracker is dropping parts out of the
 * visible set and re-adding them, which looks the same to a player as an update that never arrived.
 */
public final class TitanPerfCommand extends AbstractPlayerCommand {

    @Nonnull
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public TitanPerfCommand() {
        super("perf", "titan_commands.commands.titan.perf.desc");
    }

    @Override
    protected void execute(@Nonnull final CommandContext context,
                           @Nonnull final Store<EntityStore> store,
                           @Nonnull final Ref<EntityStore> ref,
                           @Nonnull final PlayerRef playerRef,
                           @Nonnull final World world) {

        final TitanSyncStats.Snapshot snapshot = TitanSyncStats.lastTick();
        if (snapshot.considered() == 0) {
            context.sendMessage(Message.translation("titan_commands.commands.titan.perf.idle"));
            LOGGER.at(Level.INFO).log("titan perf: no titan parts ticked last tick");
            return;
        }

        final TitanConfig config = TitanConfig.get();

        // Also to the console, on one line, because the interesting thing to do with these numbers is
        // compare a run of them against each other and chat is not somewhere you can copy them out of.
        LOGGER.at(Level.INFO).log(
            "titan perf: wrote %d/%d part transforms (%.1f KiB/tick, %.1f KiB/s per viewer pre-compression); "
                + "skipped %d = %d titan still, %d bone still, %d deadband, %d off-phase; "
                + "epsilon %.3f blocks, %.3f deg, interval %.3fs, parallel %s",
            snapshot.written(), snapshot.considered(),
            snapshot.bytes() / 1024.0, snapshot.bytesPerSecond() / 1024.0,
            snapshot.skipped(), snapshot.stillPose(), snapshot.stillBone(),
            snapshot.deadband(), snapshot.offPhase(),
            config.getPartSyncEpsilon(), Math.toDegrees(config.getPartSyncRotationEpsilon()),
            config.getPartSyncInterval(), config.isParallelPartSync());

        context.sendMessage(Message.translation("titan_commands.commands.titan.perf.volume")
            .param("written", snapshot.written())
            .param("considered", snapshot.considered())
            .param("kib", snapshot.bytes() / 1024.0)
            .param("kibs", snapshot.bytesPerSecond() / 1024.0));

        context.sendMessage(Message.translation("titan_commands.commands.titan.perf.skipped")
            .param("skipped", snapshot.skipped())
            .param("pose", snapshot.stillPose())
            .param("bone", snapshot.stillBone())
            .param("deadband", snapshot.deadband())
            .param("phase", snapshot.offPhase()));

        context.sendMessage(Message.translation("titan_commands.commands.titan.perf.tuning")
            .param("epsilon", (float) config.getPartSyncEpsilon())
            .param("rotation", (float) Math.toDegrees(config.getPartSyncRotationEpsilon()))
            .param("interval", (float) config.getPartSyncInterval()));
    }
}
