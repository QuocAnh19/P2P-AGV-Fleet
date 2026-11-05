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

        collector.recordLatencySample(1.0);
        collector.recordLatencySample(2.0);
        collector.recordLatencySample(3.0);
        collector.recordLatencySample(4.0);
        collector.recordLatencySample(10.0);

        collector.tickPeriodicSample();

        assertTrue(collector.getP50LatencyMs() >= 2.0);
        assertTrue(collector.getP95LatencyMs() >= 4.0);
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
