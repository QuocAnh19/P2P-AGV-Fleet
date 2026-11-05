package com.agv.fleet.ui.traffic;

import com.agv.fleet.common.protocol.Opcode;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * Thread-safe log buffer maintaining up to 2000 wire messages.
 * Supports filtering by Opcode and Node ID, pausing, and exporting to CSV.
 */
public class WireTrafficLog {

    public static final int MAX_CAPACITY = 2000;

    private final Deque<WireMessageEntry> entries = new ConcurrentLinkedDeque<>();
    private volatile boolean paused = false;

    public void addEntry(WireMessageEntry entry) {
        if (paused || entry == null) return;

        entries.addLast(entry);
        while (entries.size() > MAX_CAPACITY) {
            entries.pollFirst();
        }
    }

    public List<WireMessageEntry> getAllEntries() {
        return new ArrayList<>(entries);
    }

    public List<WireMessageEntry> getFilteredEntries(Opcode opcodeFilter, Long nodeFilter) {
        return entries.stream()
                .filter(e -> {
                    if (opcodeFilter != null && e.opcode() != opcodeFilter) {
                        return false;
                    }
                    if (nodeFilter != null && nodeFilter > 0) {
                        return e.senderNodeId() == nodeFilter || e.targetNodeId() == nodeFilter;
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isPaused() {
        return paused;
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    public String toCsvString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Time,Lamport,Opcode,SenderNodeId,TargetNodeId,Detail\n");
        for (WireMessageEntry entry : entries) {
            sb.append(entry.toCsvLine()).append("\n");
        }
        return sb.toString();
    }

    public void exportCsv(File file) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file, false))) {
            writer.print(toCsvString());
        }
    }
}
