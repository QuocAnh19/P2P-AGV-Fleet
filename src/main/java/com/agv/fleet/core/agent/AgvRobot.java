package com.agv.fleet.core.agent;

import com.agv.fleet.core.AgvNode;
import com.agv.fleet.core.map.GridMap;
import com.agv.fleet.core.map.GridPoint;
import com.agv.fleet.core.navigation.DynamicAStarPathfinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Autonomous AGV Robot Controller executing in the warehouse environment.
 * <p>
 * Seamlessly integrates:
 * <ul>
 *   <li>P2P Mesh Network Node ({@link AgvNode})</li>
 *   <li>Dynamic A* Pathfinding ({@link DynamicAStarPathfinder})</li>
 *   <li>Ricart-Agrawala Mutual Exclusion for crossing intersections</li>
 *   <li>Contract Net Protocol Task Execution (Pickup & Delivery)</li>
 *   <li>On-board Battery & Automated Charging</li>
 * </ul>
 */
public class AgvRobot {

    private static final Logger log = LoggerFactory.getLogger(AgvRobot.class);

    private final AgvNode node;
    private final GridMap map;
    private final DynamicAStarPathfinder pathfinder;

    private final ReentrantLock stateLock = new ReentrantLock();

    private volatile GridPoint currentGridPos;
    private volatile GridPoint targetGridPos;
    private volatile double renderX;
    private volatile double renderY;

    private final Deque<GridPoint> pathQueue = new ConcurrentLinkedDeque<>();
    private volatile Task currentTask;
    private volatile boolean carryingCargo = false;

    private volatile String heldIntersectionCellId = null;
    private volatile String waitingIntersectionCellId = null;
    private volatile CompletableFuture<Void> mutexGrantFuture = null;

    private volatile boolean crashed = false;
    private volatile boolean paused = false;
    private volatile String modelName = "Pallet AGV-Pro";
    private volatile long totalTasksCompleted = 0;

    // Corridor collision avoidance and siding yielding (Task 5)
    private int consecutiveBlockedTicks = 0;
    private GridPoint lastBlockedCell = null;
    private int backoffTicks = 0;
    private boolean yieldingToSiding = false;
    private GridPoint originalTargetPos = null;
    private GridPoint sidingEntryCell = null;
    private Long yieldingForPeerId = null;
    private final Random seededRandom;

    public AgvRobot(AgvNode node, GridMap map, DynamicAStarPathfinder pathfinder, GridPoint initialPos) {
        this.node = Objects.requireNonNull(node, "node cannot be null");
        this.map = Objects.requireNonNull(map, "map cannot be null");
        this.pathfinder = Objects.requireNonNull(pathfinder, "pathfinder cannot be null");
        this.currentGridPos = Objects.requireNonNull(initialPos, "initialPos cannot be null");
        this.renderX = initialPos.x();
        this.renderY = initialPos.y();
        this.node.setPosition(initialPos.toPosition2D());
        this.seededRandom = new Random(node != null ? node.getNodeId() * 31L + 17L : 42L);

        // Wire task assignment listener
        this.node.addAssignmentListener(this::onTaskAssigned);
        this.node.getCnpEngine().setAllowSelfBidding(true);
        this.node.getCnpEngine().setHasPendingTaskSupplier(() -> this.currentTask != null);
    }

