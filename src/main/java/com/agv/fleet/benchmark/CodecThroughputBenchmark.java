package com.agv.fleet.benchmark;

import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import com.agv.fleet.network.codec.AgvPacketDecoder;
import com.agv.fleet.network.codec.AgvPacketEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Benchmark measuring serialization and deserialization throughput (encode + decode)
 * of the 36-byte binary framing protocol using Netty's {@link EmbeddedChannel}.
 * <p>
 * Evaluates payloads of sizes: 0B, 32B, 256B, and 4KB (4096B).
 */
public class CodecThroughputBenchmark {

    private static final int[] PAYLOAD_SIZES = {0, 32, 256, 4096};
    private static final int DEFAULT_WARMUP_ITERATIONS = 50000;
    private static final int DEFAULT_MEASURE_ITERATIONS = 300000;

    public static void main(String[] args) {
        int warmup = DEFAULT_WARMUP_ITERATIONS;
        int measure = DEFAULT_MEASURE_ITERATIONS;

        if (args.length >= 2) {
            try {
                warmup = Integer.parseInt(args[0]);
                measure = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {}
        }

        runBenchmark(warmup, measure);
    }

    public static List<CodecResult> runBenchmark(int warmupIterations, int measureIterations) {
        BenchmarkUtils.printSystemInfoBanner();
        System.out.printf("[CodecThroughputBenchmark] Testing encode+decode throughput on EmbeddedChannel...%n");
        System.out.printf("Warmup: %,d iterations | Measure: %,d iterations per payload size%n%n",
                warmupIterations, measureIterations);

        List<CodecResult> results = new ArrayList<>();
        List<String> headers = List.of("Payload Size", "Frame Size", "Iterations", "Duration (s)", "Throughput (msgs/s)", "Throughput (MB/s)", "Avg Latency (µs)");
        List<List<String>> rows = new ArrayList<>();

        Random random = new Random(42);

        for (int payloadSize : PAYLOAD_SIZES) {
            byte[] payload = new byte[payloadSize];
            random.nextBytes(payload);

            EmbeddedChannel channel = new EmbeddedChannel(new AgvPacketEncoder(), new AgvPacketDecoder());
            AgvMessage templateMsg = AgvMessage.create(Opcode.POSITION_UPDATE, 1L, 100L, 99L, payload);

            // 1. Warm-up
            for (int i = 0; i < warmupIterations; i++) {
                channel.writeOutbound(templateMsg);
                ByteBuf encoded = channel.readOutbound();
                channel.writeInbound(encoded);
                AgvMessage decoded = channel.readInbound();
                if (decoded == null) {
                    throw new IllegalStateException("Warmup decoding failed!");
                }
            }

            // 2. Measurement
            System.gc();
            long startNanos = System.nanoTime();
            for (int i = 0; i < measureIterations; i++) {
                channel.writeOutbound(templateMsg);
                ByteBuf encoded = channel.readOutbound();
                channel.writeInbound(encoded);
                AgvMessage decoded = channel.readInbound();
                if (decoded == null) {
                    throw new IllegalStateException("Measurement decoding failed!");
                }
            }
            long elapsedNanos = System.nanoTime() - startNanos;

            channel.finishAndReleaseAll();

            double durationSec = elapsedNanos / 1_000_000_000.0;
            double msgsPerSec = measureIterations / durationSec;
            int frameSize = 36 + payloadSize;
            double totalBytes = (double) measureIterations * frameSize;
            double mbPerSec = (totalBytes / (1024.0 * 1024.0)) / durationSec;
            double avgLatencyUs = (elapsedNanos / (double) measureIterations) / 1000.0;

            String payloadLabel = payloadSize == 0 ? "0 B (Header Only)" : (payloadSize >= 1024 ? (payloadSize / 1024) + " KB" : payloadSize + " B");
            rows.add(List.of(
                    payloadLabel,
                    frameSize + " B",
                    String.format("%,d", measureIterations),
                    String.format("%.3f", durationSec),
                    String.format("%,.1f", msgsPerSec),
                    String.format("%,.2f", mbPerSec),
                    String.format("%.3f", avgLatencyUs)
            ));

            results.add(new CodecResult(payloadSize, frameSize, msgsPerSec, mbPerSec, avgLatencyUs));
        }

        BenchmarkUtils.printTable("Binary Frame Codec Throughput (AgvPacketEncoder + AgvPacketDecoder)", headers, rows);

        // Persist to CSV
        List<String> csvHeaders = List.of("Payload_Bytes", "Frame_Bytes", "Iterations", "Duration_sec", "Msgs_per_sec", "MB_per_sec", "Avg_Latency_us");
        File csv = BenchmarkUtils.writeCsv("codec_throughput.csv", csvHeaders, rows);
        System.out.println("Benchmark results saved to: " + csv.getAbsolutePath());

        return results;
    }

    public record CodecResult(int payloadSize, int frameSize, double msgsPerSec, double mbPerSec, double avgLatencyUs) {}
}
