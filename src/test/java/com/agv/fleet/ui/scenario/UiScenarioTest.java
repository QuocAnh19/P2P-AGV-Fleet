package com.agv.fleet.ui.scenario;

import com.agv.fleet.ui.metrics.FleetMetricsCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class UiScenarioTest {

    private FleetMetricsCollector metricsCollector;
    private UiScenarioController controller;

    @BeforeEach
    public void setUp() {
        metricsCollector = new FleetMetricsCollector();
        controller = new UiScenarioController(metricsCollector);
    }

    @Test
    public void testS1LifecyclePassAndFail() {
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicReference<UiScenarioResult> resultRef = new AtomicReference<>();

        controller.addListener(new UiScenarioListener() {
            @Override
            public void onScenarioStarted(UiScenarioType type, String banner) {
                assertEquals(UiScenarioType.S1_HEAD_ON_INTERSECTION, type);
                started.set(true);
            }

            @Override
            public void onStepUpdated(int step, int totalSteps, String banner) {
                assertEquals(2, step);
            }

            @Override
            public void onScenarioCompleted(UiScenarioResult result) {
                resultRef.set(result);
            }
        });

        // 1. Run S1 with 4 vehicles -> PASS
        controller.startS1Scenario(4);
        assertTrue(controller.isRunning());
        assertTrue(started.get());
        assertEquals(4, controller.getS1VehicleCount());

        controller.updateS1Step2();

        UiScenarioResult passResult = controller.finishS1Scenario(true, 0, 150.0);
        assertFalse(controller.isRunning());
        assertTrue(passResult.passed());
        assertEquals(0, passResult.collisionCount());
        assertEquals(150.0, passResult.averageWaitTimeMs(), 0.001);
        assertEquals(passResult, resultRef.get());

        // 2. Run S1 with collision -> FAIL
        controller.startS1Scenario(4);
        UiScenarioResult failResult = controller.finishS1Scenario(true, 1, 300.0);
        assertFalse(failResult.passed());
        assertEquals(1, failResult.collisionCount());
    }

    @Test
    public void testS2AuctionVerification() {
        AtomicBoolean bidLogged = new AtomicBoolean(false);
        controller.addListener(new UiScenarioListener() {
            @Override
            public void onScenarioStarted(UiScenarioType type, String banner) {}

            @Override
            public void onStepUpdated(int step, int totalSteps, String banner) {}

            @Override
            public void onScenarioCompleted(UiScenarioResult result) {}

            @Override
            public void onBidEvaluated(long bidderId, double cost, double battery, boolean isWinner) {
                bidLogged.set(true);
            }
        });

        controller.startS2Scenario();
        controller.updateS2Step2("TASK-101");
        controller.recordBid(1, 8.5, 90.0, true);
        assertTrue(bidLogged.get());

        // 4 vehicles: Node 3 wins, Node 2 (low battery) did not bid -> PASS
        UiScenarioResult passResult4 = controller.finishS2Scenario(3L, 8.5, false, 4);
        assertTrue(passResult4.passed());
        assertTrue(passResult4.summary().contains("PASS"));

        // If Node 2 (low battery) bid or won -> FAIL
        UiScenarioResult failResult4 = controller.finishS2Scenario(2L, 2.0, true, 4);
        assertFalse(failResult4.passed());

        // 2 vehicles: Node 2 wins -> PASS
        controller.startS2Scenario(2);
        UiScenarioResult passResult2 = controller.finishS2Scenario(2L, 7.2, false, 2);
        assertTrue(passResult2.passed());
        assertTrue(passResult2.summary().contains("PASS"));
    }

    @Test
    public void testS3CrashRecoveryVariants() {
        // Variant 1: Hard Crash
        controller.startS3Scenario("Hard Crash");
        assertTrue(controller.isRunning());

        UiScenarioResult result = controller.finishS3Scenario("Hard Crash", true, 420);
        assertFalse(controller.isRunning());
        assertTrue(result.passed());
        assertEquals(420L, result.extraMetrics().get("recoveryTimeMs"));

        // Variant 2: Crash Holding Mutex
        controller.startS3Scenario("Crash khi giữ ngã tư");
        assertTrue(controller.isRunning());
        UiScenarioResult mutexRes = controller.finishS3Scenario("Crash khi giữ ngã tư", true, 350);
        assertFalse(controller.isRunning());
        assertTrue(mutexRes.passed());
        assertTrue(mutexRes.summary().contains("PASS"));

        // Variant 3: Freeze Network
        controller.startS3Scenario("Freeze Network");
        UiScenarioResult freezeRes = controller.finishS3Scenario("Freeze Network", true, 1500);
        assertTrue(freezeRes.passed());
    }
}
