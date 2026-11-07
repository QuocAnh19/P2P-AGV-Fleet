package com.agv.fleet.ui.scenario;

import com.agv.fleet.ui.metrics.FleetMetricsCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class UiScenarioControllerTest {

    private FleetMetricsCollector metrics;
    private UiScenarioController controller;

    @BeforeEach
    void setUp() {
        metrics = new FleetMetricsCollector();
        controller = new UiScenarioController(metrics);
    }

    @Test
    @DisplayName("Stepper & Progress: steps are tracked and emitted to listeners")
    void testStepperTracking() {
        AtomicInteger reportedStep = new AtomicInteger(0);
        AtomicInteger reportedTotal = new AtomicInteger(0);

        controller.addListener(new UiScenarioListener() {
            @Override
            public void onScenarioStarted(UiScenarioType type, String banner) {}

            @Override
            public void onStepUpdated(int step, int totalSteps, String banner) {
                reportedStep.set(step);
                reportedTotal.set(totalSteps);
            }

            @Override
            public void onScenarioCompleted(UiScenarioResult result) {}
        });

        controller.startS1Scenario(2);
        assertThat(controller.getCurrentStep()).isEqualTo(1);
        assertThat(controller.getTotalSteps()).isEqualTo(3);
        assertThat(controller.isRunning()).isTrue();

        controller.updateS1Step2();
        assertThat(controller.getCurrentStep()).isEqualTo(2);
        assertThat(reportedStep.get()).isEqualTo(2);
        assertThat(reportedTotal.get()).isEqualTo(3);

        controller.notifyStep(3, 3, "Bước 3/3 hoàn tất");
        assertThat(controller.getCurrentStep()).isEqualTo(3);
        assertThat(reportedStep.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("Timeout logic: failWithTimeout generates valid FAIL verdict and marks running false")
    void testTimeoutVerdict() {
        controller.startS1Scenario(4);
        assertThat(controller.isRunning()).isTrue();

        UiScenarioResult res = controller.failWithTimeout("AGV#2 đang WAITING_MUTEX");
        assertThat(controller.isRunning()).isFalse();
        assertThat(res.passed()).isFalse();
        assertThat(res.summary()).contains("THẤT BẠI (FAIL)");
        assertThat(res.summary()).contains("30s");
        assertThat(res.summary()).contains("AGV#2 đang WAITING_MUTEX");
    }

    @Test
    @DisplayName("S1 completion: produces PASS result when allReached and zero collisions")
    void testS1SuccessfulCompletion() {
        controller.startS1Scenario(2);
        UiScenarioResult res = controller.finishS1Scenario(true, 0, 15.5);

        assertThat(controller.isRunning()).isFalse();
        assertThat(res.passed()).isTrue();
        assertThat(res.collisionCount()).isEqualTo(0);
        assertThat(res.averageWaitTimeMs()).isEqualTo(15.5);
        assertThat(res.summary()).contains("THÀNH CÔNG (PASS)");
    }
}
