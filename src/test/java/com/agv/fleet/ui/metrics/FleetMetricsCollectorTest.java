package com.agv.fleet.ui.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class FleetMetricsCollectorTest {

    private FleetMetricsCollector collector;

    @BeforeEach
    public void setUp() {
        collector = new FleetMetricsCollector();
    }

    @Test
    public void testCollisionAndTaskTracking() {
        assertEquals(0, collector.getGroundTruthCollisions());
        assertEquals(0, collector.getTasksCompleted());
        assertEquals(0, collector.getTasksFailed());

        collector.recordCollision();
        collector.recordCollision();
        assertEquals(2, collector.getGroundTruthCollisions());

        collector.recordTaskCompleted();
        collector.recordTaskCompleted();
        collector.recordTaskCompleted();
        assertEquals(3, collector.getTasksCompleted());

        collector.recordTaskFailed();
        assertEquals(1, collector.getTasksFailed());
    }

    @Test
    public void testMutexWaitTimeCalculation() {
        assertEquals(0.0, collector.getAverageMutexWaitTimeMs());

        collector.recordMutexWait(100);
        collector.recordMutexWait(200);
        collector.recordMutexWait(300);

        assertEquals(3, collector.getTotalMutexAcquisitions());
        assertEquals(200.0, collector.getAverageMutexWaitTimeMs(), 0.001);
    }

    @Test
    public void testThroughputAndLatencyPercentiles() {
        collector.recordMessageProcessed();
        collector.recordMessageProcessed();
        collector.recordMessageProcessed();
        assertEquals(3, collector.getTotalMessagesProcessed());

        // Add 10 latency samples
        for (int i = 1; i <= 10; i++) {
            collector.recordLatencySample(i * 1.0);
        }

        collector.tickPeriodicSample();

        // Check average latency: (1 + 2 + ... + 10) / 10 = 5.5
        assertEquals(5.5, collector.getAverageLatencyMs(), 0.001);

        // p50Index = round(9 * 0.5) = 5 -> 6.0
        assertEquals(6.0, collector.getP50LatencyMs(), 0.001);

        // p95Index = round(9 * 0.95) = 9 -> 10.0
        assertEquals(10.0, collector.getP95LatencyMs(), 0.001);
    }

    @Test
    public void testResetAndCsvExport() throws IOException {
        collector.recordCollision();
        collector.recordTaskCompleted();
        collector.recordMutexWait(50);
        collector.setAlivePeersCount(4);

        String csv = collector.toCsvString();
        assertTrue(csv.contains("Collisions"));
        assertTrue(csv.contains(",1,1,0,50.00,0,0,0,"));

        File tmpFile = File.createTempFile("metrics_test_", ".csv");
        tmpFile.deleteOnExit();
        collector.exportCsv(tmpFile);

        String content = Files.readString(tmpFile.toPath());
        assertTrue(content.contains("Timestamp,Collisions,TasksCompleted,TasksFailed,AvgMutexWaitMs,TotalAuctions,TotalBids,TotalMsgs,MsgsPerSec,LatencyP50Ms,LatencyP95Ms,AlivePeers"));
        assertTrue(content.contains(",1,1,0,50.00,0,0,0,"));
        assertTrue(content.contains(",4\n") || content.contains(",4\r\n"));

        collector.reset();
        assertEquals(0, collector.getGroundTruthCollisions());
        assertEquals(0, collector.getTasksCompleted());
        assertEquals(0.0, collector.getAverageMutexWaitTimeMs());
    }
}
