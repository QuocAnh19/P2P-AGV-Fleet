package com.agv.fleet.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRunnerSmokeTest {

    @Test
    @DisplayName("Scenario 3 Fault Tolerance Smoke Test: 3 variants pass")
    void testScenario3FaultTolerance() {
        ScenarioRunner runner = new ScenarioRunner();
        ScenarioRunner.S3Result result = runner.runScenario3();
        assertThat(result.passed()).isTrue();
        assertThat(result.markdownReport()).contains("Variant 3a", "Variant 3b", "Variant 3c", "0 tasks lost");
    }

    @Test
    @DisplayName("Scenario 2 Adaptive Auction Smoke Test: AGV 5 refuses to bid")
    void testScenario2AdaptiveAuction() {
        ScenarioRunner runner = new ScenarioRunner();
        ScenarioRunner.S2Result result = runner.runScenario2();
        assertThat(result.passed()).isTrue();
        assertThat(result.markdownReport()).contains("AGV 5", "<=15% battery refused to bid");
    }
}
