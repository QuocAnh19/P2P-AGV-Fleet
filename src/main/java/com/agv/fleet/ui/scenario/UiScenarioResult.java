package com.agv.fleet.ui.scenario;

import java.util.Map;

/**
 * Result data payload generated upon completion of a scenario.
 */
public record UiScenarioResult(
        UiScenarioType type,
        boolean passed,
        String summary,
        int collisionCount,
        double averageWaitTimeMs,
        int tasksSucceeded,
        Map<String, Object> extraMetrics
) {
}
