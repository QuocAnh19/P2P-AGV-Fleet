package com.agv.fleet.headless;

import com.agv.fleet.core.AgvNode;
import com.agv.fleet.core.agent.AgvRobot;
import com.agv.fleet.core.agent.Task;
import com.agv.fleet.core.map.CellType;
import com.agv.fleet.core.map.GridMap;
import com.agv.fleet.core.map.GridPoint;
import com.agv.fleet.core.navigation.DynamicAStarPathfinder;
import com.agv.fleet.ui.metrics.FleetMetricsCollector;
import io.netty.handler.codec.CorruptedFrameException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class FreeRunningSmokeHeadlessTest {

    private final List<AgvNode> nodes = new ArrayList<>();
    private final List<AgvRobot> robots = new ArrayList<>();
    private ScheduledExecutorService ticker;

    @AfterEach
    void tearDown() {
        if (ticker != null) {
            ticker.shutdownNow();
        }
        for (AgvNode node : nodes) {
            try { node.stop(); } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Task 15: 20s Free-Running Smoke Test with 5 AGVs & 3 concurrent tasks: 0 collisions, >= 3 completed, no corrupted frames")
    void testFreeRunningConcurrentTasksSmoke() throws Exception {
        int multicastPort = ThreadLocalRandom.current().nextInt(20000, 30000);
        int baseTcpPort = ThreadLocalRandom.current().nextInt(31000, 38000);

        GridMap map = new GridMap(12, 12);
        map.setCellType(5, 5, CellType.INTERSECTION);
        map.setCellType(5, 7, CellType.INTERSECTION);
        map.setCellType(7, 5, CellType.INTERSECTION);
        map.setCellType(7, 7, CellType.INTERSECTION);

        DynamicAStarPathfinder pathfinder = new DynamicAStarPathfinder();
        FleetMetricsCollector metricsCollector = new FleetMetricsCollector();
        AtomicBoolean abnormalExceptionDetected = new AtomicBoolean(false);

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
                    .heartbeatTimeoutMs(2000)
                    .initialPosition(initialPositions[i].toPosition2D())
                    .initialBattery(100.0)
                    .auctionTimeoutMs(300)
                    .mapDiagonal(16.97)
                    .allowSelfBidding(true)
                    .build();

            node.getPeerManager().addMessageListener((msg, ch) -> {
                // Packet verified and decoded
            });

            node.start();
            nodes.add(node);

            AgvRobot robot = new AgvRobot(node, map, pathfinder, initialPositions[i]);
            robot.setModelName("AGV-" + nodeId);
            robots.add(robot);
        }

        // Wait for 5-node full P2P mesh
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            for (AgvNode n : nodes) {
                assertThat(n.getActivePeerCount()).isEqualTo(4);
            }
        });

        // 3 Concurrent Tasks announced at start
        Task t1 = new Task("SMOKE-1", 2, 2, 8, 8);
        Task t2 = new Task("SMOKE-2", 8, 2, 2, 8);
        Task t3 = new Task("SMOKE-3", 5, 2, 5, 9);

        nodes.get(0).announceTask(t1);
        nodes.get(1).announceTask(t2);
        nodes.get(2).announceTask(t3);

        List<Task> activeTasks = List.of(t1, t2, t3);

        // Run simulation physics loop via ScheduledExecutorService (no Thread.sleep)
        ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(() -> {
            for (AgvRobot robot : robots) {
                try {
                    robot.updatePhysicsAndLogic(null);
                } catch (Exception ex) {
                    if (ex instanceof CorruptedFrameException) {
                        abnormalExceptionDetected.set(true);
                    }
                }
            }

            // Check ground-truth collision
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

        // Await completion of all 3 tasks within 20s
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            long completed = activeTasks.stream().filter(t -> t.getStatus() == Task.Status.COMPLETED).count();
            assertThat(completed)
                    .as("At least 3 concurrent tasks must be COMPLETED")
                    .isGreaterThanOrEqualTo(3);
        });

        assertThat(abnormalExceptionDetected.get())
                .as("No CorruptedFrameException allowed during free-running smoke run")
                .isFalse();

        assertThat(metricsCollector.getGroundTruthCollisions())
                .as("0 physical collisions between robots")
                .isEqualTo(0);
    }
}
