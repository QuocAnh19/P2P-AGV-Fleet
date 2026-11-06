package com.agv.fleet.ui.traffic;

import com.agv.fleet.common.protocol.Opcode;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Enterprise Two-Tier Wire Traffic Log Buffer.
 * <p>
 * Maintains up to 5,000 entries with:
 * <ul>
 *   <li><b>Business Tier:</b> MUTEX_REQUEST/REPLY/RELEASE, TASK_ANNOUNCE/BID/AWARD/COMPLETE (default view).</li>
 *   <li><b>System Tier:</b> HEARTBEAT, POSITION_UPDATE, PING/PONG (rate counters &amp; optional view).</li>
 *   <li><b>Broadcast Grouping &amp; Deduplication:</b> Prevents N identical broadcast lines per event.</li>
 *   <li><b>Text search &amp; Opcode filtering.</b></li>
 * </ul>
 */
public class WireTrafficLog {

    public static final int MAX_CAPACITY = 5000;

    private final Deque<WireMessageEntry> allEntries = new ConcurrentLinkedDeque<>();
    private final Deque<WireMessageEntry> businessEntries = new ConcurrentLinkedDeque<>();

    // Broadcast deduplication cache: key -> timestamp ms
    private final Map<String, Long> broadcastDeduplicationCache = new ConcurrentHashMap<>();

    // Opcode counters & rate calculation
    private final Map<Opcode, LongAdder> totalOpcodeCounts = new ConcurrentHashMap<>();
    private final Map<Opcode, Long> lastSecondSnapshot = new ConcurrentHashMap<>();
    private final Map<Opcode, Double> currentRatesPerSec = new ConcurrentHashMap<>();
    private volatile long lastRateCalcTimeMs = System.currentTimeMillis();

    private volatile boolean paused = false;

    public WireTrafficLog() {
        for (Opcode op : Opcode.values()) {
            totalOpcodeCounts.put(op, new LongAdder());
            lastSecondSnapshot.put(op, 0L);
            currentRatesPerSec.put(op, 0.0);
        }
    }

    /**
     * Checks if an opcode belongs to the Business tier.
     */
    public static boolean isBusinessOpcode(Opcode op) {
        if (op == null) return false;
        return switch (op) {
            case MUTEX_REQUEST, MUTEX_REPLY, MUTEX_RELEASE,
                 TASK_ANNOUNCE, TASK_BID, TASK_AWARD, TASK_COMPLETE -> true;
            default -> false;
        };
    }

    /**
     * Adds an entry into the wire log.
     * Deduplicates broadcasts across peers within a 1-second sliding window.
     */
    public void addEntry(WireMessageEntry entry) {
        if (paused || entry == null) return;

        // Count opcode
        if (entry.opcode() != null) {
            totalOpcodeCounts.computeIfAbsent(entry.opcode(), k -> new LongAdder()).increment();
        }

        // Broadcast deduplication: Node X broadcast with same (senderNodeId, lamport, opcode)
        if (entry.isBroadcast()) {
            String dedupKey = entry.senderNodeId() + ":" + entry.lamportTimestamp() + ":" + entry.opcode();
            long now = System.currentTimeMillis();
            Long prevTime = broadcastDeduplicationCache.put(dedupKey, now);
            if (prevTime != null && (now - prevTime) < 1200) {
                // Duplicate broadcast reception from peer; ignore to prevent multi-line spam
                return;
            }

            // Periodic clean-up of stale keys in cache
            if (broadcastDeduplicationCache.size() > 500) {
                broadcastDeduplicationCache.entrySet().removeIf(e -> (now - e.getValue()) > 3000);
            }
        }

        // Add to main buffer
        allEntries.addLast(entry);
        while (allEntries.size() > MAX_CAPACITY) {
            allEntries.pollFirst();
        }

        // Add to business buffer if business tier
        if (entry.isBusinessMessage()) {
            businessEntries.addLast(entry);
            while (businessEntries.size() > MAX_CAPACITY) {
                businessEntries.pollFirst();
            }
        }
    }

