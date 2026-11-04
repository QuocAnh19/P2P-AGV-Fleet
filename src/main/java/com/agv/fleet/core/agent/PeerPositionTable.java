package com.agv.fleet.core.agent;

import com.agv.fleet.core.map.GridPoint;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe table storing the latest reported spatial positions and states of peer AGVs.
 * Entries expire if no updates are received within the configured heartbeat timeout window.
 */
public class PeerPositionTable {

    public record Entry(
            long nodeId,
            double x,
            double y,
            double targetX,
            double targetY,
            AgentState state,
            long lastUpdateTimestamp
    ) {
        public GridPoint toGridPoint() {
            return new GridPoint((int) Math.round(x), (int) Math.round(y));
        }
    }

    private final long thisNodeId;
    private final long timeoutMs;
    private final ConcurrentMap<Long, Entry> entries = new ConcurrentHashMap<>();

    public PeerPositionTable(long thisNodeId, long timeoutMs) {
        this.thisNodeId = thisNodeId;
        this.timeoutMs = timeoutMs;
    }

    public void update(long nodeId, double x, double y, double targetX, double targetY, AgentState state) {
        if (nodeId == thisNodeId) {
            return;
        }
        entries.put(nodeId, new Entry(nodeId, x, y, targetX, targetY, state, System.currentTimeMillis()));
    }

    public void remove(long nodeId) {
        entries.remove(nodeId);
    }

    public Entry get(long nodeId) {
        return entries.get(nodeId);
    }

    public Long findPeerAt(GridPoint point) {
        long now = System.currentTimeMillis();
        for (Entry entry : entries.values()) {
            if ((now - entry.lastUpdateTimestamp()) <= timeoutMs && entry.toGridPoint().equals(point)) {
                return entry.nodeId();
            }
        }
        return null;
    }

    public Map<Long, Entry> getAll() {
        return entries;
    }

    /**
     * Purges expired entries and returns grid points occupied by active peers.
     *
     * @return set of occupied GridPoint obstacles
     */
    public Set<GridPoint> getActiveObstaclePoints() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(entry -> (now - entry.getValue().lastUpdateTimestamp()) > timeoutMs);

        Set<GridPoint> obstacles = new HashSet<>();
        for (Entry entry : entries.values()) {
            obstacles.add(entry.toGridPoint());
        }
        return obstacles;
    }

    public void clear() {
        entries.clear();
    }
}
