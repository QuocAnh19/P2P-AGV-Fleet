package com.agv.fleet.network.mesh;

import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Controller responsible for periodic Heartbeat emissions and Node Failure Detection.
 * <p>
 * Emits {@link Opcode#HEARTBEAT} packets at a configured interval (default 500ms) and checks
 * for peers exceeding the failure detection timeout (default 1500ms = 3 missed heartbeats).
 */
public class HeartbeatController {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatController.class);

    public static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 500;
    public static final long DEFAULT_HEARTBEAT_TIMEOUT_MS = 1500;

    private final long thisNodeId;
    private final PeerManager peerManager;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier sequenceSupplier;
    private final LongSupplier lamportSupplier;
    private final long heartbeatIntervalMs;
    private final long heartbeatTimeoutMs;

    private volatile ScheduledFuture<?> sendTask;
    private volatile ScheduledFuture<?> failureCheckTask;
    private volatile boolean running = false;

    public HeartbeatController(long thisNodeId,
                               PeerManager peerManager,
                               ScheduledExecutorService scheduler,
                               LongSupplier sequenceSupplier,
                               LongSupplier lamportSupplier) {
        this(thisNodeId, peerManager, scheduler, sequenceSupplier, lamportSupplier,
                DEFAULT_HEARTBEAT_INTERVAL_MS, DEFAULT_HEARTBEAT_TIMEOUT_MS);
    }

    public HeartbeatController(long thisNodeId,
                               PeerManager peerManager,
                               ScheduledExecutorService scheduler,
                               LongSupplier sequenceSupplier,
                               LongSupplier lamportSupplier,
                               long heartbeatIntervalMs,
                               long heartbeatTimeoutMs) {
        this.thisNodeId = thisNodeId;
        this.peerManager = Objects.requireNonNull(peerManager, "peerManager cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.sequenceSupplier = Objects.requireNonNull(sequenceSupplier, "sequenceSupplier cannot be null");
        this.lamportSupplier = Objects.requireNonNull(lamportSupplier, "lamportSupplier cannot be null");
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    }

    public synchronized void start() {
        if (running) {
            return;
        }

        // Schedule periodic heartbeat sending
        this.sendTask = scheduler.scheduleAtFixedRate(
                this::sendHeartbeatToPeers,
                heartbeatIntervalMs,
                heartbeatIntervalMs,
                TimeUnit.MILLISECONDS
        );

        // Schedule periodic node failure check
        this.failureCheckTask = scheduler.scheduleAtFixedRate(
                this::checkPeerTimeouts,
                heartbeatIntervalMs,
                heartbeatIntervalMs,
                TimeUnit.MILLISECONDS
        );

        this.running = true;
        log.info("Node {}: HeartbeatController started (interval={}ms, timeout={}ms)",
                thisNodeId, heartbeatIntervalMs, heartbeatTimeoutMs);
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;

        if (sendTask != null) {
            sendTask.cancel(false);
            sendTask = null;
        }
        if (failureCheckTask != null) {
            failureCheckTask.cancel(false);
            failureCheckTask = null;
        }

        log.info("Node {}: HeartbeatController stopped", thisNodeId);
    }

    private void sendHeartbeatToPeers() {
        if (!running || peerManager.getActivePeerCount() == 0) {
            return;
        }

        try {
            AgvMessage heartbeat = AgvMessage.create(
                    Opcode.HEARTBEAT,
                    sequenceSupplier.getAsLong(),
                    lamportSupplier.getAsLong(),
                    thisNodeId
            );
            peerManager.broadcast(heartbeat);
        } catch (Exception e) {
            log.warn("Node {}: Error broadcasting heartbeat: {}", thisNodeId, e.getMessage());
        }
    }

    private void checkPeerTimeouts() {
        if (!running) {
            return;
        }

        long now = System.currentTimeMillis();
        for (PeerSession session : peerManager.getActivePeers()) {
            long elapsed = now - session.getLastHeartbeatTimestamp();
            if (elapsed > heartbeatTimeoutMs) {
                log.warn("Node {}: Failure detected for peer Node {} (last heartbeat {}ms ago > {}ms threshold)",
                        thisNodeId, session.getPeerId(), elapsed, heartbeatTimeoutMs);
                peerManager.removeSession(session.getPeerId());
            }
        }
    }

    public boolean isRunning() {
        return running;
    }
}