    public List<WireMessageEntry> getAllEntries() {
        return new ArrayList<>(allEntries);
    }

    public List<WireMessageEntry> getBusinessEntries() {
        return new ArrayList<>(businessEntries);
    }

    /**
     * Overloaded method for backward-compatibility with tests.
     */
    public List<WireMessageEntry> getFilteredEntries(Opcode opcodeFilter, Long nodeFilter) {
        return getFilteredEntries(opcodeFilter, nodeFilter, true, null);
    }

    /**
     * Comprehensive multi-parameter filter.
     */
    public List<WireMessageEntry> getFilteredEntries(Opcode opcodeFilter, Long nodeFilter, boolean showSystem, String searchText) {
        Collection<WireMessageEntry> source = showSystem ? allEntries : businessEntries;
        String query = (searchText != null && !searchText.isBlank()) ? searchText.trim().toLowerCase(Locale.ROOT) : null;

        return source.stream()
                .filter(e -> {
                    if (opcodeFilter != null && e.opcode() != opcodeFilter) {
                        return false;
                    }
                    if (nodeFilter != null && nodeFilter > 0) {
                        if (e.senderNodeId() != nodeFilter && e.targetNodeId() != nodeFilter) {
                            return false;
                        }
                    }
                    if (query != null) {
                        boolean match = (e.detail() != null && e.detail().toLowerCase(Locale.ROOT).contains(query))
                                || (e.opcode() != null && e.opcode().name().toLowerCase(Locale.ROOT).contains(query))
                                || (e.getRouteString().toLowerCase(Locale.ROOT).contains(query))
                                || (e.timeStr().toLowerCase(Locale.ROOT).contains(query));
                        if (!match) return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * Recalculates messages/sec rates for system monitoring.
     */
    public synchronized void updateRates() {
        long now = System.currentTimeMillis();
        double elapsedSec = (now - lastRateCalcTimeMs) / 1000.0;
        if (elapsedSec < 0.5) return; // Debounce rapid invocations

        for (Opcode op : Opcode.values()) {
            long total = totalOpcodeCounts.computeIfAbsent(op, k -> new LongAdder()).sum();
            long prev = lastSecondSnapshot.getOrDefault(op, 0L);
            double rate = Math.max(0.0, (total - prev) / elapsedSec);
            currentRatesPerSec.put(op, rate);
            lastSecondSnapshot.put(op, total);
        }
        lastRateCalcTimeMs = now;
    }

    public double getRate(Opcode op) {
        return currentRatesPerSec.getOrDefault(op, 0.0);
    }

    public long getTotalCount(Opcode op) {
        LongAdder adder = totalOpcodeCounts.get(op);
        return adder != null ? adder.sum() : 0L;
    }

    public String getSystemRateString() {
        updateRates();
        long hbRate = Math.round(getRate(Opcode.HEARTBEAT));
        long posRate = Math.round(getRate(Opcode.POSITION_UPDATE));
        long pingRate = Math.round(getRate(Opcode.PING) + getRate(Opcode.PONG));
        return String.format(Locale.US, "HEARTBEAT: %d msgs/s | POSITION: %d msgs/s | PING/PONG: %d msgs/s",
                hbRate, posRate, pingRate);
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isPaused() {
        return paused;
    }

    public void clear() {
        allEntries.clear();
        businessEntries.clear();
        broadcastDeduplicationCache.clear();
        for (LongAdder adder : totalOpcodeCounts.values()) {
            adder.reset();
        }
    }

    public int size() {
        return allEntries.size();
    }

    public int businessSize() {
        return businessEntries.size();
    }

    public String toCsvString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Time,Lamport,Opcode,SenderNodeId,TargetNodeId,Detail\n");
        for (WireMessageEntry entry : allEntries) {
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
