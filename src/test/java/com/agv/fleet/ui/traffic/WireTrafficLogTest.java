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
        for (int i = 0; i < 5100; i++) {
            log.addEntry(WireMessageEntry.of(i, Opcode.HEARTBEAT, 1, 2, "tick=" + i));
        }

        assertEquals(WireTrafficLog.MAX_CAPACITY, log.size());
        assertEquals(5000, log.getAllEntries().size());
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
    public void testTwoTierSeparation() {
        // Business entries
        log.addEntry(WireMessageEntry.of(1, Opcode.MUTEX_REQUEST, 1, 2, "Cell (5, 5)"));
        log.addEntry(WireMessageEntry.of(2, Opcode.TASK_ANNOUNCE, 1, 2, "Task 1"));

        // System entries
        log.addEntry(WireMessageEntry.of(3, Opcode.HEARTBEAT, 1, 2, "hb"));
        log.addEntry(WireMessageEntry.of(4, Opcode.POSITION_UPDATE, 1, 2, "pos"));

        assertEquals(4, log.size());
        assertEquals(2, log.businessSize());

        // Default view: business only
        List<WireMessageEntry> businessOnly = log.getFilteredEntries(null, null, false, null);
        assertEquals(2, businessOnly.size());

        // System enabled view: all
        List<WireMessageEntry> all = log.getFilteredEntries(null, null, true, null);
        assertEquals(4, all.size());
    }

    @Test
    public void testBroadcastGroupingAndDeduplication() {
        // First broadcast reception from Node 1
        WireMessageEntry b1 = WireMessageEntry.ofBroadcast(10, Opcode.MUTEX_REQUEST, 1, 4, "Intersection (5, 5)", null);
        log.addEntry(b1);
        assertEquals(1, log.size());
        assertEquals("Node 1 -> ALL (4 peers)", b1.getRouteString());

        // Duplicate broadcast reception from 3 other peers with same (sender=1, lamport=10, opcode=MUTEX_REQUEST)
        WireMessageEntry dup1 = WireMessageEntry.ofBroadcast(10, Opcode.MUTEX_REQUEST, 1, 4, "Intersection (5, 5)", null);
        WireMessageEntry dup2 = WireMessageEntry.ofBroadcast(10, Opcode.MUTEX_REQUEST, 1, 4, "Intersection (5, 5)", null);
        WireMessageEntry dup3 = WireMessageEntry.ofBroadcast(10, Opcode.MUTEX_REQUEST, 1, 4, "Intersection (5, 5)", null);

        log.addEntry(dup1);
        log.addEntry(dup2);
        log.addEntry(dup3);

        // Size must still be 1 (grouped/deduplicated)
        assertEquals(1, log.size());
    }

    @Test
    public void testSearchTextFilter() {
        log.addEntry(WireMessageEntry.of(1, Opcode.MUTEX_REQUEST, 1, 2, "Critical Crossing A"));
        log.addEntry(WireMessageEntry.of(2, Opcode.TASK_ANNOUNCE, 2, 3, "Warehouse Zone B"));

        List<WireMessageEntry> searchCrossing = log.getFilteredEntries(null, null, true, "crossing");
        assertEquals(1, searchCrossing.size());
        assertEquals("Critical Crossing A", searchCrossing.get(0).detail());

        List<WireMessageEntry> searchZone = log.getFilteredEntries(null, null, true, "ZONE B");
        assertEquals(1, searchZone.size());
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
