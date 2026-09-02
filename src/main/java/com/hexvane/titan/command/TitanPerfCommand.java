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
 * <p>Development aid reporting how much of the last tick went out to clients as part transforms, the number
 * behind a large titan flickering as it walks. Each reading also goes to the server log on one line, so a
 * series taken while adjusting the tolerances it prints can be compared.
 *
 * <p>This only covers the server side. The engine's {@code /entity tracker <player>} covers the rest: its
 * {@code visibleCount} is how many of those parts a client is actually being sent, and a non-zero removed
 * count while a titan is in plain view means the tracker is dropping parts out of the visible set and
 * re-adding them, which a player sees as an update that never arrived.
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

        // Console copy, on one line: these numbers are read as a series and chat cannot be copied out of.
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
