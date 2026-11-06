package com.agv.fleet.benchmark;

import com.agv.fleet.common.protocol.Opcode;
import com.agv.fleet.common.protocol.payload.PositionUpdatePayload;
import com.agv.fleet.core.AgvNode;
import com.agv.fleet.core.agent.AgentState;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P2P Mesh Network Throughput Benchmark measuring full-mesh broadcasting performance
 * under heavy sustained load across 5 and 10 nodes for 10 seconds each.
 * <p>
 * Metrics recorded:
 * - Total Broadcasts Sent
 * - Total Expected Deliveries (Sent * (N - 1))
 * - Successfully Received Packets
 * - Packets Lost / Dropped
 * - CRC Errors
 * - Delivery Rate (msgs/s) and Network Throughput (MB/s)
 */
public class MeshThroughputBenchmark {

    private static final int DEFAULT_DURATION_SECONDS = 10;
    private static final int[] NODE_COUNTS = {5, 10};

    public static void main(String[] args) throws Exception {
        int durationSec = DEFAULT_DURATION_SECONDS;
        if (args.length >= 1) {
            try {
                durationSec = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }

        runBenchmark(durationSec);
    }

    public static List<MeshResult> runBenchmark(int durationSeconds) throws Exception {
        BenchmarkUtils.printSystemInfoBanner();
        System.out.printf("[MeshThroughputBenchmark] Evaluating full-mesh broadcast throughput for %d seconds each...%n%n",
                durationSeconds);

        List<MeshResult> results = new ArrayList<>();
        List<String> headers = List.of(
                "Nodes (N)", "Duration", "Sent", "Expected", "Received", "Lost", "Loss Rate", "CRC Errors", "Delivered msgs/s", "MB/s"
        );
        List<List<String>> tableRows = new ArrayList<>();
        List<List<String>> csvRows = new ArrayList<>();

        for (int nodeCount : NODE_COUNTS) {
            System.out.printf(">>> Starting benchmark for N = %d nodes in full P2P mesh...%n", nodeCount);
            MeshResult res = benchmarkMesh(nodeCount, durationSeconds);
            results.add(res);

            // Table format for human-readable console output
            tableRows.add(List.of(
                    String.valueOf(res.nodeCount()),
                    durationSeconds + " s",
                    String.format("%,d", res.totalSent()),
                    String.format("%,d", res.expectedDeliveries()),
                    String.format("%,d", res.totalReceived()),
                    String.format("%,d", res.packetsLost()),
                    String.format("%.3f%%", res.lossRatePercent()),
                    String.valueOf(res.crcErrors()),
                    String.format("%,.1f", res.deliveredMsgsPerSec()),
                    String.format("%,.2f", res.throughputMbPerSec())
            ));

            // Pure integer CSV format without thousand-separator commas
            csvRows.add(List.of(
                    String.valueOf(res.nodeCount()),
                    String.valueOf(durationSeconds),
                    String.valueOf(res.totalSent()),
                    String.valueOf(res.expectedDeliveries()),
                    String.valueOf(res.totalReceived()),
                    String.valueOf(res.packetsLost()),
                    String.format(java.util.Locale.US, "%.3f", res.lossRatePercent()),
                    String.valueOf(res.crcErrors()),
                    String.format(java.util.Locale.US, "%.1f", res.deliveredMsgsPerSec()),
                    String.format(java.util.Locale.US, "%.2f", res.throughputMbPerSec())
            ));
        }

        BenchmarkUtils.printTable("P2P Mesh Broadcast Throughput & Reliability Benchmark", headers, tableRows);

        // Persist to CSV without thousand commas
        List<String> csvHeaders = List.of(
                "Node_Count", "Duration_sec", "Broadcasts_Sent", "Expected_Deliveries",
                "Received_Deliveries", "Packets_Lost", "Loss_Rate_pct", "CRC_Errors",
                "Delivered_msgs_per_sec", "Throughput_MB_per_sec"
        );
        File csv = BenchmarkUtils.writeCsv("mesh_throughput.csv", csvHeaders, csvRows);
        System.out.println("Benchmark results saved to: " + csv.getAbsolutePath());

        return results;
    }

    public static MeshResult benchmarkMesh(int nodeCount, int durationSeconds) throws Exception {
        int multicastPort = ThreadLocalRandom.current().nextInt(20000, 30000);
        int baseTcpPort = ThreadLocalRandom.current().nextInt(31000, 39000);

        List<AgvNode> nodes = new ArrayList<>(nodeCount);
        AtomicLong totalReceived = new AtomicLong(0);
        AtomicLong crcErrors = new AtomicLong(0);

        for (int i = 0; i < nodeCount; i++) {
            long nodeId = i + 1;
            int port = baseTcpPort + i;
            AgvNode node = AgvNode.builder()
                    .nodeId(nodeId)
                    .tcpPort(port)
                    .multicastPort(multicastPort)
                    .heartbeatIntervalMs(1000)
                    .heartbeatTimeoutMs(5000)
                    .build();

            node.getPeerManager().addMessageListener((msg, ch) -> totalReceived.incrementAndGet());
            nodes.add(node);
        }

        try {
            // 1. Start all nodes
            for (AgvNode node : nodes) {
                node.start();
            }

            // 2. Wait until full mesh is connected: each node must see (nodeCount - 1) peers
            System.out.printf("Waiting for %d nodes to establish full P2P mesh (%d connections)...%n",
                    nodeCount, (nodeCount * (nodeCount - 1)) / 2);
            long meshWaitStart = System.currentTimeMillis();
            boolean meshReady = false;
            while (!meshReady) {
                if (System.currentTimeMillis() - meshWaitStart > 15000) {
                    throw new TimeoutException("Timed out waiting for full mesh formation with " + nodeCount + " nodes");
                }
                meshReady = true;
                for (AgvNode n : nodes) {
                    if (n.getActivePeerCount() < (nodeCount - 1)) {
                        meshReady = false;
                        break;
                    }
                }
                if (!meshReady) {
                    Thread.onSpinWait();
                }
            }
            System.out.println("Full mesh established successfully!");

            // 3. Reset counters and prepare payload
            totalReceived.set(0);
            PositionUpdatePayload samplePayload = new PositionUpdatePayload(10.0, 15.0, 20.0, 25.0, AgentState.MOVING);
            byte[] payloadBytes = samplePayload.toBytes();
            int frameSize = 36 + payloadBytes.length; // 69 bytes

            AtomicBoolean running = new AtomicBoolean(true);
            AtomicLong totalSent = new AtomicLong(0);
            ExecutorService broadcastExec = Executors.newFixedThreadPool(nodeCount);

            System.out.printf("Broadcasting continuously for %d seconds across %d nodes...%n", durationSeconds, nodeCount);
            long startNanos = System.nanoTime();

            for (AgvNode node : nodes) {
                broadcastExec.submit(() -> {
                    while (running.get()) {
                        node.broadcast(Opcode.POSITION_UPDATE, payloadBytes);
                        totalSent.incrementAndGet();
                        // Cooperative yield every 100 broadcasts to prevent starving Netty I/O threads
                        if ((totalSent.get() & 0x7F) == 0) {
                            Thread.yield();
                        }
                    }
                });
            }

            // Let it run for duration
            long endTimeNanos = startNanos + TimeUnit.SECONDS.toNanos(durationSeconds);
            while (System.nanoTime() < endTimeNanos) {
                Thread.onSpinWait();
            }

            // Stop broadcast workers
            running.set(false);
            broadcastExec.shutdown();
            broadcastExec.awaitTermination(3, TimeUnit.SECONDS);

            long actualDurationNanos = System.nanoTime() - startNanos;
            double durationSec = actualDurationNanos / 1_000_000_000.0;

            // Allow 2-second drain period for remaining in-flight socket buffers
            long drainEnd = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < drainEnd) {
                Thread.onSpinWait();
            }

            long sent = totalSent.get();
            long expected = sent * (nodeCount - 1L);
            long received = totalReceived.get();
            long lost = Math.max(0, expected - received);
            double lossRate = expected > 0 ? ((double) lost / expected) * 100.0 : 0.0;

            double deliveredMsgsPerSec = (double) received / durationSec;
            double throughputMbPerSec = ((double) received * frameSize / (1024.0 * 1024.0)) / durationSec;

            System.out.printf("Done: %,d broadcasts sent, %,d messages delivered in %.2fs%n%n",
                    sent, received, durationSec);

            return new MeshResult(
                    nodeCount, durationSec, sent, expected, received, lost, lossRate,
                    crcErrors.get(), deliveredMsgsPerSec, throughputMbPerSec
            );
        } finally {
            for (AgvNode node : nodes) {
                try { node.stop(); } catch (Exception ignored) {}
            }
        }
    }

    public record MeshResult(
            int nodeCount,
            double durationSec,
            long totalSent,
            long expectedDeliveries,
            long totalReceived,
            long packetsLost,
            double lossRatePercent,
            long crcErrors,
            double deliveredMsgsPerSec,
            double throughputMbPerSec
    ) {}
}
