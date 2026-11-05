package com.agv.fleet.cli;

import com.agv.fleet.core.agent.AgentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

public class NodeCliTest {

    private NodeCli nodeCli1;
    private NodeCli nodeCli2;

    @AfterEach
    public void tearDown() {
        if (nodeCli1 != null) {
            nodeCli1.stop();
        }
        if (nodeCli2 != null) {
            nodeCli2.stop();
        }
    }

    @Test
    public void testCliConfigParsing() {
        String[] args = {
                "--id", "42",
                "--port", "29500",
                "--iface", "test-iface",
                "--multicast-group", "239.255.10.20:25000",
                "--host", "127.0.0.1",
                "--battery", "85.5",
                "--x", "12.0",
                "--y", "8.0"
        };

        NodeCli.Config cfg = NodeCli.Config.parse(args);

        assertEquals(42L, cfg.nodeId);
        assertEquals(29500, cfg.port);
        assertEquals("test-iface", cfg.iface);
        assertEquals("239.255.10.20", cfg.multicastGroup);
        assertEquals(25000, cfg.multicastPort);
        assertEquals("127.0.0.1", cfg.host);
        assertEquals(85.5, cfg.battery, 0.001);
        assertEquals(12.0, cfg.x, 0.001);
        assertEquals(8.0, cfg.y, 0.001);
        assertFalse(cfg.showHelp);
    }

    @Test
    public void testCliHelpFlag() {
        String[] args = {"--help"};
        NodeCli.Config cfg = NodeCli.Config.parse(args);
        assertTrue(cfg.showHelp);
    }

    @Test
    public void testCliLifecycleAndCommands() throws Exception {
        nodeCli1 = new NodeCli(1, 0, null, "239.255.0.1", 21500, "127.0.0.1", 100.0, 1.0, 1.0);
        nodeCli1.start();
        assertTrue(nodeCli1.isRunning());
        assertNotNull(nodeCli1.getAgvNode());

        // Test commands
        nodeCli1.handleCommand("status");
        nodeCli1.handleCommand("peers");
        nodeCli1.handleCommand("help");
        nodeCli1.handleCommand("unknown-command");

        // Mutex command without peers immediately succeeds
        nodeCli1.handleCommand("mutex 5 5");
        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> nodeCli1.getAgvNode().getMutexState("(5, 5)") != com.agv.fleet.distributed.mutex.MutexState.WANTED);

        nodeCli1.handleCommand("release (5, 5)");
        assertEquals(com.agv.fleet.distributed.mutex.MutexState.RELEASED, nodeCli1.getAgvNode().getMutexState("(5, 5)"));

        nodeCli1.stop();
        assertFalse(nodeCli1.isRunning());
    }

    @Test
    public void testTwoNodesMeshAndAuctionViaCli() throws Exception {
        int mPort = 21550;
        nodeCli1 = new NodeCli(1, 0, null, "239.255.0.1", mPort, "127.0.0.1", 90.0, 0.0, 0.0);
        nodeCli2 = new NodeCli(2, 0, null, "239.255.0.1", mPort, "127.0.0.1", 80.0, 5.0, 5.0);

        nodeCli1.start();
        nodeCli2.start();

        // Wait for UDP peer discovery and TCP connection establishment
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> nodeCli1.getAgvNode().getActivePeerCount() >= 1 &&
                             nodeCli2.getAgvNode().getActivePeerCount() >= 1);

        // Ping peer 2 from node 1
        AtomicBoolean pingSuccess = new AtomicBoolean(false);
        nodeCli1.getAgvNode().ping(2).whenComplete((rtt, err) -> {
            if (err == null && rtt > 0) {
                pingSuccess.set(true);
            }
        });
        await().atMost(3, TimeUnit.SECONDS).untilTrue(pingSuccess);

        // Test announcing task from Node 1 via command
        nodeCli1.handleCommand("announce TASK-CLI-1 2.0 2.0 8.0 8.0");

        // Node 2 should bid and win
        await().atMost(3, TimeUnit.SECONDS)
                .until(() -> nodeCli2.getAgvNode().getAgentState() == AgentState.BUSY);

        nodeCli1.stop();
        nodeCli2.stop();
    }
}
