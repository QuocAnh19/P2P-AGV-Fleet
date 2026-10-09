package com.agv.fleet.distributed.auction;

import com.agv.fleet.core.AgvNode;
import com.agv.fleet.core.agent.AgentState;
import com.agv.fleet.core.agent.Position2D;
import com.agv.fleet.core.agent.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Contract Net Protocol (CNP) Distributed Auction Integration Tests")
class CnpAuctionTest {

    private static final Logger log = LoggerFactory.getLogger(CnpAuctionTest.class);

    private final List<AgvNode> nodes = new ArrayList<>();
    private int multicastPort;

    @BeforeEach
    void setUp() {
        multicastPort = ThreadLocalRandom.current().nextInt(20001, 30000);
    }

    @AfterEach
    void tearDown() {
        for (AgvNode node : nodes) {
            if (node != null) {
                try {
                    node.stop();
                } catch (Exception ignored) {
                }
            }
        }
        nodes.clear();
    }

    private AgvNode createNode(long nodeId, int tcpPort, double battery, Position2D pos, long auctionTimeoutMs) {
        AgvNode node = AgvNode.builder()
                .nodeId(nodeId)
                .host("127.0.0.1")
                .tcpPort(tcpPort)
                .multicastPort(multicastPort)
                .discoveryIntervalMs(120)
                .heartbeatIntervalMs(150)
                .heartbeatTimeoutMs(800)
                .initialBattery(battery)
                .initialPosition(pos)
                .auctionTimeoutMs(auctionTimeoutMs)
                .build();
        nodes.add(node);
        return node;
    }

