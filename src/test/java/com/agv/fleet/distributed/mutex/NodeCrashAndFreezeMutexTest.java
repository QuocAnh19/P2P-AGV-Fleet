package com.agv.fleet.distributed.mutex;

import com.agv.fleet.core.AgvNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class NodeCrashAndFreezeMutexTest {

    private AgvNode node1;
    private AgvNode node2;
    private int multicastPort;

    @BeforeEach
    void setUp() {
        multicastPort = ThreadLocalRandom.current().nextInt(20000, 30000);
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
    @DisplayName("Task 3 (a): Node holding HELD crashes abruptly -> WANTED node acquires lock")
    void testNodeHoldingHeldCrashes_wantedNodeAcquires() throws Exception {
        String cellId = "INTERSECTION-CRASH-TEST";

        node1 = AgvNode.builder()
                .nodeId(1L)
                .tcpPort(19401)
                .multicastPort(multicastPort)
                .heartbeatIntervalMs(100)
                .heartbeatTimeoutMs(600)
                .build();

        node2 = AgvNode.builder()
                .nodeId(2L)
                .tcpPort(19402)
                .multicastPort(multicastPort)
                .heartbeatIntervalMs(100)
                .heartbeatTimeoutMs(600)
                .build();

        node1.start();
        node2.start();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(node1.getActivePeerCount()).isEqualTo(1);
            assertThat(node2.getActivePeerCount()).isEqualTo(1);
        });

        // 1. Node 1 acquires intersection -> HELD
        CompletableFuture<Void> grant1 = node1.acquireIntersection(cellId);
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(grant1.isDone()).isTrue();
            assertThat(node1.getMutexState(cellId)).isEqualTo(MutexState.HELD);
        });

        // 2. Node 2 requests intersection -> WANTED (deferred by Node 1)
        CompletableFuture<Void> grant2 = node2.acquireIntersection(cellId);
        assertThat(grant2.isDone()).isFalse();
        assertThat(node2.getMutexState(cellId)).isEqualTo(MutexState.WANTED);

        // 3. Node 1 crashes abruptly without releasing mutex or sending replies
        node1.crash();

        // 4. Node 2 must safely acquire lock (HELD) after detecting peer dropped
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(grant2.isDone()).isTrue();
            assertThat(node2.getMutexState(cellId)).isEqualTo(MutexState.HELD);
        });
    }

    @Test
    @DisplayName("Task 3 (b): Node holding HELD freezes network -> WANTED node acquires after heartbeat timeout")
    void testNodeHoldingHeldFreezesNetwork_wantedNodeAcquiresAfterHeartbeatTimeout() throws Exception {
        String cellId = "INTERSECTION-FREEZE-TEST";

        node1 = AgvNode.builder()
                .nodeId(1L)
                .tcpPort(19411)
                .multicastPort(multicastPort)
                .heartbeatIntervalMs(100)
                .heartbeatTimeoutMs(600)
                .build();

        node2 = AgvNode.builder()
                .nodeId(2L)
                .tcpPort(19412)
                .multicastPort(multicastPort)
                .heartbeatIntervalMs(100)
                .heartbeatTimeoutMs(600)
                .build();

        node1.start();
        node2.start();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(node1.getActivePeerCount()).isEqualTo(1);
            assertThat(node2.getActivePeerCount()).isEqualTo(1);
        });

        // 1. Node 1 acquires intersection -> HELD
        CompletableFuture<Void> grant1 = node1.acquireIntersection(cellId);
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(grant1.isDone()).isTrue();
            assertThat(node1.getMutexState(cellId)).isEqualTo(MutexState.HELD);
        });

        // 2. Node 2 requests intersection -> WANTED (deferred by Node 1)
        CompletableFuture<Void> grant2 = node2.acquireIntersection(cellId);
        assertThat(grant2.isDone()).isFalse();
        assertThat(node2.getMutexState(cellId)).isEqualTo(MutexState.WANTED);

        // 3. Node 1 freezes network (sockets remain open, no heartbeats sent)
        node1.freezeNetwork();

        // 4. Node 2 must detect failure via heartbeat timeout (~600ms) and acquire lock (HELD)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(grant2.isDone()).isTrue();
            assertThat(node2.getMutexState(cellId)).isEqualTo(MutexState.HELD);
        });
    }
}
