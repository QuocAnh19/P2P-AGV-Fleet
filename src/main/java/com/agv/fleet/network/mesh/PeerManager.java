package com.agv.fleet.network.mesh;

import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.network.discovery.PeerDiscoveryInfo;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe manager responsible for peer topology, session life cycles,
 * duplicate connection prevention, and message dissemination.
 */
public class PeerManager {

    private static final Logger log = LoggerFactory.getLogger(PeerManager.class);

    private final long thisNodeId;
    private final ConcurrentMap<Long, PeerSession> activePeers = new ConcurrentHashMap<>();
    private final Set<Long> connectingPeers = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<PeerLifecycleListener> lifecycleListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MessageListener> messageListeners = new CopyOnWriteArrayList<>();

    private volatile AgvTcpClient tcpClient;

    public PeerManager(long thisNodeId) {
        this.thisNodeId = thisNodeId;
    }

    public void setTcpClient(AgvTcpClient tcpClient) {
        this.tcpClient = Objects.requireNonNull(tcpClient, "tcpClient cannot be null");
    }

    /**
     * Handles peer discovery notifications from UDP multicast.
     * <p>
     * Implements the Duplicate Connection Prevention rule:
     * Only nodes with a lower Node ID initiate outbound TCP connections to nodes with higher IDs.
     * The node with the higher ID passively waits for inbound connections.
     *
     * @param info peer discovery metadata
     */
    public void onPeerDiscovered(PeerDiscoveryInfo info) {
        long peerId = info.nodeId();
        if (peerId == thisNodeId) {
            return; // Never connect to self
        }

        PeerSession existing = activePeers.get(peerId);
        if (existing != null && existing.isActive()) {
            return; // Already actively connected
        }

        // Rule: Only smaller nodeId connects to larger nodeId
        if (thisNodeId < peerId) {
            if (connectingPeers.add(peerId)) {
                log.info("Node {}: Initiating TCP connection to peer Node {} ({}:{})",
                        thisNodeId, peerId, info.host(), info.tcpPort());

                if (tcpClient == null) {
                    log.error("Node {}: AgvTcpClient not configured in PeerManager!", thisNodeId);
                    connectingPeers.remove(peerId);
                    return;
                }

                tcpClient.connect(info.host(), info.tcpPort()).addListener(future -> {
                    if (!future.isSuccess()) {
                        connectingPeers.remove(peerId);
                        log.warn("Node {}: Failed connection attempt to Node {}: {}",
                                thisNodeId, peerId, future.cause().getMessage());
                    }
                });
            }
        } else {
            if (log.isTraceEnabled()) {
                log.trace("Node {}: Discovered Node {} (lower ID). Awaiting inbound connection.",
                        thisNodeId, peerId);
            }
        }
    }

    /**
     * Registers a new or refreshed active peer session.
     *
     * @param peerId  nodeId of peer
     * @param channel TCP channel
     */
    public void registerSession(long peerId, Channel channel) {
        connectingPeers.remove(peerId);

        PeerSession oldSession = activePeers.get(peerId);
        if (oldSession != null && oldSession.getChannel().equals(channel)) {
            oldSession.updateHeartbeat();
            return;
        }

        if (oldSession != null) {
            log.info("Node {}: Replacing existing session for Node {}", thisNodeId, peerId);
            oldSession.close();
        }

        PeerSession newSession = new PeerSession(peerId, channel);
        activePeers.put(peerId, newSession);
        log.info("Node {}: Established connection with Node {}. Total active peers: {}",
                thisNodeId, peerId, activePeers.size());

        for (PeerLifecycleListener listener : lifecycleListeners) {
            try {
                listener.onPeerConnected(peerId, channel);
            } catch (Exception e) {
                log.error("Node {}: Error notifying onPeerConnected for Node {}", thisNodeId, peerId, e);
            }
        }
    }

