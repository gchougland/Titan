package com.hexvane.titan.system;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts what {@link TitanPartSyncSystem} sent to the clients on the last tick.
 *
 * <p>A titan's cost is almost entirely replication rather than computation: every part whose transform is
 * rewritten becomes a {@code TransformUpdate} in that tick's {@code EntityUpdates} packet, and the engine
 * packs the whole lot into one packet with no splitting. A walking Roaming Temple can therefore push a few
 * hundred kilobytes down a single connection in one tick, which is what makes its parts flicker. Every
 * lever in the sync system exists to bring that number down, so the number is worth being able to read.
 *
 * <p>Counters are process-wide rather than per world or per titan. That is coarse, but it is what the
 * packet builder sees, and it keeps the accounting cheap enough to leave switched on: the adders are only
 * touched once per part and are striped across threads, which the sync system needs since it runs in
 * parallel. The one thing to know when reading them is that worlds tick independently, so with titans
 * standing in two worlds at once the totals are summed and the split between the two is arbitrary.
 */
public final class TitanSyncStats {

    /**
     * Wire cost of replicating one part's transform, in bytes.
     *
     * <p>{@code ModelTransform.MAX_SIZE} is 49 — a fixed block of three raw doubles for the position plus
     * two packed {@code Direction}s, with absent fields zero-filled rather than omitted, so there is no
     * cheaper case to account for. The rest is the {@code EntityUpdate} that carries it: a network id, a
     * type tag and the update's own framing. Approximate on purpose; it is here to turn a part count into a
     * number that can be held against a connection, not to predict a packet to the byte. Compression takes
     * roughly half of it back off again on the wire.
     */
    public static final int BYTES_PER_TRANSFORM = 63;

    @Nonnull
    private static final LongAdder CONSIDERED = new LongAdder();
    @Nonnull
    private static final LongAdder WRITTEN = new LongAdder();
    @Nonnull
    private static final LongAdder STILL_POSE = new LongAdder();
    @Nonnull
    private static final LongAdder STILL_BONE = new LongAdder();
    @Nonnull
    private static final LongAdder OFF_PHASE = new LongAdder();
    @Nonnull
    private static final LongAdder DEADBAND = new LongAdder();

    @Nonnull
    private static volatile Snapshot last = Snapshot.EMPTY;

    private TitanSyncStats() {
    }

    /** One tick's worth of accounting. */
    public record Snapshot(long considered, long written, long stillPose, long stillBone, long offPhase, long deadband) {

        @Nonnull
        public static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0);

        public long skipped() {
            return stillPose + stillBone + offPhase + deadband;
        }

        /** Bytes this tick's part updates added to the outgoing packet, per client that can see them. */
        public long bytes() {
            return written * BYTES_PER_TRANSFORM;
        }

        /** The same figure as a sustained rate at the server's 20 Hz tick, in bytes per second. */
        public long bytesPerSecond() {
            return bytes() * 20;
        }
    }

    @Nonnull
    public static Snapshot lastTick() {
        return last;
    }

    public static void countConsidered() {
        CONSIDERED.increment();
    }

    public static void countWritten() {
        WRITTEN.increment();
    }

    /** The owning titan did not re-pose at all this tick, so none of its parts moved. */
    public static void countStillPose() {
        STILL_POSE.increment();
    }

    /** The titan re-posed, but this part's own bone landed on the same world matrix as last tick. */
    public static void countStillBone() {
        STILL_BONE.increment();
    }

    /** This part's turn in the sync interval has not come round yet. */
    public static void countOffPhase() {
        OFF_PHASE.increment();
    }

    /** The part moved, but not far enough from what the clients were last told to be worth saying again. */
    public static void countDeadband() {
        DEADBAND.increment();
    }

    /**
     * Closes off the tick: publishes the counters as {@link #lastTick} and zeroes them.
     *
     * <p>Registered to run after {@link TitanPartSyncSystem} so it sees a whole tick. It is a separate
     * system rather than a hook in the sync system because that one is called per entity and has nowhere to
     * notice that a tick has ended.
     */
    public static final class Roll extends TickingSystem<EntityStore> {

        @Nonnull
        private final Set<Dependency<EntityStore>> dependencies =
            Set.of(new SystemDependency<>(Order.AFTER, TitanPartSyncSystem.class));

        @Nonnull
        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return dependencies;
        }

        @Override
        public void tick(final float dt, final int systemIndex, @Nonnull final Store<EntityStore> store) {
            final long considered = CONSIDERED.sumThenReset();
            last = considered == 0
                ? Snapshot.EMPTY
                : new Snapshot(
                    considered,
                    WRITTEN.sumThenReset(),
                    STILL_POSE.sumThenReset(),
                    STILL_BONE.sumThenReset(),
                    OFF_PHASE.sumThenReset(),
                    DEADBAND.sumThenReset()
                );
        }
    }
}
