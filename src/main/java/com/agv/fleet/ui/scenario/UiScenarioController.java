package com.agv.fleet.ui.scenario;

import com.agv.fleet.ui.metrics.FleetMetricsCollector;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Controller orchestrating interactive UI scenarios:
 * <ul>
 *   <li>S1: Head-on Intersection Mutex (2, 4, 8 AGVs)</li>
 *   <li>S2: Adaptive CNP Task Auction (5 AGVs with 90%, 60%, 40%, 20%, 10% battery)</li>
 *   <li>S3: Fault Tolerance & Crash Recovery (Hard Crash, Network Freeze, Mutex-held Crash)</li>
 * </ul>
 * Emits step banner descriptions and evaluates ground-truth PASS/FAIL verdicts.
 */
public class UiScenarioController {

    public static final long SCENARIO_TIMEOUT_MS = 30_000L;

    private final List<UiScenarioListener> listeners = new CopyOnWriteArrayList<>();
    private final FleetMetricsCollector metricsCollector;

    private volatile UiScenarioType activeScenario = null;
    private volatile boolean running = false;
    private volatile int s1VehicleCount = 2; // 2, 4, or 8
    private volatile int currentStep = 1;
    private volatile int totalSteps = 3;
    private volatile long scenarioStartTimeMs = 0;

    public UiScenarioController(FleetMetricsCollector metricsCollector) {
        this.metricsCollector = Objects.requireNonNull(metricsCollector, "metricsCollector cannot be null");
    }

