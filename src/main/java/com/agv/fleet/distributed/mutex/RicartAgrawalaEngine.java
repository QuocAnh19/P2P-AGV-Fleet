package com.agv.fleet.distributed.mutex;

import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import com.agv.fleet.common.protocol.payload.MutexReleasePayload;
import com.agv.fleet.common.protocol.payload.MutexReplyPayload;
import com.agv.fleet.common.protocol.payload.MutexRequestPayload;
import com.agv.fleet.distributed.clock.LamportClock;
import com.agv.fleet.network.mesh.PeerManager;
import com.agv.fleet.network.mesh.PeerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * Distributed Mutual Exclusion engine implementing the Ricart-Agrawala algorithm
 * with Lamport logical clocks for conflict-free intersection management in AGV fleets.
 */
public class RicartAgrawalaEngine {

    private static final Logger log = LoggerFactory.getLogger(RicartAgrawalaEngine.class);

    private final long thisNodeId;
    private final PeerManager peerManager;
    private final LamportClock lamportClock;
    private final LongSupplier sequenceSupplier;
    private final ConcurrentMap<String, CellMutex> cellMutexes = new ConcurrentHashMap<>();
    private final List<MutexEventListener> mutexEventListeners = new CopyOnWriteArrayList<>();

    /**
     * Callback interface for mutual exclusion state transitions and events.
     */
    public interface MutexEventListener {
        default void onMutexRequested(String cellId, long requestTimestamp) {}
        default void onMutexAcquired(String cellId) {}
        default void onMutexReleased(String cellId) {}
    }

    public void addMutexEventListener(MutexEventListener listener) {
        if (listener != null) {
            mutexEventListeners.add(listener);
        }
    }

    public void removeMutexEventListener(MutexEventListener listener) {
        mutexEventListeners.remove(listener);
    }

    private void notifyMutexRequested(String cellId, long requestTimestamp) {
        for (MutexEventListener listener : mutexEventListeners) {
            try {
                listener.onMutexRequested(cellId, requestTimestamp);
            } catch (Exception ignored) {}
        }
    }

    private void notifyMutexAcquired(String cellId) {
        for (MutexEventListener listener : mutexEventListeners) {
            try {
                listener.onMutexAcquired(cellId);
            } catch (Exception ignored) {}
        }
    }

    private void notifyMutexReleased(String cellId) {
        for (MutexEventListener listener : mutexEventListeners) {
            try {
                listener.onMutexReleased(cellId);
            } catch (Exception ignored) {}
        }
    }

    public RicartAgrawalaEngine(long thisNodeId,
                                PeerManager peerManager,
                                LamportClock lamportClock,
                                LongSupplier sequenceSupplier) {
        this.thisNodeId = thisNodeId;
        this.peerManager = Objects.requireNonNull(peerManager, "peerManager cannot be null");
        this.lamportClock = Objects.requireNonNull(lamportClock, "lamportClock cannot be null");
        this.sequenceSupplier = Objects.requireNonNull(sequenceSupplier, "sequenceSupplier cannot be null");
    }

    /**
     * Resolves or creates the {@link CellMutex} state for a given intersection cell.
     *
     * @param cellId intersection cell identifier
     * @return CellMutex instance
     */
    public CellMutex getOrCreateCellMutex(String cellId) {
        return cellMutexes.computeIfAbsent(cellId, CellMutex::new);
    }

    /**
     * Resets all cell mutex states to RELEASED and clears deferred / pending collections.
     * Useful for multi-run test scenarios.
     */
    public void resetAll() {
        for (CellMutex mutex : cellMutexes.values()) {
            mutex.getLock().lock();
            try {
                mutex.setState(MutexState.RELEASED);
                mutex.clearPendingReplies();
                mutex.clearDeferred();
                CompletableFuture<Void> gf = mutex.getGrantFuture();
                if (gf != null && !gf.isDone()) {
                    gf.cancel(false);
                }
            } finally {
                mutex.getLock().unlock();
            }
        }
    }

