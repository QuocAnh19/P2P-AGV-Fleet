package com.agv.fleet.distributed.mutex;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe state container for a specific intersection cell (critical section)
 * managed under the Ricart-Agrawala distributed mutual exclusion algorithm.
 */
public class CellMutex {

    private final String cellId;
    private final ReentrantLock lock = new ReentrantLock();

    private MutexState state = MutexState.RELEASED;
    private long requestTimestamp = 0;
    private final Set<Long> pendingReplies = new HashSet<>();
    private final Queue<Long> deferredQueue = new ArrayDeque<>();
    private CompletableFuture<Void> grantFuture;

    public CellMutex(String cellId) {
        this.cellId = Objects.requireNonNull(cellId, "cellId cannot be null");
    }

    public String getCellId() {
        return cellId;
    }

    public ReentrantLock getLock() {
        return lock;
    }

    public MutexState getState() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    public void setState(MutexState state) {
        lock.lock();
        try {
            this.state = Objects.requireNonNull(state, "state cannot be null");
        } finally {
            lock.unlock();
        }
    }

    public long getRequestTimestamp() {
        lock.lock();
        try {
            return requestTimestamp;
        } finally {
            lock.unlock();
        }
    }

    public void setRequestTimestamp(long requestTimestamp) {
        lock.lock();
        try {
            this.requestTimestamp = requestTimestamp;
        } finally {
            lock.unlock();
        }
    }

    public void addPendingReply(long peerId) {
        lock.lock();
        try {
            pendingReplies.add(peerId);
        } finally {
            lock.unlock();
        }
    }

    public boolean removePendingReply(long peerId) {
        lock.lock();
        try {
            return pendingReplies.remove(peerId);
        } finally {
            lock.unlock();
        }
    }

    public void clearPendingReplies() {
        lock.lock();
        try {
            pendingReplies.clear();
        } finally {
            lock.unlock();
        }
    }

    public Set<Long> getPendingReplies() {
        lock.lock();
        try {
            return new HashSet<>(pendingReplies);
        } finally {
            lock.unlock();
        }
    }

    public int getPendingRepliesCount() {
        lock.lock();
        try {
            return pendingReplies.size();
        } finally {
            lock.unlock();
        }
    }

    public void deferReply(long requesterNodeId) {
        lock.lock();
        try {
            if (!deferredQueue.contains(requesterNodeId)) {
                deferredQueue.add(requesterNodeId);
            }
        } finally {
            lock.unlock();
        }
    }

    public List<Long> drainDeferred() {
        lock.lock();
        try {
            List<Long> list = new ArrayList<>(deferredQueue);
            deferredQueue.clear();
            return list;
        } finally {
            lock.unlock();
        }
    }

    public boolean removeDeferred(long peerId) {
        lock.lock();
        try {
            return deferredQueue.remove(peerId);
        } finally {
            lock.unlock();
        }
    }

    public void clearDeferred() {
        lock.lock();
        try {
            deferredQueue.clear();
        } finally {
            lock.unlock();
        }
    }

    public List<Long> getDeferredNodes() {
        lock.lock();
        try {
            return new ArrayList<>(deferredQueue);
        } finally {
            lock.unlock();
        }
    }

    public CompletableFuture<Void> getGrantFuture() {
        lock.lock();
        try {
            return grantFuture;
        } finally {
            lock.unlock();
        }
    }

    public void setGrantFuture(CompletableFuture<Void> grantFuture) {
        lock.lock();
        try {
            this.grantFuture = grantFuture;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            return String.format("CellMutex[cellId='%s', state=%s, reqTs=%d, pending=%s, deferred=%s]",
                    cellId, state, requestTimestamp, pendingReplies, deferredQueue);
        } finally {
            lock.unlock();
        }
    }
}
