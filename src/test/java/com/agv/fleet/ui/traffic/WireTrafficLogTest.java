package com.agv.fleet.ui.traffic;

import com.agv.fleet.common.protocol.Opcode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class WireTrafficLogTest {

    private WireTrafficLog log;

    @BeforeEach
    public void setUp() {
        log = new WireTrafficLog();
    }

    @Test
    public void testAddAndCapCapacity() {
        for (int i = 0; i < 2100; i++) {
            log.addEntry(WireMessageEntry.of(i, Opcode.HEARTBEAT, 1, -1, "tick=" + i));
        }

        assertEquals(WireTrafficLog.MAX_CAPACITY, log.size());
        assertEquals(2000, log.getAllEntries().size());
        // Oldest entry should be index 100
        assertEquals(100, log.getAllEntries().get(0).lamportTimestamp());
    }

    @Test
    public void testFilteringByOpcodeAndNode() {
        log.addEntry(WireMessageEntry.of(1, Opcode.MUTEX_REQUEST, 1, -1, "(4, 4)"));
        log.addEntry(WireMessageEntry.of(2, Opcode.TASK_ANNOUNCE, 2, -1, "TASK-1"));
        log.addEntry(WireMessageEntry.of(3, Opcode.TASK_BID, 3, 2, "cost=12.5"));
        log.addEntry(WireMessageEntry.of(4, Opcode.MUTEX_RELEASE, 1, -1, "(4, 4)"));

        // Filter by MUTEX_REQUEST
        List<WireMessageEntry> mutexReqs = log.getFilteredEntries(Opcode.MUTEX_REQUEST, null);
        assertEquals(1, mutexReqs.size());
        assertEquals(Opcode.MUTEX_REQUEST, mutexReqs.get(0).opcode());

        // Filter by Node 2 (either as sender or target)
        List<WireMessageEntry> node2Messages = log.getFilteredEntries(null, 2L);
        assertEquals(2, node2Messages.size());

        // Filter by both Opcode TASK_BID and Node 3
        List<WireMessageEntry> bidByNode3 = log.getFilteredEntries(Opcode.TASK_BID, 3L);
        assertEquals(1, bidByNode3.size());
    }

    @Test
    public void testPauseAndResume() {
        log.addEntry(WireMessageEntry.of(1, Opcode.PING, 1, 2, ""));
        assertEquals(1, log.size());

        log.setPaused(true);
        assertTrue(log.isPaused());
        log.addEntry(WireMessageEntry.of(2, Opcode.PONG, 2, 1, ""));
        assertEquals(1, log.size()); // Ignored while paused

        log.setPaused(false);
        assertFalse(log.isPaused());
        log.addEntry(WireMessageEntry.of(3, Opcode.PONG, 2, 1, ""));
        assertEquals(2, log.size());
    }

    @Test
    public void testExportCsv() throws IOException {
        log.addEntry(WireMessageEntry.of(10, Opcode.MUTEX_REQUEST, 1, -1, "Cell (5, 5)"));
        log.addEntry(WireMessageEntry.of(11, Opcode.MUTEX_REPLY, 2, 1, "Grant"));

        File tmpFile = File.createTempFile("traffic_log_", ".csv");
        tmpFile.deleteOnExit();
        log.exportCsv(tmpFile);

        String content = Files.readString(tmpFile.toPath());
        assertTrue(content.contains("Time,Lamport,Opcode,SenderNodeId,TargetNodeId,Detail"));
        assertTrue(content.contains("MUTEX_REQUEST,1,BROADCAST,\"Cell (5, 5)\""));
        assertTrue(content.contains("MUTEX_REPLY,2,1,\"Grant\""));
    }
}