    /**
     * Requests exclusive access to an intersection cell (critical section).
     *
     * @param cellId intersection cell ID
     * @return CompletableFuture completing when the AGV is safely granted permission (HELD)
     */
    public CompletableFuture<Void> acquireIntersection(String cellId) {
        CellMutex cellMutex = getOrCreateCellMutex(cellId);
        cellMutex.getLock().lock();
        try {
            MutexState state = cellMutex.getState();
            if (state == MutexState.HELD) {
                log.debug("Node {}: Already holds lock for cell {}", thisNodeId, cellId);
                return CompletableFuture.completedFuture(null);
            }
            if (state == MutexState.WANTED) {
                log.debug("Node {}: Request for cell {} already pending", thisNodeId, cellId);
                return cellMutex.getGrantFuture();
            }

            // 1. Transition state to WANTED
            cellMutex.setState(MutexState.WANTED);

            // 2. Assign requestTimestamp = lamportClock.tick()
            long reqTimestamp = lamportClock.tick();
            cellMutex.setRequestTimestamp(reqTimestamp);

            CompletableFuture<Void> grantFuture = new CompletableFuture<>();
            cellMutex.setGrantFuture(grantFuture);
            cellMutex.clearPendingReplies();
            cellMutex.clearDeferred();

            // 3. Inspect active peers
            Collection<PeerSession> activePeers = peerManager.getActivePeers();
            if (activePeers.isEmpty()) {
                log.info("Node {}: No other peers active in network. Immediately acquired cell {}", thisNodeId, cellId);
                cellMutex.setState(MutexState.HELD);
                grantFuture.complete(null);
                notifyMutexAcquired(cellId);
                return grantFuture;
            }

            // 4. Register all active peers in pendingReplies
            for (PeerSession session : activePeers) {
                cellMutex.addPendingReply(session.getPeerId());
            }

            log.info("Node {}: Requesting cell {} (reqTs={}, awaiting {} peers: {})",
                    thisNodeId, cellId, reqTimestamp, cellMutex.getPendingRepliesCount(), cellMutex.getPendingReplies());
            notifyMutexRequested(cellId, reqTimestamp);

            // 5. Broadcast MUTEX_REQUEST (Opcode 0x10)
            MutexRequestPayload payload = new MutexRequestPayload(cellId, reqTimestamp, thisNodeId);
            AgvMessage requestMsg = AgvMessage.create(
                    Opcode.MUTEX_REQUEST,
                    sequenceSupplier.getAsLong(),
                    reqTimestamp,
                    thisNodeId,
                    payload.toBytes()
            );
            peerManager.broadcast(requestMsg);

            // 6. Return non-blocking grantFuture
            return grantFuture;
        } finally {
            cellMutex.getLock().unlock();
        }
    }

    /**
     * Handles incoming MUTEX_REQUEST (Opcode 0x10) from a peer AGV.
     *
     * @param message incoming AGV protocol message
     */
    public void onMutexRequestReceived(AgvMessage message) {
        MutexRequestPayload req = MutexRequestPayload.fromBytes(message.getPayload());
        String cellId = req.cellId();
        long incomingReqTs = req.requestTimestamp();
        long requesterNodeId = req.requesterNodeId();

        // 1. Update Lamport Clock
        lamportClock.updateAndGet(message.getHeader().lamportTimestamp());

        if (requesterNodeId == thisNodeId) {
            return; // Ignore requests from self
        }

        CellMutex cellMutex = getOrCreateCellMutex(cellId);
        cellMutex.getLock().lock();
        try {
            MutexState state = cellMutex.getState();
            long ourReqTs = cellMutex.getRequestTimestamp();

            // 2. Priority Comparison:
            // Defer if we currently HOLD the lock, OR if we also WANT the lock with higher priority
            // (lower timestamp, or equal timestamp with lower Node ID).
            boolean ourPriorityHigher = (state == MutexState.HELD) ||
                    (state == MutexState.WANTED && (
                            ourReqTs < incomingReqTs || (ourReqTs == incomingReqTs && thisNodeId < requesterNodeId)
                    ));

            if (ourPriorityHigher) {
                log.info("Node {}: Deferring reply to Node {} for cell {} (ourState={}, ourReqTs={}, incomingReqTs={})",
                        thisNodeId, requesterNodeId, cellId, state, ourReqTs, incomingReqTs);
                cellMutex.deferReply(requesterNodeId);
            } else {
                // Otherwise grant immediate MUTEX_REPLY (Opcode 0x11)
                long replyTs = lamportClock.tick();
                MutexReplyPayload replyPayload = new MutexReplyPayload(cellId, thisNodeId);
                AgvMessage replyMsg = AgvMessage.create(
                        Opcode.MUTEX_REPLY,
                        sequenceSupplier.getAsLong(),
                        replyTs,
                        thisNodeId,
                        replyPayload.toBytes()
                );
                log.debug("Node {}: Sending immediate MUTEX_REPLY to Node {} for cell {}",
                        thisNodeId, requesterNodeId, cellId);
                peerManager.send(requesterNodeId, replyMsg);
            }
        } finally {
            cellMutex.getLock().unlock();
        }
    }