    /**
     * Callback when this AGV wins a task auction via Contract Net Protocol.
     */
    private void onTaskAssigned(Task task) {
        stateLock.lock();
        try {
            if (crashed) {
                return;
            }
            log.info("AGV {}: Assigned Task {} (Pickup at {},{} -> Delivery at {},{})",
                    node.getNodeId(), task.getTaskId(),
                    task.getPickupX(), task.getPickupY(),
                    task.getDeliveryX(), task.getDeliveryY());

            this.currentTask = task;
            this.carryingCargo = false;

            GridPoint pickupPoint = new GridPoint((int) Math.round(task.getPickupX()), (int) Math.round(task.getPickupY()));
            setDestination(pickupPoint);
            node.setAgentState(AgentState.MOVING);
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Directs the AGV to plan and follow a route to a target destination.
     */
    public void setDestination(GridPoint destination) {
        stateLock.lock();
        try {
            this.targetGridPos = destination;
            List<GridPoint> path = pathfinder.findPath(map, currentGridPos, destination);
            pathQueue.clear();
            if (!path.isEmpty()) {
                // Remove the starting cell
                for (int i = 1; i < path.size(); i++) {
                    pathQueue.add(path.get(i));
                }
                if (node != null && node.getAgentState() != AgentState.LOW_BATTERY_CHARGING) {
                    node.setAgentState(AgentState.MOVING);
                }
            }
            broadcastPosition();
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Broadcasts real-time position to peer mesh network using POSITION_UPDATE (Opcode 0x03).
     */
    public void broadcastPosition() {
        if (node != null && !crashed) {
            node.setPosition(currentGridPos.toPosition2D());
            node.setTargetPosition(targetGridPos != null ? targetGridPos.toPosition2D() : currentGridPos.toPosition2D());
            com.agv.fleet.common.protocol.payload.PositionUpdatePayload payload =
                    new com.agv.fleet.common.protocol.payload.PositionUpdatePayload(
                            currentGridPos.x(),
                            currentGridPos.y(),
                            targetGridPos != null ? targetGridPos.x() : currentGridPos.x(),
                            targetGridPos != null ? targetGridPos.y() : currentGridPos.y(),
                            node.getAgentState()
                    );
            node.broadcastPositionUpdate(payload);
        }
    }

    /**
     * Core update loop called periodically by the simulation engine (e.g. 5-10 times per second).
     * <p>
     * <b>Battery Safety Policy:</b>
     * If the battery level drops to or below {@link AgvBattery#LOW_BATTERY_THRESHOLD} (<= 15%),
     * the robot checks whether it currently holds an active order ({@code currentTask != null}).
     * If so, the robot will first complete the current task to avoid stranding cargo mid-aisle,
     * and will navigate to the nearest charging station immediately upon task completion.
     * If no task is active, the robot transitions to {@link AgentState#LOW_BATTERY_CHARGING}
     * and immediately routes to the nearest charging station.
     * </p>
     *
     * @param dynamicObstacles positions of other AGVs to prevent physical collisions
     */
    public void updatePhysicsAndLogic(Set<GridPoint> simulatorObstacles) {
        if (crashed || paused) {
            return;
        }

        stateLock.lock();
        try {
            if (backoffTicks > 0) {
                backoffTicks--;
                return;
            }

            // Dynamic obstacles are sourced directly from this AGV node's PeerPositionTable (pure P2P)
            Set<GridPoint> dynamicObstacles;
            if (node != null && node.getPeerPositionTable() != null) {
                dynamicObstacles = node.getPeerPositionTable().getActiveObstaclePoints();
            } else {
                dynamicObstacles = (simulatorObstacles != null) ? simulatorObstacles : java.util.Collections.emptySet();
            }

            // If currently yielding in a siding, check if corridor is clear to resume original destination
            if (yieldingToSiding) {
                if (pathQueue.isEmpty()) {
                    if (node != null && node.getAgentState() != AgentState.IDLE) {
                        node.setAgentState(AgentState.IDLE);
                        broadcastPosition();
                    }
                    boolean entryClear = dynamicObstacles == null || !dynamicObstacles.contains(sidingEntryCell);
                    if (entryClear && originalTargetPos != null) {
                        List<GridPoint> resumePath = pathfinder.findPath(map, currentGridPos, originalTargetPos, dynamicObstacles);
                        if (!resumePath.isEmpty()) {
                            log.info("AGV {}: Corridor clear! Exiting siding {} to resume route to {}",
                                    node.getNodeId(), currentGridPos, originalTargetPos);
                            yieldingToSiding = false;
                            targetGridPos = originalTargetPos;
                            originalTargetPos = null;
                            sidingEntryCell = null;
                            yieldingForPeerId = null;
                            pathQueue.clear();
                            for (int i = 1; i < resumePath.size(); i++) {
                                pathQueue.add(resumePath.get(i));
                            }
                            if (node != null) {
                                node.setAgentState(AgentState.MOVING);
                            }
                            broadcastPosition();
                        }
                    }
                    return; // Wait inside siding until path is clear
                }
            }

            // 1. Battery Safety & Automated Recharge Handling
            double batteryLevel = node.getBattery().getLevel();
            if (batteryLevel <= AgvBattery.LOW_BATTERY_THRESHOLD) {
                // Policy: complete currentTask first before heading to recharge
                if (currentTask == null) {
                    if (node.getAgentState() != AgentState.LOW_BATTERY_CHARGING) {
                        node.setAgentState(AgentState.LOW_BATTERY_CHARGING);
                        GridPoint station = map.getNearestChargingStation(currentGridPos);
                        if (station != null && !station.equals(targetGridPos)) {
                            setDestination(station);
                        }
                    }
                }
            }

            // Safety fallback: if state is LOW_BATTERY_CHARGING, not at a charging station,
            // and pathQueue is empty, automatically set destination to the nearest charging station.
            if (node.getAgentState() == AgentState.LOW_BATTERY_CHARGING
                    && !map.isChargingStation(currentGridPos)
                    && pathQueue.isEmpty()) {
                GridPoint station = map.getNearestChargingStation(currentGridPos);
                if (station != null && !station.equals(targetGridPos)) {
                    setDestination(station);
                }
            }

            if (map.isChargingStation(currentGridPos)) {
                // In charging dock: recharge battery
                node.getBattery().setLevel(Math.min(100.0, node.getBattery().getLevel() + 2.5));
                if (node.getBattery().getLevel() >= 95.0 && node.getAgentState() == AgentState.LOW_BATTERY_CHARGING) {
                    node.setAgentState(AgentState.IDLE);
                }
                return;
            }

            // 2. Mutual Exclusion Check for Intersections
            if (mutexGrantFuture != null) {
                if (mutexGrantFuture.isDone()) {
                    // Critical section granted!
                    heldIntersectionCellId = waitingIntersectionCellId;
                    waitingIntersectionCellId = null;
                    mutexGrantFuture = null;
                    node.setAgentState(AgentState.MOVING);
                    log.info("AGV {}: Acquired MUTEX for {}. Proceeding into intersection.",
                            node.getNodeId(), heldIntersectionCellId);
                } else {
                    // Still waiting for replies from peer mesh
                    node.setAgentState(AgentState.WAITING_MUTEX);
                    return; // Stopped waiting
                }
            }

            // 3. Navigation Step
            if (!pathQueue.isEmpty()) {
                GridPoint nextCell = pathQueue.peek();

                // Check dynamic collision: Don't step into cell currently occupied by another AGV
                boolean isBlocked = dynamicObstacles != null && dynamicObstacles.contains(nextCell);

                // Also check step contention: if peer is also 1 step away from nextCell and actively MOVING, tie-break by node ID
                // (Intersections are exclusively governed by Ricart-Agrawala distributed mutex)
                if (!isBlocked && !map.isIntersection(nextCell) && node != null && node.getPeerPositionTable() != null) {
                    for (PeerPositionTable.Entry entry : node.getPeerPositionTable().getAll().values()) {
                        if (entry.nodeId() == node.getNodeId()) continue;
                        if (entry.state() != AgentState.MOVING) continue;
                        GridPoint peerPos = entry.toGridPoint();
                        int dist = Math.abs(peerPos.x() - nextCell.x()) + Math.abs(peerPos.y() - nextCell.y());
                        if (dist == 1 && node.getNodeId() < entry.nodeId()) {
                            // Lower node ID yields step contention to higher node with seeded backoff to break symmetry
                            isBlocked = true;
                            backoffTicks = 1 + seededRandom.nextInt(2);
                            break;
                        }
                    }
                }

                if (isBlocked) {
                    if (nextCell.equals(lastBlockedCell)) {
                        consecutiveBlockedTicks++;
                    } else {
                        lastBlockedCell = nextCell;
                        consecutiveBlockedTicks = 1;
                    }

                    int maxWaitTicks = map.isIntersection(nextCell) ? 45 : 12;
                    if (consecutiveBlockedTicks <= maxWaitTicks) {
                        log.debug("AGV {}: Queuing behind dynamic obstacle at {} (tick {})",
                                node.getNodeId(), nextCell, consecutiveBlockedTicks);
                        return;
                    }

                    // If blocked at intersection threshold, remain waiting in queue
                    if (map.isIntersection(nextCell)) {
                        return;
                    }

                    // Blocked for > 3 consecutive ticks!
                    // Step 1: Try replan avoiding blocked cell
                    Set<GridPoint> blockedSet = new HashSet<>(dynamicObstacles);
                    blockedSet.add(nextCell);
                    List<GridPoint> alternativePath = pathfinder.findPath(map, currentGridPos, targetGridPos, blockedSet);
                    if (alternativePath != null && alternativePath.size() > 1) {
                        log.info("AGV {}: Re-routed around blocked cell {}. New path length: {}",
                                node.getNodeId(), nextCell, alternativePath.size());
                        pathQueue.clear();
                        for (int i = 1; i < alternativePath.size(); i++) {
                            pathQueue.add(alternativePath.get(i));
                        }
                        consecutiveBlockedTicks = 0;
                        lastBlockedCell = null;
                        backoffTicks = seededRandom.nextInt(2);
                        return;
                    }

                    // Step 2: Still no path! Need to reverse/yield to nearest empty cell (siding)
                    // Rule: higher node ID yields.
                    Long otherNodeId = (node != null && node.getPeerPositionTable() != null)
                            ? node.getPeerPositionTable().findPeerAt(nextCell)
                            : null;
                    if (otherNodeId == null && node != null && node.getPeerPositionTable() != null) {
                        for (PeerPositionTable.Entry entry : node.getPeerPositionTable().getAll().values()) {
                            if (entry.nodeId() != node.getNodeId()) {
                                otherNodeId = entry.nodeId();
                                break;
                            }
                        }
                    }

                    long myNodeId = node != null ? node.getNodeId() : 0L;
                    boolean isHigherNode = otherNodeId == null || myNodeId > otherNodeId;

                    // Lower node ID waits for higher node ID to yield (fallback after 8 ticks to avoid permanent deadlock)
                    if (!isHigherNode && consecutiveBlockedTicks <= 8) {
                        backoffTicks = 1 + seededRandom.nextInt(2);
                        log.debug("AGV {}: Lower nodeId than peer {}. Holding position with backoff {}",
                                myNodeId, otherNodeId, backoffTicks);
                        return;
                    }

                    // Find nearest empty cell (siding)
                    GridPoint siding = findNearestSidingCell(currentGridPos, nextCell, dynamicObstacles);
                    if (siding != null) {
                        log.info("AGV {}: Yielding into siding {} for peer {}", myNodeId, siding, otherNodeId);
                        yieldingToSiding = true;
                        originalTargetPos = targetGridPos;
                        sidingEntryCell = currentGridPos;
                        yieldingForPeerId = otherNodeId;
                        pathQueue.clear();
                        pathQueue.add(siding);
                        consecutiveBlockedTicks = 0;
                        lastBlockedCell = null;
                        return;
                    } else {
                        backoffTicks = 1 + seededRandom.nextInt(2);
                        return;
                    }
                } else {
                    consecutiveBlockedTicks = 0;
                    lastBlockedCell = null;
                }

                // Check intersection entry: Must obtain Ricart-Agrawala Mutex before entering!
                if (map.isIntersection(nextCell)) {
                    String intersectionId = map.getIntersectionCellId(nextCell);
                    if (!intersectionId.equals(heldIntersectionCellId)) {
                        // Request intersection permission
                        waitingIntersectionCellId = intersectionId;
                        mutexGrantFuture = node.acquireIntersection(intersectionId);
                        node.setAgentState(AgentState.WAITING_MUTEX);
                        log.info("AGV {}: Approaching intersection {}. Requesting MUTEX...",
                                node.getNodeId(), intersectionId);
                        return; // Halt before entering
                    }
                }

                // Move into next cell
                pathQueue.poll();
                GridPoint previousPos = currentGridPos;
                currentGridPos = nextCell;
                node.setPosition(currentGridPos.toPosition2D());
                node.getBattery().consume(0.1); // 0.1% per cell traversed

                if (node.getAgentState() != AgentState.WAITING_MUTEX && node.getAgentState() != AgentState.LOW_BATTERY_CHARGING) {
                    node.setAgentState(AgentState.MOVING);
                }

                broadcastPosition();

                // If moving out of an intersection, release it!
                if (heldIntersectionCellId != null && !heldIntersectionCellId.equals(map.getIntersectionCellId(currentGridPos))) {
                    log.info("AGV {}: Cleared intersection {}. Releasing MUTEX.",
                            node.getNodeId(), heldIntersectionCellId);
                    node.releaseIntersection(heldIntersectionCellId);
                    heldIntersectionCellId = null;
                }
            } else {
                if (yieldingToSiding) {
                    // In siding waiting for corridor to clear
                    return;
                }
                // Path queue empty: Arrived at current destination target
                handleArrivalAtDestination();
            }
        } finally {
            stateLock.unlock();
        }
    }

    private GridPoint findNearestSidingCell(GridPoint current, GridPoint blockedCell, Set<GridPoint> dynamicObstacles) {
        int[][] directions = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        GridPoint bestCandidate = null;

        for (int[] dir : directions) {
            GridPoint neighbor = new GridPoint(current.x() + dir[0], current.y() + dir[1]);
            if (neighbor.equals(blockedCell) || neighbor.equals(current)) {
                continue;
            }
            if (!map.isWalkable(neighbor)) {
                continue;
            }
            if (dynamicObstacles != null && dynamicObstacles.contains(neighbor)) {
                continue;
            }

            boolean isSidingBranch = (blockedCell.y() == current.y() && neighbor.y() != current.y())
                    || (blockedCell.x() == current.x() && neighbor.x() != current.x());
            if (isSidingBranch) {
                return neighbor;
            }
            if (bestCandidate == null) {
                bestCandidate = neighbor;
            }
        }
        return bestCandidate;
    }

    /**
     * Smooth visual rendering coordinate interpolation.
     *
     * @param deltaSeconds time elapsed in seconds
     */
    public void interpolateVisualPosition(double deltaSeconds) {
        double lerpSpeed = Math.min(1.0, 8.0 * deltaSeconds);
        this.renderX += (currentGridPos.x() - renderX) * lerpSpeed;
        this.renderY += (currentGridPos.y() - renderY) * lerpSpeed;
    }

    private void handleArrivalAtDestination() {
        if (currentTask != null) {
            if (!carryingCargo) {
                // Arrived at Pickup!
                carryingCargo = true;
                log.info("AGV {}: Picked up cargo for task {}. Navigating to delivery at ({},{})",
                        node.getNodeId(), currentTask.getTaskId(), currentTask.getDeliveryX(), currentTask.getDeliveryY());
                GridPoint deliveryPoint = new GridPoint((int) Math.round(currentTask.getDeliveryX()), (int) Math.round(currentTask.getDeliveryY()));
                setDestination(deliveryPoint);
            } else {
                // Arrived at Delivery!
                carryingCargo = false;
                currentTask.setStatus(Task.Status.COMPLETED);
                totalTasksCompleted++;
                log.info("AGV {}: Successfully completed Task {} at destination!", node.getNodeId(), currentTask.getTaskId());

                Long announcerId = currentTask.getAnnouncerNodeId();
                if (announcerId != null) {
                    node.notifyTaskComplete(currentTask.getTaskId(), announcerId);
                }
                currentTask = null;
                node.setAgentState(AgentState.IDLE);
            }
        } else {
            if (node.getAgentState() == AgentState.MOVING) {
                node.setAgentState(AgentState.IDLE);
            }
        }
    }

    /**
     * Teleports the robot directly to a designated grid coordinate (used in simulation testing / conflict setup).
     */
    public void teleportTo(GridPoint newPos) {
        stateLock.lock();
        try {
            if (heldIntersectionCellId != null) {
                try {
                    node.releaseIntersection(heldIntersectionCellId);
                } catch (Exception ignored) {
                }
                heldIntersectionCellId = null;
            }
            waitingIntersectionCellId = null;
            mutexGrantFuture = null;
            pathQueue.clear();
            currentTask = null;
            carryingCargo = false;
            yieldingToSiding = false;
            originalTargetPos = null;
            sidingEntryCell = null;
            yieldingForPeerId = null;
            backoffTicks = 0;
            consecutiveBlockedTicks = 0;
            lastBlockedCell = null;
            currentGridPos = Objects.requireNonNull(newPos, "newPos cannot be null");
            renderX = newPos.x();
            renderY = newPos.y();
            node.setPosition(newPos.toPosition2D());
            if (!crashed) {
                node.setAgentState(AgentState.IDLE);
            }
            broadcastPosition();
            log.info("AGV {}: Repositioned to {}", node.getNodeId(), newPos);
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Recovers / reboots a crashed AGV node.
     */
    public void recover() {
        stateLock.lock();
        try {
            if (!crashed) return;
            crashed = false;
            pathQueue.clear();
            waitingIntersectionCellId = null;
            heldIntersectionCellId = null;
            mutexGrantFuture = null;
            node.setAgentState(AgentState.IDLE);
            try {
                node.start();
                log.info("AGV {}: Node successfully rebooted and rejoined mesh!", node.getNodeId());
            } catch (Exception e) {
                log.error("Failed to restart node {}", node.getNodeId(), e);
            }
        } finally {
            stateLock.unlock();
        }
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        log.info("AGV {}: Paused state set to {}", node.getNodeId(), paused);
    }

    public boolean isPaused() {
        return paused;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public long getTotalTasksCompleted() {
        return totalTasksCompleted;
    }

    /**
     * Simulates node crash / power failure.
     */
    public void crash() {
        stateLock.lock();
        try {
            if (crashed) {
                return;
            }
            crashed = true;
            node.setAgentState(AgentState.CRASHED);

            // Power failure simulation: do NOT proactively release intersection or send replies
            heldIntersectionCellId = null;

            pathQueue.clear();
            node.crash();
            log.warn("AGV {}: Terminated and crashed!", node.getNodeId());
        } finally {
            stateLock.unlock();
        }
    }

    // Getters
    public long getNodeId() {
        return node.getNodeId();
    }

    public AgvNode getNode() {
        return node;
    }

    public AgentState getState() {
        return node.getAgentState();
    }

    public double getBatteryLevel() {
        return node.getBattery().getLevel();
    }

    public GridPoint getCurrentGridPos() {
        return currentGridPos;
    }

    public GridPoint getTargetGridPos() {
        return targetGridPos;
    }

    public double getRenderX() {
        return renderX;
    }

    public double getRenderY() {
        return renderY;
    }

    public Task getCurrentTask() {
        return currentTask;
    }

    public boolean isCarryingCargo() {
        return carryingCargo;
    }

    public boolean isCrashed() {
        return crashed;
    }

    public String getHeldIntersection() {
        return heldIntersectionCellId;
    }

    public String getWaitingIntersection() {
        return waitingIntersectionCellId;
    }

    public List<GridPoint> getRemainingPath() {
        return new ArrayList<>(pathQueue);
    }
}
