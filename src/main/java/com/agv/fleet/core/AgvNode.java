package com.agv.fleet.core;

import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import com.agv.fleet.network.discovery.UdpPeerDiscovery;
import com.agv.fleet.network.mesh.*;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agv.fleet.distributed.clock.LamportClock;
import com.agv.fleet.distributed.mutex.MutexState;
import com.agv.fleet.distributed.mutex.RicartAgrawalaEngine;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Autonomous Guided Vehicle (AGV) Node wrapper.
 * <p>
 * Represents an autonomous node in the Pure P2P Fleet network without any central broker.
 * Integrates:
 * <ul>
 *   <li>Netty TCP Server for inbound peer connections</li>
 *   <li>Netty TCP Client for outbound peer connections</li>
 *   <li>UDP Multicast Discovery for decentralized auto-discovery</li>
 *   <li>PeerManager with Duplicate Connection Prevention</li>
 *   <li>Periodic Heartbeat and Failure Detection</li>
 *   <li>Logical Lamport Clock for distributed event ordering</li>
 *   <li>Ricart-Agrawala Distributed Mutual Exclusion for conflict-free intersection crossing</li>
 * </ul>
 */
public class AgvNode {

    private static final Logger log = LoggerFactory.getLogger(AgvNode.class);

    private final long nodeId;
    private final String host;
    private final int requestedTcpPort;
    private final String multicastGroup;
    private final int multicastPort;
    private final long discoveryIntervalMs;
    private final long heartbeatIntervalMs;
    private final long heartbeatTimeoutMs;

    private final AtomicLong sequenceCounter = new AtomicLong(1);
    private final LamportClock lamportClock = new LamportClock(0);

    private final PeerManager peerManager;
    private final RicartAgrawalaEngine mutexEngine;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private AgvTcpServer tcpServer;
    private AgvTcpClient tcpClient;
    private UdpPeerDiscovery udpDiscovery;
    private HeartbeatController heartbeatController;

    private volatile int actualTcpPort;
    private volatile boolean running = false;

    public AgvNode(Builder builder) {
        this.nodeId = builder.nodeId;
        this.host = builder.host;
        this.requestedTcpPort = builder.tcpPort;
        this.multicastGroup = builder.multicastGroup;
        this.multicastPort = builder.multicastPort;
        this.discoveryIntervalMs = builder.discoveryIntervalMs;
        this.heartbeatIntervalMs = builder.heartbeatIntervalMs;
        this.heartbeatTimeoutMs = builder.heartbeatTimeoutMs;

        this.peerManager = new PeerManager(this.nodeId);
        this.mutexEngine = new RicartAgrawalaEngine(this.nodeId, this.peerManager, this.lamportClock, this::nextSequenceId);
        this.peerManager.setMutexEngine(this.mutexEngine);
    }

    /**
     * Synchronously boots up the entire P2P stack:
     * 1. Start Netty TCP Server (binds to port)
     * 2. Initialize Netty TCP Client
     * 3. Start UDP Multicast Peer Discovery
     * 4. Start Heartbeat & Failure Detection Controller
     */
    public synchronized void start() throws Exception {
        if (running) {
            return;
        }

        log.info("Starting AGV Node {} on host {} (requested TCP port: {})...", nodeId, host, requestedTcpPort);

        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup(4);

        // 1. Initialize and start TCP Server
        this.tcpServer = new AgvTcpServer(
                nodeId,
                host,
                requestedTcpPort,
                bossGroup,
                workerGroup,
                peerManager,
                this::nextSequenceId,
                this::nextLamportTimestamp
        );
        this.tcpServer.start();
        this.actualTcpPort = tcpServer.getBoundPort();

        // 2. Initialize TCP Client
        this.tcpClient = new AgvTcpClient(
                nodeId,
                workerGroup,
                peerManager,
                this::nextSequenceId,
                this::nextLamportTimestamp
        );
        this.peerManager.setTcpClient(tcpClient);

        // 3. Initialize and start UDP Discovery
        this.udpDiscovery = new UdpPeerDiscovery(
                nodeId,
                host,
                actualTcpPort,
                multicastGroup,
                multicastPort,
                discoveryIntervalMs,
                workerGroup,
                peerManager::onPeerDiscovered
        );
        this.udpDiscovery.start();

        // 4. Initialize and start Heartbeat Controller
        this.heartbeatController = new HeartbeatController(
                nodeId,
                peerManager,
                workerGroup,
                this::nextSequenceId,
                this::nextLamportTimestamp,
                heartbeatIntervalMs,
                heartbeatTimeoutMs
        );
        this.heartbeatController.start();

        this.running = true;
        log.info("AGV Node {} successfully started! Active TCP port: {}", nodeId, actualTcpPort);
    }

    /**
     * Gracefully stops the AGV Node:
     * 1. Stops UDP Multicast Discovery
     * 2. Stops Heartbeat Controller
     * 3. Closes all active peer sessions
     * 4. Stops TCP Server
     * 5. Shuts down EventLoopGroups cleanly
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        log.info("Stopping AGV Node {}...", nodeId);

        // 1. Stop UDP Discovery
        if (udpDiscovery != null) {
            try {
                udpDiscovery.stop();
            } catch (Exception e) {
                log.warn("Node {}: Error stopping UDP discovery: {}", nodeId, e.getMessage());
            }
        }

        // 2. Stop Heartbeat Controller
        if (heartbeatController != null) {
            try {
                heartbeatController.stop();
            } catch (Exception e) {
                log.warn("Node {}: Error stopping HeartbeatController: {}", nodeId, e.getMessage());
            }
        }

        // 3. Close active TCP connections
        if (peerManager != null) {
            peerManager.closeAll();
        }

        // 4. Stop TCP Server
        if (tcpServer != null) {
            try {
                tcpServer.stop();
            } catch (Exception e) {
                log.warn("Node {}: Error stopping TCP server: {}", nodeId, e.getMessage());
            }
        }

        // 5. Terminate EventLoopGroups
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(50, 200, TimeUnit.MILLISECONDS);
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(50, 200, TimeUnit.MILLISECONDS);
        }

        log.info("AGV Node {} stopped cleanly.", nodeId);
    }

    /**
     * Generates monotonically increasing sequence ID.
     */
    public long nextSequenceId() {
        return sequenceCounter.getAndIncrement();
    }

