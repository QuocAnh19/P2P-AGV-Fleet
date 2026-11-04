package com.agv.fleet.distributed.auction;

import com.agv.fleet.core.AgvNode;
import com.agv.fleet.core.agent.AgentState;
import com.agv.fleet.core.agent.Position2D;
import com.agv.fleet.core.agent.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TaskCompletionFaultToleranceTest {

    private static final Logger log = LoggerFactory.getLogger(TaskCompletionFaultToleranceTest.class);

    @Test
    @DisplayName("Bug 3: When contractor completes task and then crashes, manager must NOT re-announce the task")
    void testCompletedTaskIsNotReAnnouncedWhenContractorCrashes() throws Exception {
        int multicastPort = 25200;
        AgvNode manager = AgvNode.builder()
                .nodeId(1L)
                .tcpPort(19201)
                .multicastPort(multicastPort)
                .initialBattery(100.0)
                .initialPosition(new Position2D(0, 0))
                .auctionTimeoutMs(400)
                .discoveryIntervalMs(100)
                .heartbeatIntervalMs(150)
                .heartbeatTimeoutMs(800)
                .build();

        AgvNode contractor = AgvNode.builder()
                .nodeId(2L)
                .tcpPort(19202)
                .multicastPort(multicastPort)
                .initialBattery(100.0)
                .initialPosition(new Position2D(1, 0))
                .auctionTimeoutMs(400)
                .discoveryIntervalMs(100)
                .heartbeatIntervalMs(150)
                .heartbeatTimeoutMs(800)
                .build();

        try {
            manager.start();
            contractor.start();

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(manager.getActivePeerCount()).isEqualTo(1);
                assertThat(contractor.getActivePeerCount()).isEqualTo(1);
            });

            AtomicReference<Task> assignedTaskRef = new AtomicReference<>();
            contractor.addAssignmentListener(assignedTaskRef::set);

            Task task = new Task("TASK-NO-REASSIGN-ON-COMPLETE", 0.0, 0.0, 10.0, 10.0);
            AuctionSession session = manager.announceTask(task);

            await().atMost(4, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(session.isClosed()).isTrue();
                assertThat(contractor.getAgentState()).isEqualTo(AgentState.BUSY);
                assertThat(session.getTask().getStatus()).isEqualTo(Task.Status.ASSIGNED);
                assertThat(session.getTask().getAssignedNodeId()).isEqualTo(2L);
            });

            assertThat(assignedTaskRef.get()).isNotNull();

            // Contractor completes the task and notifies manager
            contractor.notifyTaskComplete("TASK-NO-REASSIGN-ON-COMPLETE", 1L);

            // Wait until manager processes TASK_COMPLETE
            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(task.getStatus()).isEqualTo(Task.Status.COMPLETED);
                assertThat(manager.getCnpEngine().getAuctionSession("TASK-NO-REASSIGN-ON-COMPLETE")).isNull();
            });

            // Now contractor crashes / shuts down
            contractor.stop();

            // Manager detects contractor disconnect
            await().atMost(4, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(manager.getActivePeerCount()).isEqualTo(0);
            });

            // Crucial assertion: Manager must NOT re-announce the completed task!
            // Assert during 1 second that no delayed re-announcement occurs
            await().during(1, TimeUnit.SECONDS)
                    .atMost(2, TimeUnit.SECONDS)
                    .until(() -> manager.getCnpEngine().getAuctionSession("TASK-NO-REASSIGN-ON-COMPLETE") == null);

            assertThat(task.getStatus()).isEqualTo(Task.Status.COMPLETED);

        } finally {
            try { manager.stop(); } catch (Exception ignored) {}
            try { contractor.stop(); } catch (Exception ignored) {}
        }
    }
}
