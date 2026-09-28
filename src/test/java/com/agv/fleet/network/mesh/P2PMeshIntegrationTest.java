package com.agv.fleet.network.mesh;

import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import com.agv.fleet.core.AgvNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("P2P Mesh Integration Tests (Multi-Node Localhost Full-Mesh)")
class P2PMeshIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(P2PMeshIntegrationTest.class);

    private AgvNode node1;
    private AgvNode node2;
    private AgvNode node3;
    private int multicastPort;

    @BeforeEach
    void setUp() {
        // Use an isolated multicast port per test to avoid cross-test packet interference
        multicastPort = ThreadLocalRandom.current().nextInt(10000, 20000);
    }

    @AfterEach
    void tearDown() {
        if (node1 != null) {
            try { node1.stop(); } catch (Exception ignored) {}
        }
        if (node2 != null) {
            try { node2.stop(); } catch (Exception ignored) {}
        }
        if (node3 != null) {
            try { node3.stop(); } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("1. Three Nodes Full-Mesh Discovery and Connection")
    void testThreeNodesFullMeshDiscoveryAndConnect() throws Exception {
        log.info("--- Starting testThreeNodesFullMeshDiscoveryAndConnect ---");

        node1 = AgvNode.builder()
                .nodeId(1L)
                .host("127.0.0.1")
                .tcpPort(9001)
                .multicastPort(multicastPort)
                .discoveryIntervalMs(200)
                .heartbeatIntervalMs(300)
                .heartbeatTimeoutMs(1000)
                .build();

        node2 = AgvNode.builder()
                .nodeId(2L)
                .host("127.0.0.1")
                .tcpPort(9002)
                .multicastPort(multicastPort)
                .discoveryIntervalMs(200)
                .heartbeatIntervalMs(300)
                .heartbeatTimeoutMs(1000)
                .build();

        node3 = AgvNode.builder()
                .nodeId(3L)
                .host("127.0.0.1")
                .tcpPort(9003)
                .multicastPort(multicastPort)
                .discoveryIntervalMs(200)
                .heartbeatIntervalMs(300)
                .heartbeatTimeoutMs(1000)
                .build();

        // Boot up all three nodes simultaneously
        node1.start();
        node2.start();
        node3.start();

        // Wait up to 5 seconds for UDP Multicast discovery & TCP connection establishment
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(node1.getActivePeerCount()).isEqualTo(2);
                    assertThat(node2.getActivePeerCount()).isEqualTo(2);
                    assertThat(node3.getActivePeerCount()).isEqualTo(2);
                });

        // Verify Full-Mesh Topology
        assertThat(node1.getPeerManager().hasPeer(2L)).isTrue();
        assertThat(node1.getPeerManager().hasPeer(3L)).isTrue();

        assertThat(node2.getPeerManager().hasPeer(1L)).isTrue();
        assertThat(node2.getPeerManager().hasPeer(3L)).isTrue();

        assertThat(node3.getPeerManager().hasPeer(1L)).isTrue();
        assertThat(node3.getPeerManager().hasPeer(2L)).isTrue();

        log.info("Full-Mesh network successfully established across all 3 nodes!");
    }

    @Test
    @DisplayName("2. Broadcast Message across P2P Mesh")
    void testBroadcastMessage() throws Exception {
        log.info("--- Starting testBroadcastMessage ---");

        node1 = AgvNode.builder()
                .nodeId(1L)
                .host("127.0.0.1")
                .tcpPort(9011)
                .multicastPort(multicastPort)
                .discoveryIntervalMs(200)
                .heartbeatIntervalMs(300)
                .heartbeatTimeoutMs(1000)
                .build();

        node2 = AgvNode.builder()
                .nodeId(2L)
                .host("127.0.0.1")
                .tcpPort(9012)
                .multicastPort(multicastPort)
                .discoveryIntervalMs(200)
                .heartbeatIntervalMs(300)
                .heartbeatTimeoutMs(1000)
                .build();

        node3 = AgvNode.builder()
                .nodeId(3L)
                .host("127.0.0.1")
                .tcpPort(9013)
                .multicastPort(multicastPort)
                .discoveryIntervalMs(200)
                .heartbeatIntervalMs(300)
                .heartbeatTimeoutMs(1000)
                .build();

        CopyOnWriteArrayList<AgvMessage> node2Received = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<AgvMessage> node3Received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        node2.registerMessageListener((msg, ch) -> {
            if (msg.getOpcode() == Opcode.TASK_ANNOUNCE) {
                node2Received.add(msg);
                latch.countDown();
            }
        });

        node3.registerMessageListener((msg, ch) -> {
            if (msg.getOpcode() == Opcode.TASK_ANNOUNCE) {
                node3Received.add(msg);
                latch.countDown();
            }
        });

        node1.start();
        node2.start();
        node3.start();

        // Wait until mesh connects
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(node1.getActivePeerCount()).isEqualTo(2);
            assertThat(node2.getActivePeerCount()).isEqualTo(2);
            assertThat(node3.getActivePeerCount()).isEqualTo(2);
        });

        // Node 1 broadcasts a task announcement
        String taskPayload = "{\"taskId\":\"TASK-AGV-001\",\"pickup\":\"A1\",\"dropoff\":\"B4\"}";
        byte[] payloadBytes = taskPayload.getBytes(StandardCharsets.UTF_8);

        node1.broadcast(Opcode.TASK_ANNOUNCE, payloadBytes);

        // Await delivery on Node 2 and Node 3
        boolean received = latch.await(3, TimeUnit.SECONDS);
        assertThat(received).isTrue();

        assertThat(node2Received).hasSize(1);
        AgvMessage msg2 = node2Received.get(0);
        assertThat(msg2.getSenderNodeId()).isEqualTo(1L);
        assertThat(msg2.getOpcode()).isEqualTo(Opcode.TASK_ANNOUNCE);
        assertThat(msg2.getPayloadAsString()).isEqualTo(taskPayload);

        assertThat(node3Received).hasSize(1);
        AgvMessage msg3 = node3Received.get(0);
        assertThat(msg3.getSenderNodeId()).isEqualTo(1L);
        assertThat(msg3.getOpcode()).isEqualTo(Opcode.TASK_ANNOUNCE);
        assertThat(msg3.getPayloadAsString()).isEqualTo(taskPayload);

        log.info("Broadcast message verified on both Node 2 and Node 3!");
    }

    @Test
    @DisplayName("3. Node Crash Recovery and Failure Detection via Heartbeat Timeout")
    void testNodeCrashRecovery() throws Exception {
        log.info("--- Starting testNodeCrashRecovery ---");

        node1 = AgvNode.builder()
                .nodeId(1L)
                .host("127.0.0.1")
                .tcpPort(9021)
                .multicastPort(multicastPort)
                .discoveryIntervalMs(200)
                .heartbeatIntervalMs(200)
                .heartbeatTimeoutMs(800)
                .build();

        node2 = AgvNode.builder()
                .nodeId(2L)
                .host("127.0.0.1")
                .tcpPort(9022)
                .multicastPort(multicastPort)
                .discoveryIntervalMs(200)
                .heartbeatIntervalMs(200)
                .heartbeatTimeoutMs(800)
                .build();

        node3 = AgvNode.builder()
                .nodeId(3L)
                .host("127.0.0.1")
                .tcpPort(9023)
                .multicastPort(multicastPort)
                .discoveryIntervalMs(200)
                .heartbeatIntervalMs(200)
                .heartbeatTimeoutMs(800)
                .build();

        AtomicInteger node1DroppedCount = new AtomicInteger(0);
        AtomicInteger node2DroppedCount = new AtomicInteger(0);

        node1.registerPeerLifecycleListener(new PeerLifecycleListener() {
            @Override
            public void onPeerConnected(long peerId, io.netty.channel.Channel channel) {}

            @Override
            public void onPeerDropped(long peerId) {
                if (peerId == 3L) {
                    node1DroppedCount.incrementAndGet();
                }
            }
        });

        node2.registerPeerLifecycleListener(new PeerLifecycleListener() {
            @Override
            public void onPeerConnected(long peerId, io.netty.channel.Channel channel) {}

            @Override
            public void onPeerDropped(long peerId) {
                if (peerId == 3L) {
                    node2DroppedCount.incrementAndGet();
                }
            }
        });

        node1.start();
        node2.start();
        node3.start();

        // 1. Await full mesh
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(node1.getActivePeerCount()).isEqualTo(2);
            assertThat(node2.getActivePeerCount()).isEqualTo(2);
            assertThat(node3.getActivePeerCount()).isEqualTo(2);
        });

        log.info("Simulating sudden crash of Node 3...");
        node3.stop();

        // 2. Await failure detection and removal of Node 3 from Node 1 and Node 2
        await().atMost(3, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(node1.getActivePeerCount()).isEqualTo(1);
                    assertThat(node2.getActivePeerCount()).isEqualTo(1);
                    assertThat(node1.getPeerManager().hasPeer(3L)).isFalse();
                    assertThat(node2.getPeerManager().hasPeer(3L)).isFalse();
                    assertThat(node1DroppedCount.get()).isGreaterThanOrEqualTo(1);
                    assertThat(node2DroppedCount.get()).isGreaterThanOrEqualTo(1);
                });

        // Verify remaining connection between Node 1 and Node 2 is still healthy
        assertThat(node1.getPeerManager().hasPeer(2L)).isTrue();
        assertThat(node2.getPeerManager().hasPeer(1L)).isTrue();

        log.info("Node crash detection and topology recovery successfully verified!");
    }
}