    /**
     * Handles incoming MUTEX_REPLY (Opcode 0x11) from a peer AGV.
     *
     * @param message incoming AGV protocol message
     */
    public void onMutexReplyReceived(AgvMessage message) {
        MutexReplyPayload reply = MutexReplyPayload.fromBytes(message.getPayload());
        String cellId = reply.cellId();
        long responderNodeId = message.getHeader().senderNodeId();

        // 1. Update Lamport Clock
        lamportClock.updateAndGet(message.getHeader().lamportTimestamp());

        CellMutex cellMutex = cellMutexes.get(cellId);
        if (cellMutex == null) {
            return;
        }

        cellMutex.getLock().lock();
        try {
            if (cellMutex.getState() != MutexState.WANTED) {
                return;
            }

            // 2. Remove responder from pendingReplies
            boolean removed = cellMutex.removePendingReply(responderNodeId);
            if (removed) {
                log.debug("Node {}: Received MUTEX_REPLY from Node {} for cell {}. Remaining pending: {}",
                        thisNodeId, responderNodeId, cellId, cellMutex.getPendingRepliesCount());
            }

            // 3. If all replies gathered -> transition to HELD
            if (cellMutex.getPendingRepliesCount() == 0) {
                cellMutex.setState(MutexState.HELD);
                CompletableFuture<Void> future = cellMutex.getGrantFuture();
                if (future != null && !future.isDone()) {
                    log.info("Node {}: All replies received! Successfully ACQUIRED cell {}", thisNodeId, cellId);
                    future.complete(null);
                    notifyMutexAcquired(cellId);
                }
            }
        } finally {
            cellMutex.getLock().unlock();
        }
    }

    /**
     * Releases an intersection cell currently held by this AGV node.
     *
     * @param cellId intersection cell identifier
     */
    public void releaseIntersection(String cellId) {
        CellMutex cellMutex = cellMutexes.get(cellId);
        if (cellMutex == null) {
            log.warn("Node {}: Attempted to release non-existent cell {}", thisNodeId, cellId);
            return;
        }

        cellMutex.getLock().lock();
        try {
            if (cellMutex.getState() != MutexState.HELD) {
                log.warn("Node {}: Cannot release cell {}: state is {} (expected HELD)",
                        thisNodeId, cellId, cellMutex.getState());
                return;
            }

            // 1. Transition state to RELEASED
            cellMutex.setState(MutexState.RELEASED);
            notifyMutexReleased(cellId);

            // 2. Drain all deferred requests
            List<Long> deferredNodes = cellMutex.drainDeferred();
            log.info("Node {}: Releasing cell {}. Sending deferred MUTEX_REPLY to {} peers: {}",
                    thisNodeId, cellId, deferredNodes.size(), deferredNodes);

            // 3. Send MUTEX_REPLY to each deferred peer
            for (Long peerId : deferredNodes) {
                long replyTs = lamportClock.tick();
                MutexReplyPayload replyPayload = new MutexReplyPayload(cellId, thisNodeId);
                AgvMessage replyMsg = AgvMessage.create(
                        Opcode.MUTEX_REPLY,
                        sequenceSupplier.getAsLong(),
                        replyTs,
                        thisNodeId,
                        replyPayload.toBytes()
                );
                peerManager.send(peerId, replyMsg);
            }

            // 4. Broadcast MUTEX_RELEASE notification
            long releaseTs = lamportClock.tick();
            MutexReleasePayload releasePayload = new MutexReleasePayload(cellId, thisNodeId);
            AgvMessage releaseMsg = AgvMessage.create(
                    Opcode.MUTEX_RELEASE,
                    sequenceSupplier.getAsLong(),
                    releaseTs,
                    thisNodeId,
                    releasePayload.toBytes()
            );
            peerManager.broadcast(releaseMsg);
        } finally {
            cellMutex.getLock().unlock();
        }
    }

    /**
     * Handles incoming MUTEX_RELEASE (Opcode 0x12) notification.
     *
     * @param message incoming message
     */
    public void onMutexReleaseReceived(AgvMessage message) {
        lamportClock.updateAndGet(message.getHeader().lamportTimestamp());
        if (log.isTraceEnabled()) {
            MutexReleasePayload payload = MutexReleasePayload.fromBytes(message.getPayload());
            log.trace("Node {}: Received MUTEX_RELEASE from Node {} for cell {}",
                    thisNodeId, message.getHeader().senderNodeId(), payload.cellId());
        }
    }

