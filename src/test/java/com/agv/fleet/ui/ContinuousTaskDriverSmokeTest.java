package com.agv.fleet.ui;

import com.agv.fleet.core.AgvNode;
import com.agv.fleet.core.agent.AgentState;
import com.agv.fleet.core.agent.AgvRobot;
import com.agv.fleet.core.agent.Task;
import com.agv.fleet.core.map.CellType;
import com.agv.fleet.core.map.GridMap;
import com.agv.fleet.core.map.GridPoint;
import com.agv.fleet.core.navigation.DynamicAStarPathfinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("ContinuousTaskDriver Headless 30s Integration Smoke Test")
class ContinuousTaskDriverSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(ContinuousTaskDriverSmokeTest.class);

    private final List<AgvNode> nodes = new ArrayList<>();
    private final List<AgvRobot> robots = new CopyOnWriteArrayList<>();
    private final List<Task> allTasks = new CopyOnWriteArrayList<>();
    private ContinuousTaskDriver driver;
    private ScheduledExecutorService physicsExecutor;

    @AfterEach
    void tearDown() {
        if (driver != null) {
            try { driver.stop(); } catch (Exception ignored) {}
        }
        if (physicsExecutor != null) {
            try { physicsExecutor.shutdownNow(); } catch (Exception ignored) {}
        }
        for (AgvRobot r : robots) {
            try { r.getNode().stop(); } catch (Exception ignored) {}
        }
        for (AgvNode node : nodes) {
            try { node.stop(); } catch (Exception ignored) {}
        }
        robots.clear();
        nodes.clear();
        allTasks.clear();
    }

    @Test
    @DisplayName("ContinuousTaskDriver runs 6 nodes headlessly for up to 30s: >= 5 tasks completed, all AGVs move, 0 collisions")
    void testContinuousTaskDriverHeadless30s() throws Exception {
        GridMap map = GridMap.createDefaultWarehouseMap();
        map.setCellType(4, 12, CellType.EMPTY); // Ensure (4, 12) is walkable
        map.setCellType(3, 12, CellType.EMPTY); // Connect (4, 12) to main corridor x=2

        int multicastPort = ThreadLocalRandom.current().nextInt(20000, 30000);
        int baseTcpPort = ThreadLocalRandom.current().nextInt(31000, 40000);

        GridPoint[] startPositions = {
                new GridPoint(2, 2),
                new GridPoint(8, 2),
                new GridPoint(14, 6),
                new GridPoint(20, 10),
                new GridPoint(4, 12),
                new GridPoint(18, 14)
        };
        double[] initialBatteries = {95.0, 80.0, 65.0, 50.0, 40.0, 30.0};

        // 1. Build and start 6 AGV Nodes
        for (int i = 0; i < 6; i++) {
            long nodeId = (long) (i + 1);
            AgvNode node = AgvNode.builder()
                    .nodeId(nodeId)
                    .tcpPort(baseTcpPort + i)
                    .multicastPort(multicastPort)
                    .discoveryIntervalMs(100)
                    .heartbeatIntervalMs(150)
                    .heartbeatTimeoutMs(1000)
                    .auctionTimeoutMs(150)
                    .initialBattery(initialBatteries[i])
                    .initialPosition(startPositions[i].toPosition2D())
                    .build();
            node.start();
            nodes.add(node);
        }

        // 2. Wait until full mesh topology is formed (5 peers per node)
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            for (AgvNode node : nodes) {
                assertThat(node.getActivePeerCount())
                        .as("Node %d must have 5 connected peers", node.getNodeId())
                        .isEqualTo(5);
            }
        });

        // 3. Create AGV Robots
        DynamicAStarPathfinder pathfinder = new DynamicAStarPathfinder();
        Map<Long, Integer> perVehicleTasks = new ConcurrentHashMap<>();
        Map<Long, Integer> totalDistance = new ConcurrentHashMap<>();
        Map<Long, Integer> mutexWaitCounts = new ConcurrentHashMap<>();
        Map<Long, GridPoint> lastPositions = new ConcurrentHashMap<>();
        AtomicInteger collisionCount = new AtomicInteger(0);

        for (int i = 0; i < 6; i++) {
            AgvNode node = nodes.get(i);
            AgvRobot robot = new AgvRobot(node, map, pathfinder, startPositions[i]);
            robots.add(robot);
            lastPositions.put(node.getNodeId(), startPositions[i]);

            long id = node.getNodeId();
            node.addAssignmentListener(t -> perVehicleTasks.merge(id, 1, Integer::sum));
        }

        // 4. Start high-frequency physics simulation loop (every 60ms = ~4x simulation speed)
        physicsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "smoke-test-physics");
            t.setDaemon(true);
            return t;
        });

        physicsExecutor.scheduleAtFixedRate(() -> {
            try {
                for (AgvRobot robot : robots) {
                    robot.updatePhysicsAndLogic(null);
                    GridPoint cur = robot.getCurrentGridPos();
                    GridPoint prev = lastPositions.put(robot.getNodeId(), cur);
                    if (prev != null && !prev.equals(cur)) {
                        totalDistance.merge(robot.getNodeId(), 1, Integer::sum);
                    }
                    if (robot.getState() == AgentState.WAITING_MUTEX) {
                        mutexWaitCounts.merge(robot.getNodeId(), 1, Integer::sum);
                    }
                }

                // Ground-truth cell collision check
                for (int i = 0; i < robots.size(); i++) {
                    for (int j = i + 1; j < robots.size(); j++) {
                        AgvRobot r1 = robots.get(i);
                        AgvRobot r2 = robots.get(j);
                        if (!r1.isCrashed() && !r2.isCrashed()) {
                            if (r1.getCurrentGridPos().equals(r2.getCurrentGridPos())) {
                                collisionCount.incrementAndGet();
                                log.error("COLLISION: AGV {} and AGV {} both in cell {}",
                                        r1.getNodeId(), r2.getNodeId(), r1.getCurrentGridPos());
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                log.error("Physics tick error", t);
            }
        }, 60, 60, TimeUnit.MILLISECONDS);

        // 5. Start ContinuousTaskDriver (periodMs = 800ms)
        driver = new ContinuousTaskDriver(
                () -> new ArrayList<>(robots),
                () -> map,
                allTasks::add,
                announcer -> {},
                800L,
                42L
        );
        driver.start();

        // 6. Await until at least 5 tasks complete and all 6 vehicles have moved, bounded by 30 seconds
        await().atMost(30, TimeUnit.SECONDS).until(() -> {
            long completed = allTasks.stream().filter(t -> t.getStatus() == Task.Status.COMPLETED).count();
            boolean allMoved = robots.stream().allMatch(r -> totalDistance.getOrDefault(r.getNodeId(), 0) > 0);
            return completed >= 5 && allMoved;
        });

        // 7. Print Execution Statistics
        long completedCount = allTasks.stream().filter(t -> t.getStatus() == Task.Status.COMPLETED).count();
        System.out.println("================================================================");
        System.out.println("         ContinuousTaskDriver 30s Smoke Test Results            ");
        System.out.println("================================================================");
        System.out.println("Total Tasks Generated : " + allTasks.size());
        System.out.println("Tasks Completed       : " + completedCount);
        System.out.println("Ground-Truth Collision: " + collisionCount.get());
        System.out.println("Per-Vehicle Tasks Won : " + perVehicleTasks);
        System.out.println("Per-Vehicle Distance  : " + totalDistance);
        System.out.println("Per-Vehicle Mutex Wait: " + mutexWaitCounts);
        System.out.println("================================================================");

        // 8. Assertions
        assertThat(completedCount)
                .as("At least 5 tasks must be completed within 30s")
                .isGreaterThanOrEqualTo(5);

        assertThat(collisionCount.get())
                .as("There must be ZERO ground-truth cell collisions")
                .isZero();

        for (AgvRobot robot : robots) {
            assertThat(totalDistance.getOrDefault(robot.getNodeId(), 0))
                    .as("Vehicle %d must have moved (distance > 0)", robot.getNodeId())
                    .isGreaterThan(0);
        }
    }
}
