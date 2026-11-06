package com.agv.fleet.ui.simulation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class PhysicsLoopControllerTest {

    private PhysicsLoopController controller;

    @AfterEach
    void tearDown() {
        if (controller != null) {
            controller.stop();
        }
    }

    @Test
    @DisplayName("Calling restart 20 times consecutively maintains exactly one active ticker (approx 4 Hz at 1x)")
    void testRestart20TimesMaintainsSingleTicker() {
        AtomicInteger tickCounter = new AtomicInteger(0);
        controller = new PhysicsLoopController(tickCounter::incrementAndGet);

        // Call restart 20 times rapidly
        for (int i = 0; i < 20; i++) {
            controller.restartPhysicsLoop();
        }

        // Measure ticks over 1100 ms
        tickCounter.set(0);
        await().pollDelay(1100, TimeUnit.MILLISECONDS).until(() -> true);

        int ticks = tickCounter.get();
        // At 1.0x (250ms period), in 1.1s we expect ~4 ticks (between 3 and 5).
        // If 20 uncancelled tickers were running, ticks would be >= 60.
        assertThat(ticks).isBetween(3, 6);
    }

    @Test
    @DisplayName("Only discrete speeds {0.25, 0.5, 1.0, 2.0, 4.0} trigger a restart")
    void testDiscreteSpeedSnappingAndDebouncing() {
        AtomicInteger tickCounter = new AtomicInteger(0);
        controller = new PhysicsLoopController(tickCounter::incrementAndGet);
        controller.restartPhysicsLoop();

        assertThat(controller.getCurrentDiscreteSpeed()).isEqualTo(1.0);

        // Value close to 1.0 snaps to 1.0 -> should not restart
        boolean restarted = controller.updateSpeed(1.04);
        assertThat(restarted).isFalse();
        assertThat(controller.getCurrentDiscreteSpeed()).isEqualTo(1.0);

        // Value close to 2.0 snaps to 2.0 -> restarts
        restarted = controller.updateSpeed(1.85);
        assertThat(restarted).isTrue();
        assertThat(controller.getCurrentDiscreteSpeed()).isEqualTo(2.0);
        assertThat(controller.getCurrentPeriodMs()).isEqualTo(125);

        // Same snapped speed 2.0 again -> should not restart
        restarted = controller.updateSpeed(2.1);
        assertThat(restarted).isFalse();
    }

    @Test
    @DisplayName("Pausing suppresses tick execution")
    void testPauseSuppressesTicks() {
        AtomicInteger tickCounter = new AtomicInteger(0);
        controller = new PhysicsLoopController(tickCounter::incrementAndGet);
        controller.restartPhysicsLoop();
        controller.setPaused(true);

        tickCounter.set(0);
        await().pollDelay(600, TimeUnit.MILLISECONDS).until(() -> true);
        assertThat(tickCounter.get()).isEqualTo(0);

        controller.setPaused(false);
        await().atMost(1000, TimeUnit.MILLISECONDS).until(() -> tickCounter.get() > 0);
    }
}