    /**
     * Callback invoked when a new peer connects to the mesh network.
     * For any intersection cell currently in WANTED state, the new peer is added
     * to pendingReplies and sent a MUTEX_REQUEST preserving the original requestTimestamp.
     *
     * @param peerId ID of the newly connected peer
     */
    public void onPeerConnected(long peerId) {
        if (peerId == thisNodeId) {
            return;
        }

        for (CellMutex cellMutex : cellMutexes.values()) {
            cellMutex.getLock().lock();
            try {
                if (cellMutex.getState() == MutexState.WANTED) {
                    long originalReqTs = cellMutex.getRequestTimestamp();
                    cellMutex.addPendingReply(peerId);

                    log.info("Node {}: Late peer Node {} connected while cell {} is WANTED. Added to pendingReplies (total: {}). Sending MUTEX_REQUEST(ts={})",
                            thisNodeId, peerId, cellMutex.getCellId(), cellMutex.getPendingRepliesCount(), originalReqTs);

                    MutexRequestPayload payload = new MutexRequestPayload(cellMutex.getCellId(), originalReqTs, thisNodeId);
                    AgvMessage requestMsg = AgvMessage.create(
                            Opcode.MUTEX_REQUEST,
                            sequenceSupplier.getAsLong(),
                            originalReqTs,
                            thisNodeId,
                            payload.toBytes()
                    );
                    peerManager.send(peerId, requestMsg);
                }
            } finally {
                cellMutex.getLock().unlock();
            }
        }
    }

    /**
     * Callback invoked when a peer node drops or crashes.
     *
     * @param droppedNodeId ID of disconnected peer
     */
    public void onPeerDropped(long droppedNodeId) {
        for (CellMutex cellMutex : cellMutexes.values()) {
            cellMutex.getLock().lock();
            try {
                cellMutex.removeDeferred(droppedNodeId);

                if (cellMutex.getState() == MutexState.WANTED) {
                    boolean removed = cellMutex.removePendingReply(droppedNodeId);
                    if (removed) {
                        log.info("Node {}: Removed crashed peer Node {} from pending replies for cell {}. Remaining: {}",
                                thisNodeId, droppedNodeId, cellMutex.getCellId(), cellMutex.getPendingRepliesCount());

                        // If no more pending replies remain, promote to HELD
                        if (cellMutex.getPendingRepliesCount() == 0) {
                            cellMutex.setState(MutexState.HELD);
                            CompletableFuture<Void> future = cellMutex.getGrantFuture();
                            if (future != null && !future.isDone()) {
                                log.info("Node {}: Promoted to HELD for cell {} after peer {} crashed",
                                        thisNodeId, cellMutex.getCellId(), droppedNodeId);
                                future.complete(null);
                                notifyMutexAcquired(cellMutex.getCellId());
                            }
                        }
                    }
                }
            } finally {
                cellMutex.getLock().unlock();
            }
        }
    }

    public MutexState getState(String cellId) {
        CellMutex mutex = cellMutexes.get(cellId);
        return (mutex != null) ? mutex.getState() : MutexState.RELEASED;
    }

    public boolean isHolding(String cellId) {
        return getState(cellId) == MutexState.HELD;
    }

    public List<Long> getDeferredNodes(String cellId) {
        CellMutex mutex = cellMutexes.get(cellId);
        return (mutex != null) ? mutex.getDeferredNodes() : Collections.emptyList();
    }

    public LamportClock getLamportClock() {
        return lamportClock;
    }

    /**
     * Subscribes this engine to mutex topic patterns on the overlay TopicRouter.
     *
     * @param router topic router
     */
    public void subscribe(com.agv.fleet.network.overlay.TopicRouter router) {
        Objects.requireNonNull(router, "router cannot be null");
        router.subscribe("mutex/*", this::handleMutexMessage);
    }

    private void handleMutexMessage(AgvMessage message) {
        switch (message.getOpcode()) {
            case MUTEX_REQUEST -> onMutexRequestReceived(message);
            case MUTEX_REPLY -> onMutexReplyReceived(message);
            case MUTEX_RELEASE -> onMutexReleaseReceived(message);
            default -> {}
        }
    }

    public long getThisNodeId() {
        return thisNodeId;
    }
}
