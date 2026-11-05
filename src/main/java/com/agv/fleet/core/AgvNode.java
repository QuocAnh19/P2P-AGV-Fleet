package com.agv.fleet.core;

import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import com.agv.fleet.network.discovery.UdpPeerDiscovery;
import com.agv.fleet.network.mesh.*;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.agv.fleet.core.agent.AgentState;
import com.agv.fleet.core.agent.AgvBattery;
import com.agv.fleet.core.agent.Position2D;
import com.agv.fleet.core.agent.Task;
import com.agv.fleet.distributed.auction.AuctionSession;
import com.agv.fleet.distributed.auction.CnpEngine;
import com.agv.fleet.distributed.auction.TaskAssignmentListener;
import com.agv.fleet.distributed.clock.LamportClock;
import com.agv.fleet.distributed.mutex.MutexState;
import com.agv.fleet.distributed.mutex.RicartAgrawalaEngine;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private final String networkInterfaceName;
    private final long discoveryIntervalMs;
    private final long heartbeatIntervalMs;
    private final long heartbeatTimeoutMs;

    private final AtomicLong sequenceCounter = new AtomicLong(1);
    private final LamportClock lamportClock = new LamportClock(0);

    private final PeerManager peerManager;
    private final RicartAgrawalaEngine mutexEngine;
    private final com.agv.fleet.core.agent.PeerPositionTable peerPositionTable;

    private final AgvBattery battery;
    private volatile Position2D position;
    private volatile AgentState agentState = AgentState.IDLE;
    private volatile ScheduledExecutorService cnpScheduler;
    private final CnpEngine cnpEngine;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private AgvTcpServer tcpServer;
    private AgvTcpClient tcpClient;
    private UdpPeerDiscovery udpDiscovery;
    private HeartbeatController heartbeatController;

    private volatile int actualTcpPort;
    private volatile boolean running = false;
    private final Map<Long, CompletableFuture<Long>> pendingPings = new ConcurrentHashMap<>();

    public AgvNode(Builder builder) {
        this.nodeId = builder.nodeId;
        this.host = builder.host;
        this.requestedTcpPort = builder.tcpPort;
        this.multicastGroup = builder.multicastGroup;
        this.multicastPort = builder.multicastPort;
        this.networkInterfaceName = builder.networkInterfaceName;
        this.discoveryIntervalMs = builder.discoveryIntervalMs;
        this.heartbeatIntervalMs = builder.heartbeatIntervalMs;
        this.heartbeatTimeoutMs = builder.heartbeatTimeoutMs;

        this.peerManager = new PeerManager(this.nodeId);
        this.mutexEngine = new RicartAgrawalaEngine(this.nodeId, this.peerManager, this.lamportClock, this::nextSequenceId);
        this.peerManager.setMutexEngine(this.mutexEngine);
        this.peerPositionTable = new com.agv.fleet.core.agent.PeerPositionTable(this.nodeId, this.heartbeatTimeoutMs);
        this.peerManager.setPositionTable(this.peerPositionTable);

        this.battery = new AgvBattery(builder.initialBattery);
        this.position = builder.initialPosition != null ? builder.initialPosition : new Position2D(0.0, 0.0);
        this.cnpScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cnp-scheduler-node-" + this.nodeId);
            t.setDaemon(true);
            return t;
        });
        this.cnpEngine = new CnpEngine(
                this.nodeId,
                this.peerManager,
                this.lamportClock,
                this::nextSequenceId,
                this.cnpScheduler,
                this.battery::getLevel,
                this::getPosition,
                this::getAgentState,
                this::setAgentState,
                builder.auctionTimeoutMs,
                builder.maintenanceWeight
        );
        this.peerManager.setCnpEngine(this.cnpEngine);

        // Subscribe to PING requests: reply with PONG echoing the 8-byte nanoTime payload
        this.peerManager.getTopicRouter().subscribe("sys/ping", msg -> {
            if (!running) return;
            long replyTs = lamportClock.tick();
            AgvMessage pong = AgvMessage.create(
                    Opcode.PONG,
                    nextSequenceId(),
                    replyTs,
                    this.nodeId,
                    msg.getPayload()
            );
            peerManager.send(msg.getSenderNodeId(), pong);
        });

        // Subscribe to PONG replies: match sendNanoTime and complete RTT future
        this.peerManager.getTopicRouter().subscribe("sys/pong", msg -> {
            if (msg.getPayload() != null && msg.getPayload().length >= 8) {
                long sendNano = com.agv.fleet.common.protocol.payload.PingPongPayload.fromBytes(msg.getPayload()).sendNanoTime();
                CompletableFuture<Long> future = pendingPings.remove(sendNano);
                if (future != null) {
                    long rttNanos = System.nanoTime() - sendNano;
                    future.complete(rttNanos);
                }
            }
        });
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

        if (this.cnpScheduler == null || this.cnpScheduler.isShutdown()) {
            this.cnpScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cnp-scheduler-node-" + this.nodeId);
                t.setDaemon(true);
                return t;
            });
            this.cnpEngine.setScheduler(this.cnpScheduler);
        }

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
                networkInterfaceName,
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
     * Gracefully stops the AGV Node (alias for stop).
     */
    public synchronized void shutdownGracefully() {
        stop();
    }

    /**
     * Abruptly crashes the node (simulating power failure):
     * - Closes all TCP sockets immediately without releasing mutex or sending replies
     * - Shuts down network and schedulers
     */
    public synchronized void crash() {
        if (!running) {
            return;
        }
        running = false;
        log.warn("AGV Node {}: CRASHING abruptly (power loss simulation)...", nodeId);

        // 1. Stop Heartbeat Controller without sending any farewell
        if (heartbeatController != null) {
            try {
                heartbeatController.stop();
            } catch (Exception ignored) {}
        }

        // 2. Stop UDP Discovery
        if (udpDiscovery != null) {
            try {
                udpDiscovery.stop();
            } catch (Exception ignored) {}
        }

        // 3. Abruptly close active TCP channels WITHOUT releasing any mutexes or sending any replies
        if (peerManager != null) {
            peerManager.closeAll();
        }

        // 4. Stop TCP server
        if (tcpServer != null) {
            try {
                tcpServer.stop();
            } catch (Exception ignored) {}
        }

        // 5. Terminate event loops abruptly
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 50, TimeUnit.MILLISECONDS);
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 50, TimeUnit.MILLISECONDS);
        }

        // 6. Stop scheduler
        if (cnpScheduler != null) {
            cnpScheduler.shutdownNow();
        }

        log.warn("AGV Node {} abruptly crashed.", nodeId);
    }

    /**
     * Freezes the network subsystem to simulate an unresponsive node (hang/unreachable):
     * - Stops sending heartbeats
     * - Disables reading and writing on all peer channels
     * - Keeps TCP sockets open so peers must detect failure via heartbeat timeout
     */
    public synchronized void freezeNetwork() {
        log.warn("Node {}: Freezing network (keeping sockets open, suppressing heartbeats and I/O)", nodeId);
        if (heartbeatController != null) {
            heartbeatController.stop();
        }
        if (udpDiscovery != null) {
            udpDiscovery.stop();
        }
        if (peerManager != null) {
            peerManager.freeze();
        }
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

        // 6. Stop CNP scheduler
        if (cnpScheduler != null) {
            cnpScheduler.shutdownNow();
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

    public List<Long> getDeferredNodes(String cellId) {
        return mutexEngine != null ? mutexEngine.getDeferredNodes(cellId) : Collections.emptyList();
    }

    public LamportClock getLamportClock() {
        return lamportClock;
    }

    public com.agv.fleet.core.agent.PeerPositionTable getPeerPositionTable() {
        return peerPositionTable;
    }

    public void addMutexEventListener(com.agv.fleet.distributed.mutex.RicartAgrawalaEngine.MutexEventListener listener) {
        if (mutexEngine != null) {
            mutexEngine.addMutexEventListener(listener);
        }
    }

    public void addCnpEventListener(com.agv.fleet.distributed.auction.CnpEngine.CnpEventListener listener) {
        if (cnpEngine != null) {
            cnpEngine.addCnpEventListener(listener);
        }
    }

    public void resetMutexEngine() {
        if (mutexEngine != null) {
            mutexEngine.resetAll();
        }
    }

    public void broadcastPositionUpdate(com.agv.fleet.common.protocol.payload.PositionUpdatePayload payload) {
        if (!running) return;
        long timestamp = lamportClock.tick();
        AgvMessage msg = AgvMessage.create(
                Opcode.POSITION_UPDATE,
                nextSequenceId(),
                timestamp,
                this.nodeId,
                payload.toBytes()
        );
        peerManager.broadcast(msg);
    }

    /**
     * Sends a diagnostic PING (Opcode 0x04) to a peer AGV and returns a CompletableFuture
     * completing with the measured Round-Trip Time (RTT) in nanoseconds.
     *
     * @param targetNodeId destination peer ID
     * @return future completing with measured RTT in nanoseconds
     */
    public CompletableFuture<Long> ping(long targetNodeId) {
        long sendNanoTime = System.nanoTime();
        CompletableFuture<Long> future = new CompletableFuture<>();
        pendingPings.put(sendNanoTime, future);

        com.agv.fleet.common.protocol.payload.PingPongPayload payload =
                new com.agv.fleet.common.protocol.payload.PingPongPayload(sendNanoTime);
        AgvMessage pingMsg = AgvMessage.create(
                Opcode.PING,
                nextSequenceId(),
                nextLamportTimestamp(),
                this.nodeId,
                payload.toBytes()
        );
        if (!peerManager.hasPeer(targetNodeId)) {
            pendingPings.remove(sendNanoTime);
            future.completeExceptionally(new java.io.IOException("Target node " + targetNodeId + " is not connected"));
            return future;
        }
        peerManager.send(targetNodeId, pingMsg);
        return future;
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

    /**
     * Announces a task to the network using Contract Net Protocol.
     *
     * @param task the transport task to be auctioned
     * @return AuctionSession representing the bidding round
     */
    public AuctionSession announceTask(Task task) {
        return cnpEngine.announceTask(task);
    }

    /**
     * Notifies the task announcer that the contractor has completed the transport task.
     *
     * @param taskId          ID of the completed task
     * @param announcerNodeId ID of the node that announced and owns the task
     */
    public void notifyTaskComplete(String taskId, long announcerNodeId) {
        log.info("Node {}: Notifying task completion of {} to announcer Node {}", nodeId, taskId, announcerNodeId);
        if (announcerNodeId == this.nodeId) {
            cnpEngine.onTaskComplete(taskId, this.nodeId);
            return;
        }
        com.agv.fleet.common.protocol.payload.TaskCompletePayload payload =
                new com.agv.fleet.common.protocol.payload.TaskCompletePayload(taskId, this.nodeId);
        AgvMessage msg = AgvMessage.create(
                Opcode.TASK_COMPLETE,
                nextSequenceId(),
                nextLamportTimestamp(),
                this.nodeId,
                payload.toBytes()
        );
        peerManager.send(announcerNodeId, msg);
    }

    public AgvBattery getBattery() {
        return battery;
    }

    public double getBatteryLevel() {
        return battery.getLevel();
    }

    public Position2D getPosition() {
        return position;
    }

    public void setPosition(Position2D position) {
        this.position = Objects.requireNonNull(position, "position cannot be null");
    }

    public void setPosition(double x, double y) {
        this.position = new Position2D(x, y);
    }

    public AgentState getAgentState() {
        return agentState;
    }

    public void setAgentState(AgentState agentState) {
        this.agentState = Objects.requireNonNull(agentState, "agentState cannot be null");
    }

    public CnpEngine getCnpEngine() {
        return cnpEngine;
    }

    public void addAssignmentListener(TaskAssignmentListener listener) {
        cnpEngine.addAssignmentListener(listener);
    }

    public void removeAssignmentListener(TaskAssignmentListener listener) {
        cnpEngine.removeAssignmentListener(listener);
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
        private String networkInterfaceName;
        private long discoveryIntervalMs = UdpPeerDiscovery.DEFAULT_BROADCAST_INTERVAL_MS;
        private long heartbeatIntervalMs = HeartbeatController.DEFAULT_HEARTBEAT_INTERVAL_MS;
        private long heartbeatTimeoutMs = HeartbeatController.DEFAULT_HEARTBEAT_TIMEOUT_MS;
        private double initialBattery = AgvBattery.MAX_BATTERY_LEVEL;
        private Position2D initialPosition = new Position2D(0.0, 0.0);
        private long auctionTimeoutMs = CnpEngine.DEFAULT_AUCTION_TIMEOUT_MS;
        private double maintenanceWeight = CnpEngine.DEFAULT_MAINTENANCE_WEIGHT;

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

        public Builder networkInterfaceName(String networkInterfaceName) {
            this.networkInterfaceName = networkInterfaceName;
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

        public Builder initialBattery(double initialBattery) {
            this.initialBattery = initialBattery;
            return this;
        }

        public Builder initialPosition(Position2D initialPosition) {
            this.initialPosition = initialPosition;
            return this;
        }

        public Builder initialPosition(double x, double y) {
            this.initialPosition = new Position2D(x, y);
            return this;
        }

        public Builder auctionTimeoutMs(long auctionTimeoutMs) {
            this.auctionTimeoutMs = auctionTimeoutMs;
            return this;
        }

        public Builder maintenanceWeight(double maintenanceWeight) {
            this.maintenanceWeight = maintenanceWeight;
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
