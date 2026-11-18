package com.agv.fleet.ui;

import com.agv.fleet.core.AgvNode;
import com.agv.fleet.core.agent.AgvRobot;
import com.agv.fleet.core.agent.Task;
import com.agv.fleet.core.map.GridMap;
import com.agv.fleet.core.map.GridPoint;
import com.agv.fleet.core.navigation.DynamicAStarPathfinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("ContinuousTaskDriver Single Cycle & Control Unit Test")
class ContinuousTaskDriverTest {

    private final List<AgvNode> nodes = new ArrayList<>();
    private final List<AgvRobot> robots = new CopyOnWriteArrayList<>();
    private final List<Task> registeredTasks = new CopyOnWriteArrayList<>();
    private ContinuousTaskDriver driver;
    private GridMap map;
    private final DynamicAStarPathfinder pathfinder = new DynamicAStarPathfinder();

    @BeforeEach
    void setUp() throws Exception {
        map = GridMap.createDefaultWarehouseMap();
        int multicastPort = ThreadLocalRandom.current().nextInt(20000, 30000);
        int baseTcpPort = ThreadLocalRandom.current().nextInt(31000, 40000);

        for (int i = 0; i < 3; i++) {
            long nodeId = i + 1;
            GridPoint pos = new GridPoint(2 + i * 4, 2);
            AgvNode node = AgvNode.builder()
                    .nodeId(nodeId)
                    .tcpPort(baseTcpPort + i)
                    .multicastPort(multicastPort)
                    .discoveryIntervalMs(100)
                    .heartbeatIntervalMs(150)
                    .heartbeatTimeoutMs(1000)
                    .auctionTimeoutMs(150)
                    .initialBattery(100.0)
                    .initialPosition(pos.toPosition2D())
                    .build();
            node.start();
            nodes.add(node);

            AgvRobot robot = new AgvRobot(node, map, pathfinder, pos);
            robots.add(robot);
        }

        // Wait until mesh connects
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            for (AgvNode node : nodes) {
                assertThat(node.getActivePeerCount()).isEqualTo(2);
            }
        });
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            try { driver.stop(); } catch (Exception ignored) {}
        }
        for (AgvNode n : nodes) {
            try { n.stop(); } catch (Exception ignored) {}
        }
        robots.clear();
        nodes.clear();
        registeredTasks.clear();
    }

    @Test
    @DisplayName("Single cycle mode dispatches 1 task and pauses automatically upon task completion")
    void testSingleCycleModePausesOnCompletion() {
        AtomicBoolean completedCalled = new AtomicBoolean(false);
        AtomicReference<Task> completedTask = new AtomicReference<>();

        driver = new ContinuousTaskDriver(
                () -> new ArrayList<>(robots),
                () -> map,
                registeredTasks::add,
                announcer -> {},
                task -> {
                    completedCalled.set(true);
                    completedTask.set(task);
                },
                300L,
                123L
        );
        driver.setRemainingCycles(1);
        driver.start();

        // 1. Verify 1 task is dispatched and awarded
        await().atMost(5, TimeUnit.SECONDS).until(() -> registeredTasks.size() == 1);
        Task task = registeredTasks.get(0);
        assertThat(task).isNotNull();

        // Wait for CNP auction to assign the task
        await().atMost(5, TimeUnit.SECONDS).until(() -> task.getStatus() == Task.Status.ASSIGNED);

        // 2. Simulate task being picked up, transported and completed at delivery
        task.setStatus(Task.Status.COMPLETED);

        // 3. Await driver processing the completion
        await().atMost(5, TimeUnit.SECONDS).untilTrue(completedCalled);
        assertThat(completedTask.get()).isEqualTo(task);
        assertThat(driver.isEnabled()).isFalse();
        assertThat(driver.getRemainingCycles()).isEqualTo(0);
        assertThat(driver.getCompletedCycleCount()).isEqualTo(1);

        // 4. Ensure no further tasks are dispatched while stopped
        await().during(1000, TimeUnit.MILLISECONDS).atMost(2, TimeUnit.SECONDS).until(() -> registeredTasks.size() == 1);
    }

    @Test
    @DisplayName("triggerSingleCycle resumes execution for exactly one additional cycle")
    void testTriggerSingleCycleResumes() {
        AtomicReference<Task> completedTask = new AtomicReference<>();

        driver = new ContinuousTaskDriver(
                () -> new ArrayList<>(robots),
                () -> map,
                registeredTasks::add,
                announcer -> {},
                completedTask::set,
                300L,
                456L
        );
        driver.setRemainingCycles(1);
        driver.start();

        // First task dispatched & assigned
        await().atMost(5, TimeUnit.SECONDS).until(() -> registeredTasks.size() == 1);
        Task t1 = registeredTasks.get(0);
        await().atMost(5, TimeUnit.SECONDS).until(() -> t1.getStatus() == Task.Status.ASSIGNED);

        // Complete first task
        t1.setStatus(Task.Status.COMPLETED);
        await().atMost(5, TimeUnit.SECONDS).until(() -> driver.getRemainingCycles() == 0);
        assertThat(driver.isEnabled()).isFalse();

        // Now trigger next cycle
        driver.triggerSingleCycle();
        assertThat(driver.isEnabled()).isTrue();
        assertThat(driver.getRemainingCycles()).isEqualTo(1);

        // Second task dispatched & assigned
        await().atMost(5, TimeUnit.SECONDS).until(() -> registeredTasks.size() == 2);
        Task t2 = registeredTasks.get(1);
        assertThat(t2.getTaskId()).isNotEqualTo(t1.getTaskId());
        await().atMost(5, TimeUnit.SECONDS).until(() -> t2.getStatus() == Task.Status.ASSIGNED);

        // Complete second task
        t2.setStatus(Task.Status.COMPLETED);
        await().atMost(5, TimeUnit.SECONDS).until(() -> driver.getRemainingCycles() == 0);
        assertThat(driver.isEnabled()).isFalse();
        assertThat(driver.getCompletedCycleCount()).isEqualTo(2);
    }
}