    /**
     * Handles heartbeat message receipt by refreshing timestamp.
     *
     * @param peerId  nodeId of peer
     * @param channel TCP channel
     */
    public void onHeartbeatReceived(long peerId, Channel channel) {
        PeerSession session = activePeers.get(peerId);
        if (session != null) {
            session.updateHeartbeat();
        } else {
            registerSession(peerId, channel);
        }
    }

    /**
     * Handles channel closure event.
     *
     * @param peerId  nodeId of peer
     * @param channel closed channel
     */
    public void onChannelInactive(long peerId, Channel channel) {
        PeerSession session = activePeers.get(peerId);
        if (session != null && session.getChannel().equals(channel)) {
            removeSession(peerId);
        }
    }

    /**
     * Removes and closes a peer session, alerting lifecycle listeners.
     *
     * @param peerId nodeId of peer to remove
     */
    public void removeSession(long peerId) {
        connectingPeers.remove(peerId);
        PeerSession removed = activePeers.remove(peerId);
        if (removed != null) {
            removed.close();
            log.warn("Node {}: Peer Node {} disconnected. Remaining active peers: {}",
                    thisNodeId, peerId, activePeers.size());

            for (PeerLifecycleListener listener : lifecycleListeners) {
                try {
                    listener.onPeerDropped(peerId);
                } catch (Exception e) {
                    log.error("Node {}: Error notifying onPeerDropped for Node {}", thisNodeId, peerId, e);
                }
            }
        }
    }

    /**
     * Sends an {@link AgvMessage} to a specific target peer.
     *
     * @param targetNodeId peer ID
     * @param message      protocol message
     */
    public void send(long targetNodeId, AgvMessage message) {
        PeerSession session = activePeers.get(targetNodeId);
        if (session != null && session.isActive()) {
            session.send(message);
        } else {
            log.warn("Node {}: Cannot send message to Node {}: session inactive or missing",
                    thisNodeId, targetNodeId);
        }
    }

    /**
     * Broadcasts an {@link AgvMessage} to all actively connected peers.
     *
     * @param message protocol message
     */
    public void broadcast(AgvMessage message) {
        for (PeerSession session : activePeers.values()) {
            if (session.isActive()) {
                session.send(message);
            }
        }
    }

    /**
     * Dispatches an incoming application message to registered listeners.
     *
     * @param message message received
     * @param channel origin channel
     */
    public void dispatchMessage(AgvMessage message, Channel channel) {
        for (MessageListener listener : messageListeners) {
            try {
                listener.onMessageReceived(message, channel);
            } catch (Exception e) {
                log.error("Node {}: Error dispatching message to listener", thisNodeId, e);
            }
        }
    }

    /**
     * Returns an unmodifiable collection of all active peer sessions.
     *
     * @return active sessions
     */
    public Collection<PeerSession> getActivePeers() {
        return Collections.unmodifiableCollection(activePeers.values());
    }

    public PeerSession getSession(long peerId) {
        return activePeers.get(peerId);
    }

    public int getActivePeerCount() {
        return activePeers.size();
    }

    public boolean hasPeer(long peerId) {
        return activePeers.containsKey(peerId);
    }

    public void addLifecycleListener(PeerLifecycleListener listener) {
        lifecycleListeners.add(Objects.requireNonNull(listener, "listener cannot be null"));
    }

    public void removeLifecycleListener(PeerLifecycleListener listener) {
        lifecycleListeners.remove(listener);
    }

    public void addMessageListener(MessageListener listener) {
        messageListeners.add(Objects.requireNonNull(listener, "listener cannot be null"));
    }

    public void removeMessageListener(MessageListener listener) {
        messageListeners.remove(listener);
    }

    /**
     * Closes and clears all active sessions cleanly.
     */
    public void closeAll() {
        connectingPeers.clear();
        for (PeerSession session : activePeers.values()) {
            session.close();
        }
        activePeers.clear();
    }
}
