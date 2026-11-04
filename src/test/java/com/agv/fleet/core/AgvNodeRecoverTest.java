package com.agv.fleet.core;

import com.agv.fleet.core.agent.Task;
import com.agv.fleet.core.agent.Position2D;
import com.agv.fleet.distributed.auction.AuctionSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

class AgvNodeRecoverTest {

    private static final Logger log = LoggerFactory.getLogger(AgvNodeRecoverTest.class);

    @Test
    @DisplayName("Bug 2: start -> stop -> start -> announceTask must not throw RejectedExecutionException and auction succeeds")
    void testNodeRestartAndAnnounceTaskSucceeds() throws Exception {
        int multicastPort = 25100;
        AgvNode node1 = AgvNode.builder()
                .nodeId(1L)
                .tcpPort(19101)
                .multicastPort(multicastPort)
                .initialBattery(100.0)
                .initialPosition(new Position2D(0, 0))
                .auctionTimeoutMs(400)
                .discoveryIntervalMs(100)
                .heartbeatIntervalMs(150)
                .heartbeatTimeoutMs(800)
                .build();

        AgvNode node2 = AgvNode.builder()
                .nodeId(2L)
                .tcpPort(19102)
                .multicastPort(multicastPort)
                .initialBattery(100.0)
                .initialPosition(new Position2D(2, 0))
                .auctionTimeoutMs(400)
                .discoveryIntervalMs(100)
                .heartbeatIntervalMs(150)
                .heartbeatTimeoutMs(800)
                .build();

        try {
            // First boot
            node1.start();
            node2.start();

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(node1.getActivePeerCount()).isEqualTo(1);
                assertThat(node2.getActivePeerCount()).isEqualTo(1);
            });

            // Stop node1 (simulating crash / reboot)
            node1.stop();

            // Restart node1 (recover)
            node1.start();

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(node1.getActivePeerCount()).isGreaterThanOrEqualTo(1);
            });

            AtomicReference<Task> assignedTaskRef = new AtomicReference<>();
            node2.addAssignmentListener(assignedTaskRef::set);

            Task task = new Task("TASK-RECOVER-1", 0.0, 0.0, 20.0, 20.0);

            // This should NOT throw RejectedExecutionException!
            AtomicReference<AuctionSession> sessionRef = new AtomicReference<>();
            assertThatCode(() -> {
                AuctionSession session = node1.announceTask(task);
                sessionRef.set(session);
            }).doesNotThrowAnyException();

            // The auction should successfully complete and award the task to Node 2
            await().atMost(4, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(sessionRef.get()).isNotNull();
                assertThat(sessionRef.get().isClosed()).isTrue();
                assertThat(sessionRef.get().getTask().getStatus()).isEqualTo(Task.Status.ASSIGNED);
                assertThat(sessionRef.get().getTask().getAssignedNodeId()).isEqualTo(2L);
            });

            assertThat(assignedTaskRef.get()).isNotNull();
            assertThat(assignedTaskRef.get().getTaskId()).isEqualTo("TASK-RECOVER-1");
        } finally {
            try { node1.stop(); } catch (Exception ignored) {}
            try { node2.stop(); } catch (Exception ignored) {}
        }
    }
}