    public void addListener(UiScenarioListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(UiScenarioListener listener) {
        listeners.remove(listener);
    }

    public void setS1VehicleCount(int count) {
        if (count == 2 || count == 4 || count == 8) {
            this.s1VehicleCount = count;
        }
    }

    public int getS1VehicleCount() {
        return s1VehicleCount;
    }

    public boolean isRunning() {
        return running;
    }

    public UiScenarioType getActiveScenario() {
        return activeScenario;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public int getTotalSteps() {
        return totalSteps;
    }

    public long getScenarioStartTimeMs() {
        return scenarioStartTimeMs;
    }

    public long getElapsedMs() {
        if (!running || scenarioStartTimeMs <= 0) return 0;
        return System.currentTimeMillis() - scenarioStartTimeMs;
    }

    public boolean isTimedOut() {
        return running && getElapsedMs() >= SCENARIO_TIMEOUT_MS;
    }

    public void startS1Scenario(int vehicleCount) {
        this.s1VehicleCount = vehicleCount;
        this.activeScenario = UiScenarioType.S1_HEAD_ON_INTERSECTION;
        this.running = true;
        this.currentStep = 1;
        this.totalSteps = 3;
        this.scenarioStartTimeMs = System.currentTimeMillis();

        metricsCollector.reset();

        String banner = String.format("Bước 1/3: Đặt lại bản đồ và thiết lập %d xe tại các lối vào ngã tư.", s1VehicleCount);
        notifyStarted(UiScenarioType.S1_HEAD_ON_INTERSECTION, banner);
    }

    public void updateS1Step2() {
        notifyStep(2, 3, String.format("Bước 2/3: Cho %d xe đồng thời tiến vào ngã tư. Thuật toán Ricart-Agrawala đang giải quyết xung đột...", s1VehicleCount));
    }

    public UiScenarioResult finishS1Scenario(boolean allReached, int collisions, double avgWaitMs) {
        this.running = false;
        boolean passed = allReached && collisions == 0;

        String summary = passed
                ? String.format("THÀNH CÔNG (PASS): Toàn bộ %d xe đã vượt ngã tư an toàn. 0 va chạm, thời gian chờ mutex TB: %.1f ms.", s1VehicleCount, avgWaitMs)
                : String.format("THẤT BẠI (FAIL): Phát hiện %d va chạm hoặc có xe bị kẹt.", collisions);

        Map<String, Object> extra = new HashMap<>();
        extra.put("vehicleCount", s1VehicleCount);
        extra.put("collisions", collisions);
        extra.put("avgWaitMs", avgWaitMs);

        UiScenarioResult result = new UiScenarioResult(
                UiScenarioType.S1_HEAD_ON_INTERSECTION,
                passed,
                summary,
                collisions,
                avgWaitMs,
                passed ? 1 : 0,
                extra
        );

        notifyCompleted(result);
        return result;
    }

    public void startS2Scenario() {
        this.activeScenario = UiScenarioType.S2_ADAPTIVE_AUCTION;
        this.running = true;
        this.currentStep = 1;
        this.totalSteps = 3;
        this.scenarioStartTimeMs = System.currentTimeMillis();

        metricsCollector.reset();

        String banner = "Bước 1/3: Khởi tạo 5 xe với các mức pin: AGV 1 (90%), AGV 2 (60%), AGV 3 (40%), AGV 4 (20%), AGV 5 (10%).";
        notifyStarted(UiScenarioType.S2_ADAPTIVE_AUCTION, banner);
    }

    public void updateS2Step2(String taskId) {
        notifyStep(2, 3, String.format("Bước 2/3: Phát thầu nhiệm vụ %s qua Contract Net Protocol. Các xe đang tính toán chi phí (Cost = Dist + w * (100 - Battery))...", taskId));
    }

    public void recordBid(long bidderId, double cost, double battery, boolean isWinner) {
        for (UiScenarioListener l : listeners) {
            try {
                l.onBidEvaluated(bidderId, cost, battery, isWinner);
            } catch (Exception ignored) {}
        }
    }

    public UiScenarioResult finishS2Scenario(long winnerNodeId, double winnerCost, boolean agv5Bid) {
        this.running = false;
        boolean passed = winnerNodeId > 0 && winnerNodeId != 5 && !agv5Bid;

        String summary = passed
                ? String.format("THÀNH CÔNG (PASS): AGV %d thắng thầu với điểm chi phí tối ưu %.2f. Xe pin yếu AGV 5 (10%% <= 15%%) từ chối nộp thầu đúng đặc tả.", winnerNodeId, winnerCost)
                : String.format("THẤT BẠI (FAIL): Lỗi đấu thầu (winner=%d, AGV5 đã nộp thầu=%b).", winnerNodeId, agv5Bid);

        Map<String, Object> extra = new HashMap<>();
        extra.put("winnerNodeId", winnerNodeId);
        extra.put("winnerCost", winnerCost);
        extra.put("agv5Rejected", !agv5Bid);

        UiScenarioResult result = new UiScenarioResult(
                UiScenarioType.S2_ADAPTIVE_AUCTION,
                passed,
                summary,
                0,
                0.0,
                passed ? 1 : 0,
                extra
        );

        notifyCompleted(result);
        return result;
    }

    public void startS3Scenario(String variantName) {
        this.activeScenario = UiScenarioType.S3_CRASH_RECOVERY;
        this.running = true;
        this.currentStep = 1;
        this.totalSteps = 2;
        this.scenarioStartTimeMs = System.currentTimeMillis();

        metricsCollector.reset();

        String banner = String.format("Bước 1/2: Kích hoạt biến thể sập node [%s]...", variantName);
        notifyStarted(UiScenarioType.S3_CRASH_RECOVERY, banner);
    }

    public UiScenarioResult finishS3Scenario(String variantName, boolean recoveredSuccessfully, long detectionTimeMs) {
        this.running = false;
        boolean passed = recoveredSuccessfully;

        String summary = passed
                ? String.format("THÀNH CÔNG (PASS): Đã phát hiện sự cố [%s] và tự phục hồi sau %d ms. Không mất mát nhiệm vụ/deadlock.", variantName, detectionTimeMs)
                : String.format("THẤT BẠI (FAIL): Hệ thống không phục hồi được sau sự cố [%s].", variantName);

        Map<String, Object> extra = new HashMap<>();
        extra.put("variant", variantName);
        extra.put("recoveryTimeMs", detectionTimeMs);

        UiScenarioResult result = new UiScenarioResult(
                UiScenarioType.S3_CRASH_RECOVERY,
                passed,
                summary,
                0,
                0.0,
                passed ? 1 : 0,
                extra
        );

        notifyCompleted(result);
        return result;
    }

    public UiScenarioResult finishWithResult(UiScenarioResult result) {
        this.running = false;
        notifyCompleted(result);
        return result;
    }

    public UiScenarioResult failWithTimeout(String blockedDetails) {
        this.running = false;
        String summary = String.format("THẤT BẠI (FAIL): Quá thời gian 30s tại bước %d/%d. %s",
                currentStep, totalSteps, blockedDetails != null ? blockedDetails : "");

        Map<String, Object> extra = new HashMap<>();
        extra.put("timeout", true);
        extra.put("step", currentStep);
        extra.put("totalSteps", totalSteps);

        UiScenarioResult result = new UiScenarioResult(
                activeScenario != null ? activeScenario : UiScenarioType.S1_HEAD_ON_INTERSECTION,
                false,
                summary,
                metricsCollector.getGroundTruthCollisions(),
                metricsCollector.getAverageMutexWaitTimeMs(),
                0,
                extra
        );
        notifyCompleted(result);
        return result;
    }

    public void cancelCurrentScenario() {
        this.running = false;
        this.activeScenario = null;
    }

    private void notifyStarted(UiScenarioType type, String banner) {
        for (UiScenarioListener l : listeners) {
            try {
                l.onScenarioStarted(type, banner);
            } catch (Exception ignored) {}
        }
    }

    public void notifyStep(int step, int totalSteps, String banner) {
        this.currentStep = step;
        this.totalSteps = totalSteps;
        for (UiScenarioListener l : listeners) {
            try {
                l.onStepUpdated(step, totalSteps, banner);
            } catch (Exception ignored) {}
        }
    }

    private void notifyCompleted(UiScenarioResult result) {
        for (UiScenarioListener l : listeners) {
            try {
                l.onScenarioCompleted(result);
            } catch (Exception ignored) {}
        }
    }
}