    /**
     * Advances and returns the logical Lamport Clock timestamp.
     */
    public long nextLamportTimestamp() {
        return lamportClock.tick();
    }

    /**
     * Updates local Lamport clock with received event timestamp: max(local, received) + 1.
     *
     * @param receivedTimestamp timestamp from incoming message
     * @return updated timestamp
     */
    public long updateLamportClock(long receivedTimestamp) {
        return lamportClock.updateAndGet(receivedTimestamp);
    }

    /**
     * Requests exclusive entry into an intersection cell using Ricart-Agrawala algorithm.
     *
     * @param cellId intersection cell ID
     * @return CompletableFuture completing when critical section permission is HELD
     */
    public CompletableFuture<Void> acquireIntersection(String cellId) {
        return mutexEngine.acquireIntersection(cellId);
    }

    /**
     * Releases an intersection cell currently held by this AGV, granting access to deferred peers.
     *
     * @param cellId intersection cell ID
     */
    public void releaseIntersection(String cellId) {
        mutexEngine.releaseIntersection(cellId);
    }

    public RicartAgrawalaEngine getMutexEngine() {
        return mutexEngine;
    }

    public MutexState getMutexState(String cellId) {
        return mutexEngine.getState(cellId);
    }

    public LamportClock getLamportClock() {
        return lamportClock;
    }

    /**
     * Sends an application message to a specific peer.
     *
     * @param targetNodeId peer ID
     * @param opcode       operation code
     * @param payload      binary payload
     */
    public void send(long targetNodeId, Opcode opcode, byte[] payload) {
        AgvMessage message = AgvMessage.create(
                opcode,
                nextSequenceId(),
                nextLamportTimestamp(),
                nodeId,
                payload
        );
        peerManager.send(targetNodeId, message);
    }

    /**
     * Sends a pre-constructed {@link AgvMessage} to a specific peer.
     */
    public void send(long targetNodeId, AgvMessage message) {
        peerManager.send(targetNodeId, message);
    }

    /**
     * Broadcasts an application message to all actively connected peers in the mesh.
     *
     * @param opcode  operation code
     * @param payload binary payload
     */
    public void broadcast(Opcode opcode, byte[] payload) {
        AgvMessage message = AgvMessage.create(
                opcode,
                nextSequenceId(),
                nextLamportTimestamp(),
                nodeId,
                payload
        );
        peerManager.broadcast(message);
    }

    /**
     * Broadcasts a pre-constructed {@link AgvMessage} to all peers.
     */
    public void broadcast(AgvMessage message) {
        peerManager.broadcast(message);
    }

    public void registerMessageListener(MessageListener listener) {
        peerManager.addMessageListener(listener);
    }

    public void registerPeerLifecycleListener(PeerLifecycleListener listener) {
        peerManager.addLifecycleListener(listener);
    }

    public long getNodeId() {
        return nodeId;
    }

    public String getHost() {
        return host;
    }

    public int getTcpPort() {
        return actualTcpPort > 0 ? actualTcpPort : requestedTcpPort;
    }

    public PeerManager getPeerManager() {
        return peerManager;
    }

    public Collection<PeerSession> getActivePeers() {
        return peerManager.getActivePeers();
    }

    public int getActivePeerCount() {
        return peerManager.getActivePeerCount();
    }

    public boolean isRunning() {
        return running;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private long nodeId;
        private String host = "127.0.0.1";
        private int tcpPort = 0; // 0 = ephemeral random port
        private String multicastGroup = UdpPeerDiscovery.DEFAULT_MULTICAST_GROUP;
        private int multicastPort = UdpPeerDiscovery.DEFAULT_MULTICAST_PORT;
        private long discoveryIntervalMs = UdpPeerDiscovery.DEFAULT_BROADCAST_INTERVAL_MS;
        private long heartbeatIntervalMs = HeartbeatController.DEFAULT_HEARTBEAT_INTERVAL_MS;
        private long heartbeatTimeoutMs = HeartbeatController.DEFAULT_HEARTBEAT_TIMEOUT_MS;

        public Builder nodeId(long nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder tcpPort(int tcpPort) {
            this.tcpPort = tcpPort;
            return this;
        }

        public Builder multicastGroup(String multicastGroup) {
            this.multicastGroup = multicastGroup;
            return this;
        }

        public Builder multicastPort(int multicastPort) {
            this.multicastPort = multicastPort;
            return this;
        }

        public Builder discoveryIntervalMs(long discoveryIntervalMs) {
            this.discoveryIntervalMs = discoveryIntervalMs;
            return this;
        }

        public Builder heartbeatIntervalMs(long heartbeatIntervalMs) {
            this.heartbeatIntervalMs = heartbeatIntervalMs;
            return this;
        }

        public Builder heartbeatTimeoutMs(long heartbeatTimeoutMs) {
            this.heartbeatTimeoutMs = heartbeatTimeoutMs;
            return this;
        }

        public AgvNode build() {
            if (nodeId <= 0) {
                throw new IllegalArgumentException("nodeId must be positive: " + nodeId);
            }
            return new AgvNode(this);
        }
    }
}
