package com.agv.fleet.network.mesh;

import com.agv.fleet.common.protocol.payload.PositionUpdatePayload;
import com.agv.fleet.core.AgvNode;
import com.agv.fleet.core.agent.AgentState;
import com.agv.fleet.core.agent.AgvRobot;
import com.agv.fleet.core.agent.PeerPositionTable;
import com.agv.fleet.core.agent.Position2D;
import com.agv.fleet.core.map.GridMap;
import com.agv.fleet.core.map.GridPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class PeerPositionTableTest {

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
    @DisplayName("Task 4: Payload binary serialization roundtrip")
    void testPositionUpdatePayloadSerialization() {
        PositionUpdatePayload original = new PositionUpdatePayload(12.5, 34.0, 15.0, 40.0, AgentState.MOVING);
        byte[] bytes = original.toBytes();
        assertThat(bytes).hasSize(33);

        PositionUpdatePayload deserialized = PositionUpdatePayload.fromBytes(bytes);
        assertThat(deserialized.x()).isEqualTo(12.5);
        assertThat(deserialized.y()).isEqualTo(34.0);
        assertThat(deserialized.targetX()).isEqualTo(15.0);
        assertThat(deserialized.targetY()).isEqualTo(40.0);
        assertThat(deserialized.state()).isEqualTo(AgentState.MOVING);
    }

    @Test
    @DisplayName("Task 4: PeerPositionTable stores positions and expires after timeout")
    void testPeerPositionTableExpiration() throws InterruptedException {
        long timeoutMs = 300;
        PeerPositionTable table = new PeerPositionTable(1L, timeoutMs);

        table.update(2L, 5.0, 7.0, 10.0, 10.0, AgentState.MOVING);
        assertThat(table.getActiveObstaclePoints()).containsExactly(new GridPoint(5, 7));

        // Sleep via Awaitility until elapsed > timeoutMs
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(table.getActiveObstaclePoints()).isEmpty();
        });
    }

    @Test
    @DisplayName("Task 4: AGV broadcast position on cell change, peer populates PeerPositionTable and yields without simulator")
    void testPositionBroadcastAndDecentralizedObstacleAvoidance() throws Exception {
        int multicastPort = ThreadLocalRandom.current().nextInt(20000, 30000);

        node1 = AgvNode.builder()
                .nodeId(1L)
                .tcpPort(19501)
                .multicastPort(multicastPort)
                .heartbeatIntervalMs(100)
                .heartbeatTimeoutMs(1000)
                .build();

        node2 = AgvNode.builder()
                .nodeId(2L)
                .tcpPort(19502)
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

        GridMap map = new GridMap(20, 15);
        com.agv.fleet.core.navigation.DynamicAStarPathfinder pathfinder = new com.agv.fleet.core.navigation.DynamicAStarPathfinder();
        AgvRobot robot1 = new AgvRobot(node1, map, pathfinder, new GridPoint(2, 6));
        AgvRobot robot2 = new AgvRobot(node2, map, pathfinder, new GridPoint(2, 8));

        // Robot 1 broadcasts its initial position
        robot1.broadcastPosition();

        // Node 2's PeerPositionTable must receive Node 1 at (2, 6)
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(node2.getPeerPositionTable().getActiveObstaclePoints())
                    .contains(new GridPoint(2, 6));
        });

        // Robot 2 sets destination to (2, 5) which goes through (2, 7) then (2, 6)
        // Set robot 1 at (2, 7)
        robot1.setDestination(new GridPoint(2, 7));
        robot1.updatePhysicsAndLogic(null);

        // Node 2 must now have Robot 1 at (2, 7)
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(node2.getPeerPositionTable().getActiveObstaclePoints())
                    .contains(new GridPoint(2, 7));
        });

        // Robot 2 wants to move into (2, 7)
        robot2.setDestination(new GridPoint(2, 7));
        // Calling updatePhysicsAndLogic with NULL (simulator does NOT pass obstacles)
        robot2.updatePhysicsAndLogic(null);

        // Robot 2 must NOT step into (2, 7) because its own PeerPositionTable blocks it!
        assertThat(robot2.getCurrentGridPos()).isEqualTo(new GridPoint(2, 8));
    }

    @Test
    @DisplayName("Task B: Stationary AGV stays in PeerPositionTable for 5s via 250ms periodic broadcast, disappears on freeze")
    void testStationaryRobotRemainsAndDisappearsOnFreeze() throws Exception {
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

        GridMap map = new GridMap(20, 15);
        com.agv.fleet.core.navigation.DynamicAStarPathfinder pathfinder = new com.agv.fleet.core.navigation.DynamicAStarPathfinder();
        AgvRobot robot1 = new AgvRobot(node1, map, pathfinder, new GridPoint(3, 4));
        AgvRobot robot2 = new AgvRobot(node2, map, pathfinder, new GridPoint(8, 8));

        node1.setPosition(new Position2D(3.0, 4.0));
        node1.setTargetPosition(new Position2D(3.0, 4.0));

        // 1. Initially node2 receives node 1's position
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(node2.getPeerPositionTable().containsActivePeer(1L)).isTrue();
        });

        // 2. Robot 1 is stationary for 5 seconds without moving.
        // Because timeout is 750ms, if periodic broadcast didn't fire every 250ms, it would expire!
        // We verify that over a 5 second duration, node 1 continuously remains in node2's PeerPositionTable.
        await().during(5, TimeUnit.SECONDS).atMost(7, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(node2.getPeerPositionTable().containsActivePeer(1L)).isTrue();
        });

        // 3. Freeze node 1's network
        node1.freezeNetwork();

        // 4. After freeze, periodic broadcast stops. Node 1 must disappear after timeout (750ms)
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(node2.getPeerPositionTable().containsActivePeer(1L)).isFalse();
        });
    }
}
