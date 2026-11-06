package com.agv.fleet.headless;

import com.agv.fleet.core.AgvNode;
import com.agv.fleet.core.agent.AgvRobot;
import com.agv.fleet.core.agent.Task;
import com.agv.fleet.core.map.CellType;
import com.agv.fleet.core.map.GridMap;
import com.agv.fleet.core.map.GridPoint;
import com.agv.fleet.core.navigation.DynamicAStarPathfinder;
import com.agv.fleet.ui.metrics.FleetMetricsCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AutoDemoHeadlessTest {

    private final List<AgvNode> nodes = new ArrayList<>();
    private final List<AgvRobot> robots = new ArrayList<>();
    private ScheduledExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        for (AgvNode node : nodes) {
            try { node.stop(); } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Task 14: Headless Auto Demo with 5 AGVs creates & completes >= 2 tasks with 0 collisions")
    void testAutoDemoHeadlessSimulation() throws Exception {
        int multicastPort = ThreadLocalRandom.current().nextInt(20000, 30000);
        int baseTcpPort = ThreadLocalRandom.current().nextInt(31000, 38000);

        GridMap map = new GridMap(12, 12);
        // Intersections
        map.setCellType(5, 5, CellType.INTERSECTION);
        map.setCellType(5, 7, CellType.INTERSECTION);
        map.setCellType(7, 5, CellType.INTERSECTION);
        map.setCellType(7, 7, CellType.INTERSECTION);

        DynamicAStarPathfinder pathfinder = new DynamicAStarPathfinder();
        FleetMetricsCollector metricsCollector = new FleetMetricsCollector();

        GridPoint[] initialPositions = {
                new GridPoint(1, 1),
                new GridPoint(10, 1),
                new GridPoint(1, 10),
                new GridPoint(10, 10),
                new GridPoint(5, 1)
        };

        for (int i = 0; i < 5; i++) {
            long nodeId = i + 1L;
            AgvNode node = AgvNode.builder()
                    .nodeId(nodeId)
                    .tcpPort(baseTcpPort + i)
                    .multicastPort(multicastPort)
                    .heartbeatIntervalMs(200)
                    .heartbeatTimeoutMs(1500)
                    .initialPosition(initialPositions[i].toPosition2D())
                    .initialBattery(90.0)
                    .auctionTimeoutMs(300)
                    .mapDiagonal(16.97)
                    .allowSelfBidding(true)
                    .build();

            node.start();
            nodes.add(node);

            AgvRobot robot = new AgvRobot(node, map, pathfinder, initialPositions[i]);
            robot.setModelName("AGV-" + nodeId);
            robots.add(robot);
        }

        // Wait for full P2P mesh
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            for (AgvNode n : nodes) {
                assertThat(n.getActivePeerCount()).isEqualTo(4);
            }
        });

        // Track completed tasks and task announcement
        List<Task> generatedTasks = new CopyOnWriteArrayList<>();
        AtomicInteger taskCounter = new AtomicInteger(0);
        Random rnd = new Random(42);

        executor = Executors.newScheduledThreadPool(2);

        // Schedule task creation every 2.0 seconds
        executor.scheduleWithFixedDelay(() -> {
            int tId = taskCounter.incrementAndGet();
            if (tId > 4) return; // Limit generation

            // Choose random announcer among alive nodes
            int announcerIdx = rnd.nextInt(nodes.size());
            AgvNode announcer = nodes.get(announcerIdx);

            int px = 1 + rnd.nextInt(10);
            int py = 1 + rnd.nextInt(10);
            int dx = 1 + rnd.nextInt(10);
            int dy = 1 + rnd.nextInt(10);

            Task task = new Task("AUTO-" + tId, px, py, dx, dy);
            generatedTasks.add(task);
            announcer.announceTask(task);
        }, 200, 2000, TimeUnit.MILLISECONDS);

        // Run simulation physics loop (20 Hz = 50ms per tick) via ScheduledExecutorService (no Thread.sleep)
        executor.scheduleAtFixedRate(() -> {
            for (AgvRobot robot : robots) {
                robot.updatePhysicsAndLogic(null);
            }

            // Ground-Truth collision detector
            for (int i = 0; i < robots.size(); i++) {
                AgvRobot r1 = robots.get(i);
                for (int j = i + 1; j < robots.size(); j++) {
                    AgvRobot r2 = robots.get(j);
                    double dist = Math.hypot(r1.getRenderX() - r2.getRenderX(), r1.getRenderY() - r2.getRenderY());
                    if (dist < 0.65) {
                        metricsCollector.recordCollision();
                    }
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS);

        // Await at least 2 tasks completed
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            long totalCompleted = robots.stream().mapToLong(AgvRobot::getTotalTasksCompleted).sum();
            assertThat(totalCompleted)
                    .as("At least 2 tasks must be generated and completed")
                    .isGreaterThanOrEqualTo(2);
            assertThat(metricsCollector.getGroundTruthCollisions())
                    .as("Zero collisions allowed in Auto Demo")
                    .isEqualTo(0);
        });
    }
}
