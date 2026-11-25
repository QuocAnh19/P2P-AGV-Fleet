package com.agv.fleet.ui;

import com.agv.fleet.core.agent.AgentState;
import com.agv.fleet.core.agent.AgvRobot;
import com.agv.fleet.core.agent.Task;
import com.agv.fleet.core.map.GridMap;
import com.agv.fleet.core.map.GridPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bộ phát việc cho chế độ demo / thử nghiệm phân tán (hỗ trợ cả chạy liên tục lẫn chạy 1 chu kỳ).
 *
 * - Chế độ 1 chu kỳ (Single Cycle Mode): Phát đúng 1 mục tiêu (Task) qua đấu thầu CNP,
 *   theo dõi xe thực hiện giao hàng đến khi HOÀN THÀNH (COMPLETED), sau đó tự động dừng lại
 *   và kích hoạt callback để người dùng có thể quan sát, phân tích gói tin P2P.
 * - Chế độ liên tục (Continuous Mode): Lặp lại phát thầu khi có xe rảnh để duy trì hoạt động đội xe.
 *
 * Bộ này chỉ gọi API công khai của AgvRobot/AgvNode, không đụng tới JavaFX. Việc cập nhật
 * giao diện đi qua các callback do lớp UI truyền vào.
 */
public final class ContinuousTaskDriver {

    private static final Logger log = LoggerFactory.getLogger(ContinuousTaskDriver.class);

    /** Một đơn đấu thầu quá lâu không có kết quả thì bỏ theo dõi để không tự làm kẹt bộ phát. */
    private static final long AUCTION_WATCHDOG_MS = 3_500;
    private static final int MIN_TRIP_MANHATTAN = 5;

    private final Supplier<List<AgvRobot>> robotsSupplier;
    private final Supplier<GridMap> mapSupplier;
    private final Consumer<Task> taskRegistrar;
    private final Consumer<AgvRobot> announcerListener;
    private final Consumer<Task> cycleCompleteListener;
    private final long periodMs;
    private final Random random;
    private final AtomicInteger taskCounter = new AtomicInteger(5000);
    private final AtomicInteger completedCycleCount = new AtomicInteger(0);

