package com.agv.fleet.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkSmokeTest {

    @Test
    @DisplayName("Task 2: BenchmarkUtils extracts environment metadata and writes valid CSV")
    void testBenchmarkUtilsMetadataAndCsv() {
        assertThat(BenchmarkUtils.getCpuInfo()).isNotBlank();
        assertThat(BenchmarkUtils.getRamInfo()).isNotBlank();
        assertThat(BenchmarkUtils.getJdkInfo()).isNotBlank();
        assertThat(BenchmarkUtils.getOsInfo()).isNotBlank();

        List<String> headers = List.of("Col1", "Col2");
        List<List<String>> rows = List.of(
                List.of("ValA", "ValB"),
                List.of("ValC", "ValD")
        );
        File csv = BenchmarkUtils.writeCsv("smoke_test.csv", headers, rows);
        assertThat(csv).exists().isNotEmpty();
    }

    @Test
    @DisplayName("Task 2: CodecThroughputBenchmark runs successfully on EmbeddedChannel")
    void testCodecThroughputSmoke() {
        List<CodecThroughputBenchmark.CodecResult> results = CodecThroughputBenchmark.runBenchmark(100, 500);
        assertThat(results).hasSize(4);
        for (CodecThroughputBenchmark.CodecResult res : results) {
            assertThat(res.msgsPerSec()).isPositive();
            assertThat(res.mbPerSec()).isPositive();
        }
    }

    @Test
    @DisplayName("Task 2: LatencyBenchmark runs successfully between 2 loopback nodes")
    void testLatencyBenchmarkSmoke() throws Exception {
        LatencyBenchmark.BenchmarkResult res = LatencyBenchmark.runBenchmark(50, 100);
        assertThat(res.p50Nanos()).isPositive();
        assertThat(res.maxNanos()).isGreaterThanOrEqualTo(res.p50Nanos());
    }

    @Test
    @DisplayName("Task 2: MeshThroughputBenchmark runs successfully on mesh and persists CSV")
    void testMeshThroughputSmoke() throws Exception {
        List<MeshThroughputBenchmark.MeshResult> results = MeshThroughputBenchmark.runBenchmark(2);
        assertThat(results).hasSize(2);
        for (MeshThroughputBenchmark.MeshResult res : results) {
            assertThat(res.totalSent()).isPositive();
            assertThat(res.totalReceived()).isPositive();
            assertThat(res.deliveredMsgsPerSec()).isPositive();
        }
        File csv = new File("docs/benchmarks/mesh_throughput.csv");
        assertThat(csv).exists().isNotEmpty();
    }
}
