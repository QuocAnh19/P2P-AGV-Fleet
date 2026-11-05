package com.agv.fleet.ui.metrics;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enterprise Fleet Metrics Collector.
 * <p>
 * Decoupled from JavaFX UI thread for high-frequency physics/network sampling.
 * Tracks:
 * <ul>
 *   <li>Ground-truth AGV collision events</li>
 *   <li>Task completion & failure counts</li>
 *   <li>Ricart-Agrawala Mutex wait times & running averages</li>
 *   <li>CNP auction counts & bid submissions</li>
 *   <li>Network message throughput (messages/sec)</li>
 *   <li>Network latency distribution (p50, p95 via diagnostic PING/PONG)</li>
 *   <li>Active / alive peer counts</li>
 * </ul>
 */
public class FleetMetricsCollector {

    private static final DateTimeFormatter CSV_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AtomicInteger groundTruthCollisions = new AtomicInteger(0);
    private final AtomicInteger tasksCompleted = new AtomicInteger(0);
    private final AtomicInteger tasksFailed = new AtomicInteger(0);

    private final ConcurrentLinkedQueue<Long> mutexWaitTimesMs = new ConcurrentLinkedQueue<>();
    private final AtomicLong totalMutexWaitTimeMs = new AtomicLong(0);
    private final AtomicInteger totalMutexAcquisitions = new AtomicInteger(0);

    private final AtomicInteger totalAuctionsAnnounced = new AtomicInteger(0);
    private final AtomicInteger totalBidsSubmitted = new AtomicInteger(0);

    private final AtomicLong totalMessagesProcessed = new AtomicLong(0);
    private final AtomicLong lastSampledMessages = new AtomicLong(0);
    private volatile double currentMsgRatePerSecond = 0.0;
    private long lastThroughputSampleTimeNanos = System.nanoTime();

    private final ConcurrentLinkedQueue<Double> latencySamplesMs = new ConcurrentLinkedQueue<>();
    private volatile double p50LatencyMs = 0.0;
    private volatile double p95LatencyMs = 0.0;
    private volatile int alivePeersCount = 0;

    public void recordCollision() {
        groundTruthCollisions.incrementAndGet();
    }

    public int getGroundTruthCollisions() {
        return groundTruthCollisions.get();
    }

    public void recordTaskCompleted() {
        tasksCompleted.incrementAndGet();
    }

    public int getTasksCompleted() {
        return tasksCompleted.get();
    }

    public void recordTaskFailed() {
        tasksFailed.incrementAndGet();
    }

    public int getTasksFailed() {
        return tasksFailed.get();
    }

    public void recordMutexWait(long waitTimeMs) {
        if (waitTimeMs < 0) waitTimeMs = 0;
        mutexWaitTimesMs.add(waitTimeMs);
        totalMutexWaitTimeMs.addAndGet(waitTimeMs);
        totalMutexAcquisitions.incrementAndGet();
    }

    public double getAverageMutexWaitTimeMs() {
        int count = totalMutexAcquisitions.get();
        if (count == 0) return 0.0;
        return (double) totalMutexWaitTimeMs.get() / count;
    }

    public int getTotalMutexAcquisitions() {
        return totalMutexAcquisitions.get();
    }

    public void recordAuctionAnnounced() {
        totalAuctionsAnnounced.incrementAndGet();
    }

    public int getTotalAuctionsAnnounced() {
        return totalAuctionsAnnounced.get();
    }

    public void recordBidSubmitted() {
        totalBidsSubmitted.incrementAndGet();
    }

    public int getTotalBidsSubmitted() {
        return totalBidsSubmitted.get();
    }

    public void recordMessageProcessed() {
        totalMessagesProcessed.incrementAndGet();
    }

    public long getTotalMessagesProcessed() {
        return totalMessagesProcessed.get();
    }

    public double getMessagesPerSecond() {
        return currentMsgRatePerSecond;
    }

    public void recordLatencySample(double rttMs) {
        if (rttMs >= 0) {
            latencySamplesMs.add(rttMs);
            // Keep at most 200 recent latency samples
            while (latencySamplesMs.size() > 200) {
                latencySamplesMs.poll();
            }
        }
    }

    public double getP50LatencyMs() {
        return p50LatencyMs;
    }

    public double getP95LatencyMs() {
        return p95LatencyMs;
    }

    public void setAlivePeersCount(int alivePeersCount) {
        this.alivePeersCount = alivePeersCount;
    }

    public int getAlivePeersCount() {
        return alivePeersCount;
    }

    /**
     * Periodic 1-second tick to recalculate message throughput rates and latency percentiles.
     */
    public synchronized void tickPeriodicSample() {
        long nowNanos = System.nanoTime();
        long elapsedNanos = nowNanos - lastThroughputSampleTimeNanos;
        if (elapsedNanos > 0) {
            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
            long currentTotal = totalMessagesProcessed.get();
            long delta = currentTotal - lastSampledMessages.get();
            lastSampledMessages.set(currentTotal);
            currentMsgRatePerSecond = delta / elapsedSeconds;
            lastThroughputSampleTimeNanos = nowNanos;
        }

        // Calculate percentiles
        List<Double> samples = new ArrayList<>(latencySamplesMs);
        if (!samples.isEmpty()) {
            Collections.sort(samples);
            int p50Index = (int) Math.round((samples.size() - 1) * 0.50);
            int p95Index = (int) Math.round((samples.size() - 1) * 0.95);
            p50LatencyMs = samples.get(p50Index);
            p95LatencyMs = samples.get(p95Index);
        }
    }

    public void reset() {
        groundTruthCollisions.set(0);
        tasksCompleted.set(0);
        tasksFailed.set(0);
        mutexWaitTimesMs.clear();
        totalMutexWaitTimeMs.set(0);
        totalMutexAcquisitions.set(0);
        totalAuctionsAnnounced.set(0);
        totalBidsSubmitted.set(0);
        totalMessagesProcessed.set(0);
        lastSampledMessages.set(0);
        currentMsgRatePerSecond = 0.0;
        latencySamplesMs.clear();
        p50LatencyMs = 0.0;
        p95LatencyMs = 0.0;
    }

    public String toCsvString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Timestamp,Collisions,TasksCompleted,TasksFailed,AvgMutexWaitMs,TotalAuctions,TotalBids,TotalMsgs,MsgsPerSec,LatencyP50Ms,LatencyP95Ms,AlivePeers\n");
        sb.append(String.format(Locale.US, "%s,%d,%d,%d,%.2f,%d,%d,%d,%.2f,%.2f,%.2f,%d\n",
                LocalDateTime.now().format(CSV_TIME_FMT),
                getGroundTruthCollisions(),
                getTasksCompleted(),
                getTasksFailed(),
                getAverageMutexWaitTimeMs(),
                getTotalAuctionsAnnounced(),
                getTotalBidsSubmitted(),
                getTotalMessagesProcessed(),
                getMessagesPerSecond(),
                getP50LatencyMs(),
                getP95LatencyMs(),
                getAlivePeersCount()
        ));
        return sb.toString();
    }

    public void exportCsv(File file) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file, false))) {
            writer.print(toCsvString());
        }
    }
}
