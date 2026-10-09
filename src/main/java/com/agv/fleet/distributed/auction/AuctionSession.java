package com.agv.fleet.distributed.auction;

import com.agv.fleet.common.protocol.payload.TaskBidPayload;
import com.agv.fleet.core.agent.Task;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks an active task auction session initiated by an announcer AGV under Contract Net Protocol.
 */
public class AuctionSession {

    private final Task task;
    private final long announceTimestamp;
    private final ConcurrentMap<Long, TaskBidPayload> bids = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CompletableFuture<Long> awardFuture = new CompletableFuture<>();

    private volatile ScheduledFuture<?> timeoutTask;

    public AuctionSession(Task task, long announceTimestamp) {
        this.task = Objects.requireNonNull(task, "task cannot be null");
        this.announceTimestamp = announceTimestamp;
    }

    /**
     * Adds a candidate bid to this auction session if still open.
     *
     * @param bid bid payload submitted by a bidder AGV
     * @return {@code true} if accepted, {@code false} if auction already closed
     */
    public boolean addBid(TaskBidPayload bid) {
        if (closed.get()) {
            return false;
        }
        bids.put(bid.bidderNodeId(), bid);
        return true;
    }

    /**
     * Atomically marks this auction session as closed.
     *
     * @return {@code true} if this call transitioned state from open to closed
     */
    public boolean close() {
        boolean wasOpen = closed.compareAndSet(false, true);
        if (wasOpen && timeoutTask != null) {
            timeoutTask.cancel(false);
        }
        return wasOpen;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public Task getTask() {
        return task;
    }

    public long getAnnounceTimestamp() {
        return announceTimestamp;
    }

    public Collection<TaskBidPayload> getBids() {
        return Collections.unmodifiableCollection(bids.values());
    }

    public int getBidCount() {
        return bids.size();
    }

    public CompletableFuture<Long> getAwardFuture() {
        return awardFuture;
    }

    public ScheduledFuture<?> getTimeoutTask() {
        return timeoutTask;
    }

    public void setTimeoutTask(ScheduledFuture<?> timeoutTask) {
        this.timeoutTask = timeoutTask;
    }
}
