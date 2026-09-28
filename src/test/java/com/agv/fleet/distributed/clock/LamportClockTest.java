package com.agv.fleet.distributed.clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Lamport Logical Clock Unit Tests")
class LamportClockTest {

    @Test
    @DisplayName("Clock ticks sequentially on local events")
    void testTick() {
        LamportClock clock = new LamportClock();
        assertThat(clock.getTime()).isZero();

        assertThat(clock.tick()).isEqualTo(1L);
        assertThat(clock.tick()).isEqualTo(2L);
        assertThat(clock.getTime()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Clock updates correctly when receiving higher remote timestamp")
    void testUpdateWithHigherTimestamp() {
        LamportClock clock = new LamportClock(5L);

        // Remote timestamp 10 > 5 -> new value is max(5, 10) + 1 = 11
        long updated = clock.updateAndGet(10L);
        assertThat(updated).isEqualTo(11L);
        assertThat(clock.getTime()).isEqualTo(11L);
    }

    @Test
    @DisplayName("Clock advances when receiving lower remote timestamp")
    void testUpdateWithLowerTimestamp() {
        LamportClock clock = new LamportClock(20L);

        // Remote timestamp 5 < 20 -> new value is max(20, 5) + 1 = 21
        long updated = clock.updateAndGet(5L);
        assertThat(updated).isEqualTo(21L);
        assertThat(clock.getTime()).isEqualTo(21L);
    }
}
