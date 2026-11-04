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

    public AgvRobot(AgvNode node, GridMap map, DynamicAStarPathfinder pathfinder, GridPoint initialPos) {
        this.node = Objects.requireNonNull(node, "node cannot be null");
        this.map = Objects.requireNonNull(map, "map cannot be null");
        this.pathfinder = Objects.requireNonNull(pathfinder, "pathfinder cannot be null");
        this.currentGridPos = Objects.requireNonNull(initialPos, "initialPos cannot be null");
        this.renderX = initialPos.x();
        this.renderY = initialPos.y();
        this.node.setPosition(initialPos.toPosition2D());

        // Wire task assignment listener
        this.node.addAssignmentListener(this::onTaskAssigned);
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
            }
        } finally {
            stateLock.unlock();
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
    public void updatePhysicsAndLogic(Set<GridPoint> dynamicObstacles) {
        if (crashed || paused) {
            return;
        }

        stateLock.lock();
        try {
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
                if (dynamicObstacles != null && dynamicObstacles.contains(nextCell)) {
                    log.debug("AGV {}: Yielding step to dynamic obstacle at {}", node.getNodeId(), nextCell);
                    return;
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

                // If moving out of an intersection, release it!
                if (heldIntersectionCellId != null && !heldIntersectionCellId.equals(map.getIntersectionCellId(currentGridPos))) {
                    log.info("AGV {}: Cleared intersection {}. Releasing MUTEX.",
                            node.getNodeId(), heldIntersectionCellId);
                    node.releaseIntersection(heldIntersectionCellId);
                    heldIntersectionCellId = null;
                }

                if (node.getAgentState() != AgentState.WAITING_MUTEX && node.getAgentState() != AgentState.LOW_BATTERY_CHARGING) {
                    node.setAgentState(AgentState.MOVING);
                }
            } else {
                // Path queue empty: Arrived at current destination target
                handleArrivalAtDestination();
            }
        } finally {
            stateLock.unlock();
        }
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
            currentGridPos = Objects.requireNonNull(newPos, "newPos cannot be null");
            renderX = newPos.x();
            renderY = newPos.y();
            node.setPosition(newPos.toPosition2D());
            if (!crashed) {
                node.setAgentState(AgentState.IDLE);
            }
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
