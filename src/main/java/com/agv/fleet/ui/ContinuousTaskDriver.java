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
 * Bộ phát việc liên tục cho chế độ demo.
 *
 * Cứ mỗi {@code periodMs} ms, nếu còn xe rảnh và chưa có phiên đấu thầu nào đang chờ,
 * bộ này tạo 1 đơn hàng ngẫu nhiên và cho một xe NGẪU NHIÊN làm người phát thầu (CNP).
 * Nhờ vậy đội xe luôn có việc: xe chạy -> giao hàng -> pin yếu thì tự về sạc -> quay lại làm việc.
 *
 * Bộ này chỉ gọi API công khai của AgvRobot/AgvNode, không đụng tới JavaFX. Việc cập nhật
 * giao diện đi qua các callback do lớp UI truyền vào.
 */
public final class ContinuousTaskDriver {

    private static final Logger log = LoggerFactory.getLogger(ContinuousTaskDriver.class);

    /** Một đơn đấu thầu quá lâu không có kết quả thì bỏ theo dõi để không tự làm kẹt bộ phát. */
    private static final long AUCTION_WATCHDOG_MS = 10_000;
    private static final int MIN_TRIP_MANHATTAN = 5;

    private final Supplier<List<AgvRobot>> robotsSupplier;
    private final Supplier<GridMap> mapSupplier;
    private final Consumer<Task> taskRegistrar;
    private final Consumer<AgvRobot> announcerListener;
    private final long periodMs;
    private final Random random;
    private final AtomicInteger taskCounter = new AtomicInteger(5000);

    /** Chỉ được truy cập từ thread của scheduler. */
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
     * @param robotsSupplier    trả về bản chụp danh sách xe hiện tại (nên là new ArrayList<>(robots))
     * @param mapSupplier       trả về bản đồ hiện tại
     * @param taskRegistrar     đăng ký đơn vào danh sách đơn của UI (nhớ bọc Platform.runLater)
     * @param announcerListener báo cho UI biết xe nào vừa phát thầu (để vẽ hiệu ứng), có thể là null
     * @param periodMs          chu kỳ kiểm tra, ví dụ 2500
     * @param seed              seed ngẫu nhiên để demo lặp lại được
     */
    public ContinuousTaskDriver(Supplier<List<AgvRobot>> robotsSupplier,
                                Supplier<GridMap> mapSupplier,
                                Consumer<Task> taskRegistrar,
                                Consumer<AgvRobot> announcerListener,
                                long periodMs,
                                long seed) {
        this.robotsSupplier = robotsSupplier;
        this.mapSupplier = mapSupplier;
        this.taskRegistrar = taskRegistrar;
        this.announcerListener = announcerListener;
        this.periodMs = Math.max(500, periodMs);
        this.random = new Random(seed);
    }

    public synchronized void start() {
        if (future != null && !future.isCancelled()) {
            return;
        }
        future = executor.scheduleWithFixedDelay(this::safeTick, periodMs, periodMs, TimeUnit.MILLISECONDS);
        log.info("ContinuousTaskDriver started (period={} ms)", periodMs);
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

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            // Không để scheduler chết vì một lỗi đơn lẻ.
            log.warn("ContinuousTaskDriver tick failed: {}", t.toString());
        }
    }

    private void tick() {
        if (!enabled) {
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
            } else if (pending && now - e.getValue() > AUCTION_WATCHDOG_MS) {
                log.warn("Task {} stuck in {} for >{} ms, dropped from tracking",
                        e.getKey().getTaskId(), st, AUCTION_WATCHDOG_MS);
                it.remove();
            } else if (pending) {
                waiting++;
            }
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
