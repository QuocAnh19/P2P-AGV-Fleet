package com.agv.fleet.distributed.mutex;

import com.agv.fleet.core.AgvNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Ricart-Agrawala Distributed Mutual Exclusion Tests")
class RicartAgrawalaTest {

    private static final Logger log = LoggerFactory.getLogger(RicartAgrawalaTest.class);

    private AgvNode node1;
    private AgvNode node2;
    private int multicastPort;

    @BeforeEach
    void setUp() {
        multicastPort = ThreadLocalRandom.current().nextInt(20001, 30000);
    }

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
    @DisplayName("1. Single Node - Immediate Acquire without Blocking")
    void testSingleNodeImmediateAcquire() throws Exception {
        log.info("--- Starting testSingleNodeImmediateAcquire ---");

        node1 = AgvNode.builder()
                .nodeId(1L)
                .host("127.0.0.1")
                .tcpPort(9101)
                .multicastPort(multicastPort)
                .build();
        node1.start();

        String cellId = "intersection_single";

        // Request intersection
        CompletableFuture<Void> grantFuture = node1.acquireIntersection(cellId);

        // Single node must acquire immediately
        assertThat(grantFuture).isCompleted();
        assertThat(node1.getMutexState(cellId)).isEqualTo(MutexState.HELD);
        assertThat(node1.getMutexEngine().isHolding(cellId)).isTrue();

        // Release intersection
        node1.releaseIntersection(cellId);
        assertThat(node1.getMutexState(cellId)).isEqualTo(MutexState.RELEASED);
        assertThat(node1.getMutexEngine().isHolding(cellId)).isFalse();

        log.info("Single node immediate acquire & release successfully verified!");
    }

    @Test
    @DisplayName("2. Two Nodes Head-On Collision - Deadlock-Free Priority Resolution")
    void testTwoNodesHeadOnCollisionDeadlockResolution() throws Exception {
        log.info("--- Starting testTwoNodesHeadOnCollisionDeadlockResolution ---");

        node1 = AgvNode.builder()
                .nodeId(1L)
                .host("127.0.0.1")
                .tcpPort(9111)
                .multicastPort(multicastPort)
                .discoveryIntervalMs(150)
                .heartbeatIntervalMs(200)
                .heartbeatTimeoutMs(1000)
                .build();

        node2 = AgvNode.builder()
                .nodeId(2L)
                .host("127.0.0.1")
                .tcpPort(9112)
                .multicastPort(multicastPort)
                .discoveryIntervalMs(150)
                .heartbeatIntervalMs(200)
                .heartbeatTimeoutMs(1000)
                .build();

        node1.start();
        node2.start();

        // Await P2P Mesh connection between Node 1 and Node 2
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(node1.getActivePeerCount()).isEqualTo(1);
            assertThat(node2.getActivePeerCount()).isEqualTo(1);
        });

        String cellId = "intersection_A";

        // Both nodes simultaneously request the same intersection
        CompletableFuture<Void> future1 = node1.acquireIntersection(cellId);
        CompletableFuture<Void> future2 = node2.acquireIntersection(cellId);

        // Because Node 1 has lower Node ID (1 < 2), Node 1 has strictly higher priority
        // Node 1 must acquire HELD first
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(future1).isCompleted();
            assertThat(node1.getMutexState(cellId)).isEqualTo(MutexState.HELD);
        });

        // Meanwhile, Node 2 MUST remain blocked in WANTED state awaiting Node 1's deferred reply
        assertThat(future2).isNotDone();
        assertThat(node2.getMutexState(cellId)).isEqualTo(MutexState.WANTED);

        log.info("Node 1 successfully won intersection priority. Node 2 safely blocked.");

        // Node 1 finishes crossing and releases the intersection
        node1.releaseIntersection(cellId);
        assertThat(node1.getMutexState(cellId)).isEqualTo(MutexState.RELEASED);

        // Node 2 must now receive the deferred reply and immediately transition to HELD
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(future2).isCompleted();
            assertThat(node2.getMutexState(cellId)).isEqualTo(MutexState.HELD);
        });

        log.info("Node 2 successfully granted intersection after Node 1 release!");

        // Finally, Node 2 finishes crossing and releases
        node2.releaseIntersection(cellId);
        assertThat(node2.getMutexState(cellId)).isEqualTo(MutexState.RELEASED);

        log.info("Head-on collision resolution completed with zero conflict and zero deadlock!");
    }

    @Test
    @DisplayName("3. Acquire With Crashing Peer - Automatic Fault Recovery")
    void testAcquireWithCrashingPeer() throws Exception {
        log.info("--- Starting testAcquireWithCrashingPeer ---");

        node1 = AgvNode.builder()
                .nodeId(1L)
                .host("127.0.0.1")
                .tcpPort(9121)
                .multicastPort(multicastPort)
                .discoveryIntervalMs(150)
                .heartbeatIntervalMs(200)
                .heartbeatTimeoutMs(800)
                .build();

        node2 = AgvNode.builder()
                .nodeId(2L)
                .host("127.0.0.1")
                .tcpPort(9122)
                .multicastPort(multicastPort)
                .discoveryIntervalMs(150)
                .heartbeatIntervalMs(200)
                .heartbeatTimeoutMs(800)
                .build();

        node1.start();
        node2.start();

        // Await connection
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(node1.getActivePeerCount()).isEqualTo(1);
            assertThat(node2.getActivePeerCount()).isEqualTo(1);
        });

        String cellId = "intersection_B";

        // Have Node 2 acquire intersection first so Node 2 is HELD
        CompletableFuture<Void> n2Future = node2.acquireIntersection(cellId);
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> assertThat(n2Future).isCompleted());
        assertThat(node2.getMutexState(cellId)).isEqualTo(MutexState.HELD);

        // Now Node 1 requests the same cell. Node 2 will defer reply because it's HELD.
        CompletableFuture<Void> n1Future = node1.acquireIntersection(cellId);
        assertThat(n1Future).isNotDone();
        assertThat(node1.getMutexState(cellId)).isEqualTo(MutexState.WANTED);

        // Simulate abrupt crash of Node 2 while holding the intersection
        log.info("Simulating sudden crash of Node 2 while holding the lock...");
        node2.stop();

        // Node 1 must detect Node 2 dropped and automatically grant itself HELD without hanging forever
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(n1Future).isCompleted();
            assertThat(node1.getMutexState(cellId)).isEqualTo(MutexState.HELD);
        });

        log.info("Node 1 successfully recovered from crashed peer and acquired HELD!");

        node1.releaseIntersection(cellId);
        assertThat(node1.getMutexState(cellId)).isEqualTo(MutexState.RELEASED);
    }
}
