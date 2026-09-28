package com.agv.fleet.distributed.clock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe implementation of Lamport's Logical Clock for distributed event ordering.
 * <p>
 * Ensures partial ordering of events across the P2P AGV Fleet without requiring synchronized physical clocks:
 * <ul>
 *   <li>Internal event / message sending: $L = L + 1$</li>
 *   <li>Message receipt: $L = \max(L, L_{received}) + 1$</li>
 * </ul>
 */
public class LamportClock {

    private final AtomicLong clock;

    /**
     * Initializes Lamport Clock at 0.
     */
    public LamportClock() {
        this(0L);
    }

    /**
     * Initializes Lamport Clock at specified initial value.
     *
     * @param initialTime initial logical clock value
     */
    public LamportClock(long initialTime) {
        this.clock = new AtomicLong(Math.max(0L, initialTime));
    }

    /**
     * Ticks the clock forward by 1 for local events or before transmitting a message.
     *
     * @return the new incremented clock value
     */
    public long tick() {
        return clock.incrementAndGet();
    }

    /**
     * Updates the local clock upon receiving a remote event timestamp:
     * $L_{new} = \max(L_{local}, L_{received}) + 1$.
     *
     * @param receivedTimestamp remote Lamport timestamp from incoming packet
     * @return the updated clock value
     */
    public long updateAndGet(long receivedTimestamp) {
        return clock.updateAndGet(current -> Math.max(current, receivedTimestamp) + 1);
    }

    /**
     * Retrieves the current clock value without advancing it.
     *
     * @return current logical timestamp
     */
    public long getTime() {
        return clock.get();
    }

    /**
     * Resets the clock to a specific value (mainly for testing or re-initialization).
     *
     * @param value new clock value
     */
    public void reset(long value) {
        clock.set(value);
    }

    @Override
    public String toString() {
        return "LamportClock[" + clock.get() + "]";
    }
}