    @Test
    @DisplayName("1. Adaptive Auction - Battery Penalty (<15%) forces LOW_BATTERY_CHARGING and distant node wins")
    void testAdaptiveAuctionBatteryPenalty() throws Exception {
        log.info("--- Starting testAdaptiveAuctionBatteryPenalty ---");

        // Node 1: Announcer / Manager
        AgvNode manager = createNode(1L, 9201, 100.0, new Position2D(0, 0), 400);

        // Node 2: Closer (dist = 4m) but critical battery (10% <= 15%)
        AgvNode node2 = createNode(2L, 9202, 10.0, new Position2D(4, 0), 400);

        // Node 3: Farther (dist = 8m) but healthy battery (90%)
        AgvNode node3 = createNode(3L, 9203, 90.0, new Position2D(8, 0), 400);

        manager.start();
        node2.start();
        node3.start();

        // Wait until all 3 nodes connect to each other in mesh
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(manager.getActivePeerCount()).isEqualTo(2);
            assertThat(node2.getActivePeerCount()).isEqualTo(2);
            assertThat(node3.getActivePeerCount()).isEqualTo(2);
        });

        AtomicReference<Task> assignedTaskRef = new AtomicReference<>();
        node3.addAssignmentListener(assignedTaskRef::set);

        // Manager announces task with pickup at (0, 0)
        Task task = new Task("TASK-BATTERY-1", 0.0, 0.0, 50.0, 50.0);
        AuctionSession session = manager.announceTask(task);

        // Await auction completion
        await().atMost(4, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(session.isClosed()).isTrue();
            // Node 2 must refuse bidding and enter LOW_BATTERY_CHARGING
            assertThat(node2.getAgentState()).isEqualTo(AgentState.LOW_BATTERY_CHARGING);
            // Node 3 must win and transition to BUSY
            assertThat(node3.getAgentState()).isEqualTo(AgentState.BUSY);
            // Session must have awarded task to Node 3
            assertThat(session.getTask().getStatus()).isEqualTo(Task.Status.ASSIGNED);
            assertThat(session.getTask().getAssignedNodeId()).isEqualTo(3L);
        });

        assertThat(assignedTaskRef.get()).isNotNull();
        assertThat(assignedTaskRef.get().getTaskId()).isEqualTo("TASK-BATTERY-1");

        log.info("Adaptive auction battery penalty successfully verified! Node 2 charging, Node 3 won.");
    }

    @Test
    @DisplayName("2. Multi-Bidder Cost Function - Evaluates Distance and Battery to select Min-Cost Winner")
    void testMultipleBiddersSelectsMinCost() throws Exception {
        log.info("--- Starting testMultipleBiddersSelectsMinCost ---");

        // Node 1: Announcer
        AgvNode manager = createNode(1L, 9211, 100.0, new Position2D(0, 0), 400);

        // Node 2: dist = 2m, battery = 90% -> cost = 0.5*2 + 0.3*10 + 0.2*1 = 4.2 (BEST)
        AgvNode node2 = createNode(2L, 9212, 90.0, new Position2D(2, 0), 400);

        // Node 3: dist = 10m, battery = 90% -> cost = 0.5*10 + 0.3*10 + 0.2*1 = 8.2
        AgvNode node3 = createNode(3L, 9213, 90.0, new Position2D(10, 0), 400);

        // Node 4: dist = 2m, battery = 30% -> cost = 0.5*2 + 0.3*70 + 0.2*1 = 22.2
        AgvNode node4 = createNode(4L, 9214, 30.0, new Position2D(2, 0), 400);

        manager.start();
        node2.start();
        node3.start();
        node4.start();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(manager.getActivePeerCount()).isEqualTo(3);
            assertThat(node2.getActivePeerCount()).isEqualTo(3);
            assertThat(node3.getActivePeerCount()).isEqualTo(3);
            assertThat(node4.getActivePeerCount()).isEqualTo(3);
        });

        Task task = new Task("TASK-MINCOST-1", 0.0, 0.0, 20.0, 20.0);
        AuctionSession session = manager.announceTask(task);

        await().atMost(4, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(session.isClosed()).isTrue();
            // Winner Node 2 has lowest cost -> BUSY
            assertThat(node2.getAgentState()).isEqualTo(AgentState.BUSY);
            // Non-winners Node 3 and Node 4 revert to IDLE
            assertThat(node3.getAgentState()).isEqualTo(AgentState.IDLE);
            assertThat(node4.getAgentState()).isEqualTo(AgentState.IDLE);
            // Assigned to Node 2
            assertThat(session.getTask().getAssignedNodeId()).isEqualTo(2L);
        });

        log.info("Multi-bidder min-cost selection verified! Winner: Node 2");
    }

    @Test
    @DisplayName("3. Busy AGV ignores Task Announcement")
    void testBusyAgvIgnoresAuction() throws Exception {
        log.info("--- Starting testBusyAgvIgnoresAuction ---");

        AgvNode manager = createNode(1L, 9221, 100.0, new Position2D(0, 0), 300);
        AgvNode node2 = createNode(2L, 9222, 100.0, new Position2D(1, 0), 300);

        // Set Node 2 to BUSY prior to auction
        node2.setAgentState(AgentState.BUSY);

        manager.start();
        node2.start();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(manager.getActivePeerCount()).isEqualTo(1);
            assertThat(node2.getActivePeerCount()).isEqualTo(1);
        });

        Task task = new Task("TASK-BUSY-TEST", 0.0, 0.0, 10.0, 10.0);
        AuctionSession session = manager.announceTask(task);

        await().atMost(4, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(session.isClosed()).isTrue();
            // Node 2 remains BUSY
            assertThat(node2.getAgentState()).isEqualTo(AgentState.BUSY);
            // Auction has 0 bids and reverts to UNASSIGNED
            assertThat(session.getTask().getStatus()).isEqualTo(Task.Status.UNASSIGNED);
        });

        log.info("Busy AGV successfully ignored auction!");
    }

    @Test
    @DisplayName("4. Fault-Tolerance - Re-announce Task upon Contractor Crash")
    void testContractorCrashReAnnouncesTask() throws Exception {
        log.info("--- Starting testContractorCrashReAnnouncesTask ---");

        AgvNode manager = createNode(1L, 9231, 100.0, new Position2D(0, 0), 400);
        AgvNode contractor = createNode(2L, 9232, 100.0, new Position2D(1, 0), 400);

        manager.start();
        contractor.start();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(manager.getActivePeerCount()).isEqualTo(1);
            assertThat(contractor.getActivePeerCount()).isEqualTo(1);
        });

        Task task = new Task("TASK-CRASH-REASSIGN", 0.0, 0.0, 10.0, 10.0);
        AuctionSession session = manager.announceTask(task);

        await().atMost(4, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(session.isClosed()).isTrue();
            assertThat(contractor.getAgentState()).isEqualTo(AgentState.BUSY);
            assertThat(session.getTask().getStatus()).isEqualTo(Task.Status.ASSIGNED);
            assertThat(session.getTask().getAssignedNodeId()).isEqualTo(2L);
        });

        log.info("Task assigned to contractor Node 2. Simulating crash of Node 2...");

        // Simulate crash of contractor
        contractor.stop();

        // Manager detects peer disconnect and triggers onPeerDropped -> task re-announced
        await().atMost(4, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(manager.getActivePeerCount()).isEqualTo(0);
            AuctionSession newSession = manager.getCnpEngine().getAuctionSession("TASK-CRASH-REASSIGN");
            assertThat(newSession).isNotNull();
            assertThat(newSession.getTask().getStatus()).isIn(Task.Status.AUCTIONING, Task.Status.UNASSIGNED);
        });

        log.info("Contractor crash fault-tolerance verified! Task re-announced.");
    }
}
