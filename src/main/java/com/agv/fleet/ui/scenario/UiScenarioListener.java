package com.agv.fleet.ui.scenario;

import java.util.Map;

/**
 * Event listener receiving step-by-step progress notifications from the Scenario Controller.
 */
public interface UiScenarioListener {
    void onScenarioStarted(UiScenarioType type, String banner);
    void onStepUpdated(int step, int totalSteps, String banner);
    void onScenarioCompleted(UiScenarioResult result);
    default void onBidEvaluated(long bidderId, double cost, double battery, boolean isWinner) {}
}
