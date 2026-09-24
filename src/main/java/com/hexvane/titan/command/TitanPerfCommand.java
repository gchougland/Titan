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
 * <p>Development aid reporting transform traffic, memory, and server visibility.
 * Server visibility and sent counts distinguish tracker exclusions from client-only rendering issues.
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

        // Read only: never force a collection or change the player's heap settings.
        var heap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long used = heap.getUsed()/1048576, reserved = heap.getCommitted()/1048576, limit = heap.getMax()/1048576;
        int[] counts = new int[3];
        var viewer = store.getComponent(ref, com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems.EntityViewer.getComponentType());
        var playerTransform = store.getComponent(ref, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
        var visibility = new java.util.HashMap<Ref<EntityStore>, Visibility>();
        store.forEachChunk(com.hypixel.hytale.component.query.Query.or(
            com.hexvane.titan.entity.TitanComponent.getComponentType(), com.hexvane.titan.entity.TitanPartComponent.getComponentType()), (chunk, buffer) -> {
            for (int i=0; i<chunk.size(); i++) {
                if (chunk.getComponent(i, com.hexvane.titan.entity.TitanComponent.getComponentType()) != null) counts[0]++;
                var part = chunk.getComponent(i, com.hexvane.titan.entity.TitanPartComponent.getComponentType());
                if (part != null) counts[part.isDetached() ? 2 : 1]++;
                if (part == null || part.isDetached() || viewer == null || playerTransform == null || part.getOwner() == null) continue;
                var target = chunk.getComponent(i, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
                if (target == null) continue;
                var sample = visibility.computeIfAbsent(part.getOwner(), ignored -> new Visibility());
                double distance = target.getPosition().distanceSquared(playerTransform.getPosition());
                sample.parts++;
                sample.nearest = Math.min(sample.nearest, Math.sqrt(distance));
                sample.farthest = Math.max(sample.farthest, Math.sqrt(distance));
                var targetRef = chunk.getReferenceTo(i);
                if (viewer.visible.contains(targetRef)) sample.visible++;
                if (viewer.sent.containsKey(targetRef)) sample.sent++;
                if (part.isCollisionOnly()) sample.collisionOnly++;
                else if (chunk.getComponent(i, com.hypixel.hytale.server.core.modules.entity.component.ModelComponent.getComponentType()) != null) sample.models++;
                var box = chunk.getComponent(i, com.hypixel.hytale.server.core.modules.entity.component.BoundingBox.getComponentType());
                if (distance <= (double)viewer.viewRadiusBlocks*viewer.viewRadiusBlocks && (box == null || box.getBoundingBox().getMaximumThickness()
                    >= com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO*distance)) sample.inRange++;
            }
        });
        context.sendMessage(Message.translation("titan_commands.commands.titan.perf.memory")
            .param("used", used).param("reserved", reserved).param("limit", limit));
        context.sendMessage(Message.translation("titan_commands.commands.titan.perf.entities")
            .param("titans", counts[0]).param("pieces", counts[1]).param("debris", counts[2]));
        LOGGER.at(Level.INFO).log("titan perf memory: Java heap used=%d MiB committed=%d MiB max=%d MiB; world=%s titans=%d parts=%d debris=%d",
            used, reserved, limit, world.getName(), counts[0], counts[1], counts[2]);
        for (var entry : visibility.entrySet()) {
            var owner = entry.getKey();
            var titan = owner.isValid() ? store.getComponent(owner, com.hexvane.titan.entity.TitanComponent.getComponentType()) : null;
            var sample = entry.getValue();
            LOGGER.at(Level.INFO).log("titan perf visibility: variant=%s parts=%d visible=%d sent=%d inServerRange=%d entityModels=%d collisionOnly=%d nearest=%.1f farthest=%.1f blocks; viewRadius=%d lodRatio=%.8f",
                titan == null ? "removed" : titan.getVariantId(), sample.parts,sample.visible,sample.sent,sample.inRange,sample.models,sample.collisionOnly,
                sample.nearest,sample.farthest,viewer.viewRadiusBlocks,
                com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO);
        }

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

    private static final class Visibility {
        int parts, visible, sent, inRange, models, collisionOnly;
        double nearest = Double.POSITIVE_INFINITY, farthest;
    }
}
