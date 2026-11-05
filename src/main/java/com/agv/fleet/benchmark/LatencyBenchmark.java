package com.agv.fleet.benchmark;

import com.agv.fleet.core.AgvNode;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Latency Benchmark measuring Round-Trip Time (RTT) and One-Way Network Latency
 * between two real Netty AGV nodes communicating over TCP loopback (127.0.0.1).
 * <p>
 * Protocol: PING (0x04) carrying 8-byte nanoTime, echoed by peer via PONG (0x05).
 * One-way latency is derived as RTT / 2.
 */
public class LatencyBenchmark {

    private static final int DEFAULT_WARMUP_ROUNDS = 2000;
    private static final int DEFAULT_MEASURE_ROUNDS = 20000;

    public static void main(String[] args) throws Exception {
        int warmup = DEFAULT_WARMUP_ROUNDS;
        int rounds = DEFAULT_MEASURE_ROUNDS;

        if (args.length >= 2) {
            try {
                warmup = Integer.parseInt(args[0]);
                rounds = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {}
        }

        runBenchmark(warmup, rounds);
    }

    public static BenchmarkResult runBenchmark(int warmupRounds, int measureRounds) throws Exception {
        BenchmarkUtils.printSystemInfoBanner();
        System.out.printf("[LatencyBenchmark] Starting 2 physical Netty nodes on 127.0.0.1...%n");

        int multicastPort = ThreadLocalRandom.current().nextInt(20000, 30000);
        int tcpPort1 = ThreadLocalRandom.current().nextInt(30000, 40000);
        int tcpPort2 = tcpPort1 + 1;

        AgvNode node1 = AgvNode.builder()
                .nodeId(1L)
                .tcpPort(tcpPort1)
                .multicastPort(multicastPort)
                .heartbeatIntervalMs(500)
                .heartbeatTimeoutMs(3000)
                .build();

        AgvNode node2 = AgvNode.builder()
                .nodeId(2L)
                .tcpPort(tcpPort2)
                .multicastPort(multicastPort)
                .heartbeatIntervalMs(500)
                .heartbeatTimeoutMs(3000)
                .build();

        try {
            node1.start();
            node2.start();

            // Await peer mesh connection
            long waitStart = System.currentTimeMillis();
            while (node1.getActivePeerCount() == 0 || node2.getActivePeerCount() == 0) {
                if (System.currentTimeMillis() - waitStart > 5000) {
                    throw new IllegalStateException("Timeout establishing P2P connection between Node 1 and Node 2");
                }
                Thread.onSpinWait();
            }
            System.out.println("[LatencyBenchmark] P2P Mesh established! Active peers: " + node1.getActivePeerCount());

            // 1. Warm-up Phase
            System.out.printf("[LatencyBenchmark] Warming up JIT and Netty pipelines with %,d rounds...%n", warmupRounds);
            for (int i = 0; i < warmupRounds; i++) {
                node1.ping(2L).get(2, TimeUnit.SECONDS);
            }
            System.out.println("[LatencyBenchmark] Warm-up completed!");

            // 2. Measurement Phase
            System.out.printf("[LatencyBenchmark] Measuring %,d consecutive RTT samples...%n", measureRounds);
            long[] rttSamplesNanos = new long[measureRounds];
            long benchStartNanos = System.nanoTime();

            for (int i = 0; i < measureRounds; i++) {
                rttSamplesNanos[i] = node1.ping(2L).get(2, TimeUnit.SECONDS);
            }
            long totalBenchDurationNanos = System.nanoTime() - benchStartNanos;

            // 3. Statistical Calculations
            Arrays.sort(rttSamplesNanos);

            long minNanos = rttSamplesNanos[0];
            long maxNanos = rttSamplesNanos[measureRounds - 1];
            long p50Nanos = rttSamplesNanos[(int) (measureRounds * 0.50)];
            long p90Nanos = rttSamplesNanos[(int) (measureRounds * 0.90)];
            long p95Nanos = rttSamplesNanos[(int) (measureRounds * 0.95)];
            long p99Nanos = rttSamplesNanos[(int) (measureRounds * 0.99)];
            long p999Nanos = rttSamplesNanos[(int) (measureRounds * 0.999)];

            long sumNanos = 0;
            for (long sample : rttSamplesNanos) {
                sumNanos += sample;
            }
            double avgNanos = (double) sumNanos / measureRounds;

            double opsPerSec = (double) measureRounds / (totalBenchDurationNanos / 1_000_000_000.0);

            // 4. Console Output
            List<String> headers = List.of("Percentile / Metric", "RTT (ms)", "One-Way Latency (RTT/2) (ms)", "RTT (µs)", "One-Way (µs)");
            List<List<String>> rows = new ArrayList<>();
            rows.add(List.of("Min", formatMs(minNanos), formatMs(minNanos / 2), formatUs(minNanos), formatUs(minNanos / 2)));
            rows.add(List.of("Average", formatMs(avgNanos), formatMs(avgNanos / 2), formatUs(avgNanos), formatUs(avgNanos / 2)));
            rows.add(List.of("p50 (Median)", formatMs(p50Nanos), formatMs(p50Nanos / 2), formatUs(p50Nanos), formatUs(p50Nanos / 2)));
            rows.add(List.of("p90", formatMs(p90Nanos), formatMs(p90Nanos / 2), formatUs(p90Nanos), formatUs(p90Nanos / 2)));
            rows.add(List.of("p95", formatMs(p95Nanos), formatMs(p95Nanos / 2), formatUs(p95Nanos), formatUs(p95Nanos / 2)));
            rows.add(List.of("p99", formatMs(p99Nanos), formatMs(p99Nanos / 2), formatUs(p99Nanos), formatUs(p99Nanos / 2)));
            rows.add(List.of("p99.9", formatMs(p999Nanos), formatMs(p999Nanos / 2), formatUs(p999Nanos), formatUs(p999Nanos / 2)));
            rows.add(List.of("Max", formatMs(maxNanos), formatMs(maxNanos / 2), formatUs(maxNanos), formatUs(maxNanos / 2)));

            BenchmarkUtils.printTable("P2P RTT & One-Way Latency Benchmark (" + measureRounds + " rounds)", headers, rows);
            System.out.printf("Throughput: %,.1f RTT ops/sec (Total duration: %.2f s)%n", opsPerSec, totalBenchDurationNanos / 1e9);

            // 5. Persist to CSV
            List<String> csvHeaders = List.of("Metric", "RTT_ms", "OneWay_ms", "RTT_us", "OneWay_us");
            File csv = BenchmarkUtils.writeCsv("latency_benchmark.csv", csvHeaders, rows);
            System.out.println("Benchmark results saved to: " + csv.getAbsolutePath());

            return new BenchmarkResult(p50Nanos, p95Nanos, p99Nanos, maxNanos, avgNanos);
        } finally {
            try { node1.stop(); } catch (Exception ignored) {}
            try { node2.stop(); } catch (Exception ignored) {}
        }
    }

    private static String formatMs(double nanos) {
        return String.format("%.4f", nanos / 1_000_000.0);
    }

    private static String formatUs(double nanos) {
        return String.format("%.2f", nanos / 1_000.0);
    }

    public record BenchmarkResult(long p50Nanos, long p95Nanos, long p99Nanos, long maxNanos, double avgNanos) {}
}
