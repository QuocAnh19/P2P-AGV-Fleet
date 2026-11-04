package com.agv.fleet.distributed.auction;

import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import com.agv.fleet.common.protocol.payload.TaskAnnouncePayload;
import com.agv.fleet.common.protocol.payload.TaskAwardPayload;
import com.agv.fleet.core.AgvNode;
import com.agv.fleet.core.agent.AgentState;
import com.agv.fleet.core.agent.Position2D;
import com.agv.fleet.core.agent.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class BiddingStuckResolutionTest {

    @Test
    @DisplayName("Bug 4 (a): Zero bids auction broadcasts TASK_AWARD with winnerNodeId = -1 to reset bidders to IDLE")
    void testZeroBidsBroadcastsNegativeAwardAndResetsBidders() throws Exception {
        int multicastPort = 25301;
        AgvNode announcer = AgvNode.builder()
                .nodeId(1L)
                .tcpPort(19301)
                .multicastPort(multicastPort)
                .initialBattery(100.0)
                .initialPosition(new Position2D(0, 0))
                .auctionTimeoutMs(300)
                .build();

        AgvNode bidder = AgvNode.builder()
                .nodeId(2L)
                .tcpPort(19302)
                .multicastPort(multicastPort)
                .initialBattery(100.0)
                .initialPosition(new Position2D(5, 5))
                .auctionTimeoutMs(300)
                .build();

        try {
            announcer.start();
            bidder.start();

            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(announcer.getActivePeerCount()).isEqualTo(1);
                assertThat(bidder.getActivePeerCount()).isEqualTo(1);
            });

            // Simulate bidder having submitted a bid and in BIDDING state
            String taskId = "TASK-ZERO-BIDS-RESET";
            bidder.setAgentState(AgentState.BIDDING);
            bidder.getCnpEngine().getMySubmittedBids().put(taskId, 1L);

            Task task = new Task(taskId, 0.0, 0.0, 10.0, 10.0);
            AuctionSession session = announcer.announceTask(task);

            // Close auction with 0 bids manually or via timeout
            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(session.isClosed()).isTrue();
            });

            // Bidder should receive TASK_AWARD with winnerNodeId = -1 and revert to IDLE
            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(bidder.getAgentState()).isEqualTo(AgentState.IDLE);
                assertThat(bidder.getCnpEngine().getMySubmittedBids().containsKey(taskId)).isFalse();
            });
        } finally {
            try { announcer.stop(); } catch (Exception ignored) {}
            try { bidder.stop(); } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Bug 4 (b): Bidder timer 3x auctionTimeoutMs expires without award and reverts to IDLE")
    void testBidderTimerRevertsToIdleIfNoAwardReceived() throws Exception {
        long timeoutMs = 200;
        AgvNode bidder = AgvNode.builder()
                .nodeId(10L)
                .tcpPort(19310)
                .initialBattery(100.0)
                .initialPosition(new Position2D(0, 0))
                .auctionTimeoutMs(timeoutMs)
                .build();

        try {
            bidder.start();

            // Simulate incoming TASK_ANNOUNCE from non-existent node 999
            TaskAnnouncePayload announce = new TaskAnnouncePayload("TASK-TIMEOUT-TEST", 0.0, 0.0, 10.0, 10.0, 999L);
            AgvMessage announceMsg = AgvMessage.create(
                    Opcode.TASK_ANNOUNCE, 1L, 1L, 999L, announce.toBytes()
            );

            bidder.getCnpEngine().onTaskAnnounceReceived(announceMsg);

            // Bidder should be in BIDDING state
            assertThat(bidder.getAgentState()).isEqualTo(AgentState.BIDDING);

            // Wait for 3x auctionTimeoutMs (600ms) timer to expire
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(bidder.getAgentState()).isEqualTo(AgentState.IDLE);
                assertThat(bidder.getCnpEngine().getMySubmittedBids().containsKey("TASK-TIMEOUT-TEST")).isFalse();
            });
        } finally {
            try { bidder.stop(); } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Bug 4 (c): Task UNASSIGNED retries with backoff and fails after 5 attempts")
    void testUnassignedTaskRetriesAndFailsAfter5Attempts() throws Exception {
        AgvNode announcer = AgvNode.builder()
                .nodeId(20L)
                .tcpPort(19320)
                .initialBattery(100.0)
                .initialPosition(new Position2D(0, 0))
                .auctionTimeoutMs(100) // fast auction
                .build();

        try {
            announcer.start();

            Task task = new Task("TASK-MAX-RETRY-FAIL", 0.0, 0.0, 10.0, 10.0);
            announcer.announceTask(task);

            // Over time, 5 retries should occur with backoff and eventually mark task FAILED
            // Total backoff delay: 500ms + 1000ms + 2000ms + 2000ms + 2000ms = ~7.5s
            await().atMost(12, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(task.getStatus()).isEqualTo(Task.Status.FAILED);
            });
        } finally {
            try { announcer.stop(); } catch (Exception ignored) {}
        }
    }
}
