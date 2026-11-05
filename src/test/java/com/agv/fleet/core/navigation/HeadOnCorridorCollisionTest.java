package com.agv.fleet.core.navigation;

import com.agv.fleet.core.AgvNode;
import com.agv.fleet.core.agent.AgvRobot;
import com.agv.fleet.core.map.CellType;
import com.agv.fleet.core.map.GridMap;
import com.agv.fleet.core.map.GridPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class HeadOnCorridorCollisionTest {

    private AgvNode node1;
    private AgvNode node2;

    @AfterEach
    void tearDown() {
        if (node1 != null) {
            try { node1.stop(); } catch (Exception ignored) {}
        }
        if (node2 != null) {
            try { node2.stop(); } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Task 5: Two robots facing each other in 1-cell corridor with siding in middle reach destinations without deadlock or overlap")
    void testHeadOnCorridorCollisionAvoidanceWithSiding() throws Exception {
        int multicastPort = ThreadLocalRandom.current().nextInt(20000, 30000);

        node1 = AgvNode.builder()
                .nodeId(1L)
                .tcpPort(19601)
                .multicastPort(multicastPort)
                .heartbeatIntervalMs(100)
                .heartbeatTimeoutMs(1000)
                .build();

        node2 = AgvNode.builder()
                .nodeId(2L)
                .tcpPort(19602)
                .multicastPort(multicastPort)
                .heartbeatIntervalMs(100)
                .heartbeatTimeoutMs(1000)
                .build();

        node1.start();
        node2.start();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(node1.getActivePeerCount()).isEqualTo(1);
            assertThat(node2.getActivePeerCount()).isEqualTo(1);
        });

        // 7x5 map with 1-cell wide corridor at y=1 (x=1..5) and siding at (3,2)
        GridMap map = new GridMap(7, 5);
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 5; y++) {
                map.setCellType(x, y, CellType.OBSTACLE);
            }
        }
        for (int x = 1; x <= 5; x++) {
            map.setCellType(x, 1, CellType.EMPTY);
        }
        map.setCellType(3, 2, CellType.EMPTY); // siding in middle

        DynamicAStarPathfinder pathfinder = new DynamicAStarPathfinder();
        GridPoint start1 = new GridPoint(1, 1);
        GridPoint target1 = new GridPoint(5, 1);
        GridPoint start2 = new GridPoint(5, 1);
        GridPoint target2 = new GridPoint(1, 1);

        AgvRobot robot1 = new AgvRobot(node1, map, pathfinder, start1);
        AgvRobot robot2 = new AgvRobot(node2, map, pathfinder, start2);

        robot1.setDestination(target1);
        robot2.setDestination(target2);

        // Run simulation ticks
        int maxTicks = 80;
        int tick = 0;
        boolean bothReached = false;

        while (tick < maxTicks) {
            tick++;

            robot1.updatePhysicsAndLogic(null);
            robot2.updatePhysicsAndLogic(null);

            // Assert no cell overlap at ANY tick
            assertThat(robot1.getCurrentGridPos())
                    .as("Robots must never overlap at tick " + tick)
                    .isNotEqualTo(robot2.getCurrentGridPos());

            if (robot1.getCurrentGridPos().equals(target1) && robot2.getCurrentGridPos().equals(target2)) {
                bothReached = true;
                break;
            }

            // Allow peer positions to sync over TCP via Awaitility poll delay
            await().pollDelay(40, TimeUnit.MILLISECONDS).until(() -> true);
        }

        assertThat(bothReached)
                .as("Both robots must reach their destinations without deadlocking. Final positions: R1="
                        + robot1.getCurrentGridPos() + ", R2=" + robot2.getCurrentGridPos())
                .isTrue();
    }
}