    /** Chỉ được truy cập từ thread của scheduler hoặc phương thức synchronized. */
    private final Map<Task, Long> inFlight = new HashMap<>();

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "auto-task-driver");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> future;
    private volatile boolean enabled = true;

    /**
     * Số chu kỳ còn lại cần chạy:
     * -1: Chạy liên tục không giới hạn (Continuous Mode)
     *  0: Đã hoàn tất hoặc đang dừng (không phát thêm task mới)
     * >0: Chạy đúng số chu kỳ chỉ định (ví dụ = 1: Single Cycle Mode)
     */
    private volatile int remainingCycles = -1;

    public ContinuousTaskDriver(Supplier<List<AgvRobot>> robotsSupplier,
                                Supplier<GridMap> mapSupplier,
                                Consumer<Task> taskRegistrar,
                                Consumer<AgvRobot> announcerListener,
                                long periodMs,
                                long seed) {
        this(robotsSupplier, mapSupplier, taskRegistrar, announcerListener, null, periodMs, seed);
    }

    public ContinuousTaskDriver(Supplier<List<AgvRobot>> robotsSupplier,
                                Supplier<GridMap> mapSupplier,
                                Consumer<Task> taskRegistrar,
                                Consumer<AgvRobot> announcerListener,
                                Consumer<Task> cycleCompleteListener,
                                long periodMs,
                                long seed) {
        this.robotsSupplier = robotsSupplier;
        this.mapSupplier = mapSupplier;
        this.taskRegistrar = taskRegistrar;
        this.announcerListener = announcerListener;
        this.cycleCompleteListener = cycleCompleteListener;
        this.periodMs = Math.max(500, periodMs);
        this.random = new Random(seed);
    }

    public synchronized void start() {
        if (future != null && !future.isCancelled()) {
            return;
        }
        future = executor.scheduleWithFixedDelay(this::safeTick, periodMs, periodMs, TimeUnit.MILLISECONDS);
        log.info("ContinuousTaskDriver started (period={} ms, remainingCycles={})", periodMs, remainingCycles);
    }

    public synchronized void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        executor.shutdownNow();
    }

    /** Bật/tắt phát việc mà không dừng scheduler. Tắt khi đang chạy kịch bản kiểm thử. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("ContinuousTaskDriver enabled={}", enabled);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setRemainingCycles(int cycles) {
        this.remainingCycles = cycles;
        if (cycles != 0) {
            this.enabled = true;
        }
        log.info("ContinuousTaskDriver setRemainingCycles={}", cycles);
    }

    public int getRemainingCycles() {
        return remainingCycles;
    }

    public void setContinuous(boolean continuous) {
        if (continuous) {
            this.remainingCycles = -1;
            this.enabled = true;
            executor.execute(this::safeTick);
        } else {
            this.remainingCycles = 0;
            this.enabled = false;
        }
        log.info("ContinuousTaskDriver setContinuous={}, enabled={}", continuous, enabled);
    }

    public boolean isContinuous() {
        return remainingCycles < 0;
    }

    public int getCompletedCycleCount() {
        return completedCycleCount.get();
    }

    /**
     * Kích hoạt chạy đúng 1 chu kỳ mục tiêu: phát 1 đơn hàng -> chờ hoàn thành -> dừng lại.
     */
    public synchronized void triggerSingleCycle() {
        this.remainingCycles = 1;
        this.enabled = true;
        log.info("ContinuousTaskDriver: Triggered single cycle mode (1 cycle). Executing tick now...");
        executor.execute(this::safeTick);
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            // Không để scheduler chết vì một lỗi đơn lẻ.
            log.warn("ContinuousTaskDriver tick failed: {}", t.toString());
        }
    }

    private synchronized void tick() {
        if (!enabled) {
            return;
        }

        long now = System.currentTimeMillis();
        int waiting = 0;
        var it = inFlight.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Task.Status st = e.getKey().getStatus();
            boolean finished = st == Task.Status.COMPLETED
                    || st == Task.Status.CANCELLED
                    || st == Task.Status.FAILED;
            boolean pending = st == Task.Status.UNASSIGNED || st == Task.Status.AUCTIONING;

            if (finished) {
                it.remove();
                if (st == Task.Status.COMPLETED) {
                    int total = completedCycleCount.incrementAndGet();
                    log.info("ContinuousTaskDriver: Task {} successfully completed! (Total completed cycles: {})",
                            e.getKey().getTaskId(), total);

                    if (remainingCycles > 0) {
                        remainingCycles--;
                        if (remainingCycles == 0) {
                            // Đã hoàn tất đủ số chu kỳ chỉ định (ví dụ 1 chu kỳ khởi đầu)
                            this.enabled = false;
                            log.info("ContinuousTaskDriver: Target cycle completed. Pausing auto dispatch for user analysis.");
                            if (cycleCompleteListener != null) {
                                try {
                                    cycleCompleteListener.accept(e.getKey());
                                } catch (Exception ex) {
                                    log.error("Error executing cycleCompleteListener", ex);
                                }
                            }
                        }
                    }
                }
            } else if (pending && now - e.getValue() > AUCTION_WATCHDOG_MS) {
                log.warn("Task {} stuck in {} for >{} ms, dropped from tracking",
                        e.getKey().getTaskId(), st, AUCTION_WATCHDOG_MS);
                it.remove();
            } else if (pending) {
                waiting++;
            }
        }

        // Nếu chu kỳ đã hết (0) hoặc đang dừng thì không phát thêm việc
        if (!enabled || remainingCycles == 0) {
            return;
        }

        // Trong chế độ 1 chu kỳ (remainingCycles > 0), nếu đang có đơn in-flight thì chờ đơn này hoàn tất
        if (remainingCycles > 0 && !inFlight.isEmpty()) {
            return;
        }

        List<AgvRobot> alive = new ArrayList<>();
        for (AgvRobot r : robotsSupplier.get()) {
            if (!r.isCrashed()) {
                alive.add(r);
            }
        }
        if (alive.size() < 2) {
            return; // cần ít nhất 1 người phát thầu và 1 người nhận việc
        }

        // Mỗi lúc chỉ cho 1 phiên đấu thầu mở, để xe rảnh không bị chia nhỏ giữa nhiều phiên.
        if (waiting > 0) {
            return;
        }
        if (inFlight.size() >= alive.size()) {
            return; // không dồn quá số xe
        }

        List<AgvRobot> idle = new ArrayList<>();
        for (AgvRobot r : alive) {
            if (r.getState() == AgentState.IDLE && r.getCurrentTask() == null) {
                idle.add(r);
            }
        }
        if (idle.isEmpty()) {
            return;
        }

        // Người phát thầu không tự nộp thầu cho đơn của mình, nên phải còn ít nhất 1 xe rảnh khác.
        List<AgvRobot> announcerCandidates = new ArrayList<>();
        for (AgvRobot a : alive) {
            long otherIdle = idle.stream().filter(r -> r.getNodeId() != a.getNodeId()).count();
            if (otherIdle >= 1) {
                announcerCandidates.add(a);
            }
        }
        if (announcerCandidates.isEmpty()) {
            return;
        }
        AgvRobot announcer = announcerCandidates.get(random.nextInt(announcerCandidates.size()));

        Task task = createRandomTask(mapSupplier.get());
        if (task == null) {
            return;
        }
        inFlight.put(task, now);
        taskRegistrar.accept(task);
        if (announcerListener != null) {
            announcerListener.accept(announcer);
        }
        log.info("AutoDemo: AGV {} announces {}", announcer.getNodeId(), task.getTaskId());
        announcer.getNode().announceTask(task);
    }

    /**
     * Phát thầu 1 nhiệm vụ ngẫu nhiên ngay lập tức (dùng khi người dùng chọn thủ công từ menu hoặc nút).
     */
    public synchronized Task announceRandomTask() {
        List<AgvRobot> alive = new ArrayList<>();
        for (AgvRobot r : robotsSupplier.get()) {
            if (!r.isCrashed()) {
                alive.add(r);
            }
        }
        if (alive.size() < 2) {
            log.warn("Cannot announce task: Need at least 2 alive AGVs, but found {}", alive.size());
            return null;
        }

        List<AgvRobot> idle = new ArrayList<>();
        for (AgvRobot r : alive) {
            if (r.getState() == AgentState.IDLE && r.getCurrentTask() == null) {
                idle.add(r);
            }
        }
        if (idle.isEmpty()) {
            log.warn("Cannot announce task: No idle AGVs available");
            return null;
        }

        List<AgvRobot> announcerCandidates = new ArrayList<>();
        for (AgvRobot a : alive) {
            long otherIdle = idle.stream().filter(r -> r.getNodeId() != a.getNodeId()).count();
            if (otherIdle >= 1) {
                announcerCandidates.add(a);
            }
        }
        AgvRobot announcer = announcerCandidates.isEmpty()
                ? alive.get(random.nextInt(alive.size()))
                : announcerCandidates.get(random.nextInt(announcerCandidates.size()));

        Task task = createRandomTask(mapSupplier.get());
        if (task == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        inFlight.put(task, now);
        taskRegistrar.accept(task);
        if (announcerListener != null) {
            announcerListener.accept(announcer);
        }
        log.info("ContinuousTaskDriver: AGV {} announces task {}", announcer.getNodeId(), task.getTaskId());
        announcer.getNode().announceTask(task);
        return task;
    }

    private Task createRandomTask(GridMap map) {
        List<GridPoint> points = new ArrayList<>(map.getWalkableEmptyPoints());
        if (points.size() < 2) {
            return null;
        }
        GridPoint pickup = points.get(random.nextInt(points.size()));
        GridPoint drop = null;
        for (int i = 0; i < 20; i++) {
            GridPoint cand = points.get(random.nextInt(points.size()));
            int manhattan = Math.abs(cand.x() - pickup.x()) + Math.abs(cand.y() - pickup.y());
            if (manhattan >= MIN_TRIP_MANHATTAN) {
                drop = cand;
                break;
            }
        }
        if (drop == null) {
            return null;
        }
        String id = "AUTO-" + taskCounter.getAndIncrement();
        return new Task(id, pickup.x(), pickup.y(), drop.x(), drop.y());
    }
}
