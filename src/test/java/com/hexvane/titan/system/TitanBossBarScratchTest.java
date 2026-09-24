package com.hexvane.titan.system;

import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TitanBossBarScratchTest {
    @Test void simultaneousWorldTicksCannotClearEachOthersSnapshots() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> {
                var scratch = TitanBossBarSystem.SCRATCH.get();
                scratch.nodes.add(null);
                scratch.engaged.add(null);
                scratch.healths[0] = 42;
                barrier.await(5, TimeUnit.SECONDS);
                barrier.await(5, TimeUnit.SECONDS);
                assertEquals(1, scratch.nodes.size());
                assertEquals(1, scratch.engaged.size());
                assertEquals(42, scratch.healths[0]);
                return scratch;
            });
            var second = workers.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                var scratch = TitanBossBarSystem.SCRATCH.get();
                scratch.nodes.clear(); scratch.engaged.clear(); scratch.healths[0] = 7;
                barrier.await(5, TimeUnit.SECONDS);
                return scratch;
            });
            assertNotSame(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }
}
