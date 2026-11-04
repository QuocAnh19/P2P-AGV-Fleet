package com.agv.fleet.distributed.auction;

import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import com.agv.fleet.common.protocol.payload.TaskAnnouncePayload;
import com.agv.fleet.common.protocol.payload.TaskAwardPayload;
import com.agv.fleet.common.protocol.payload.TaskBidPayload;
import com.agv.fleet.common.protocol.payload.TaskCompletePayload;
import com.agv.fleet.core.agent.AgentState;
import com.agv.fleet.core.agent.AgvBattery;
import com.agv.fleet.core.agent.Position2D;
import com.agv.fleet.core.agent.Task;
import com.agv.fleet.distributed.clock.LamportClock;
import com.agv.fleet.network.mesh.PeerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Distributed Contract Net Protocol (CNP) Engine for decentralized multi-agent task allocation.
 * <p>
 * Manages the complete auction lifecycle:
 * <ol>
 *   <li>Task Announcement (Broadcast by Manager AGV)</li>
 *   <li>Bidding (Calculated cost evaluation by eligible Contractor AGVs)</li>
 *   <li>Bid Evaluation and Task Award (Min-Cost selection and Broadcast Award)</li>
 *   <li>Fault-Tolerance (Re-auctioning upon contractor crash / bid release upon manager crash)</li>
 * </ol>
 */
public class CnpEngine {

    private static final Logger log = LoggerFactory.getLogger(CnpEngine.class);

    public static final long DEFAULT_AUCTION_TIMEOUT_MS = 500;
    public static final double DEFAULT_MAINTENANCE_WEIGHT = 1.0;

    private final long thisNodeId;
    private final PeerManager peerManager;
    private final LamportClock lamportClock;
    private final LongSupplier sequenceSupplier;
    private volatile ScheduledExecutorService scheduler;

    private final Supplier<Double> batterySupplier;
    private final Supplier<Position2D> positionSupplier;
    private final Supplier<AgentState> stateSupplier;
    private final Consumer<AgentState> stateConsumer;

    private final long auctionTimeoutMs;
    private final double maintenanceWeight;

    private final ConcurrentMap<String, AuctionSession> activeAuctions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TaskAnnouncePayload> observedTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> mySubmittedBids = new ConcurrentHashMap<>(); // taskId -> announcerNodeId
    private final CopyOnWriteArrayList<TaskAssignmentListener> assignmentListeners = new CopyOnWriteArrayList<>();

    public CnpEngine(long thisNodeId,
                     PeerManager peerManager,
                     LamportClock lamportClock,
                     LongSupplier sequenceSupplier,
                     ScheduledExecutorService scheduler,
                     Supplier<Double> batterySupplier,
                     Supplier<Position2D> positionSupplier,
                     Supplier<AgentState> stateSupplier,
                     Consumer<AgentState> stateConsumer) {
        this(thisNodeId, peerManager, lamportClock, sequenceSupplier, scheduler,
                batterySupplier, positionSupplier, stateSupplier, stateConsumer,
                DEFAULT_AUCTION_TIMEOUT_MS, DEFAULT_MAINTENANCE_WEIGHT);
    }

    public CnpEngine(long thisNodeId,
                     PeerManager peerManager,
                     LamportClock lamportClock,
                     LongSupplier sequenceSupplier,
                     ScheduledExecutorService scheduler,
                     Supplier<Double> batterySupplier,
                     Supplier<Position2D> positionSupplier,
                     Supplier<AgentState> stateSupplier,
                     Consumer<AgentState> stateConsumer,
                     long auctionTimeoutMs,
                     double maintenanceWeight) {
        this.thisNodeId = thisNodeId;
        this.peerManager = Objects.requireNonNull(peerManager, "peerManager cannot be null");
        this.lamportClock = Objects.requireNonNull(lamportClock, "lamportClock cannot be null");
        this.sequenceSupplier = Objects.requireNonNull(sequenceSupplier, "sequenceSupplier cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.batterySupplier = Objects.requireNonNull(batterySupplier, "batterySupplier cannot be null");
        this.positionSupplier = Objects.requireNonNull(positionSupplier, "positionSupplier cannot be null");
        this.stateSupplier = Objects.requireNonNull(stateSupplier, "stateSupplier cannot be null");
        this.stateConsumer = Objects.requireNonNull(stateConsumer, "stateConsumer cannot be null");
        this.auctionTimeoutMs = auctionTimeoutMs;
        this.maintenanceWeight = maintenanceWeight;
    }

    public void setScheduler(ScheduledExecutorService scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    /**
     * Initiates a new task auction by broadcasting a {@link Opcode#TASK_ANNOUNCE} frame.
     *
     * @param task the transport task to be auctioned
     * @return AuctionSession representing this active bidding round
     */
    public AuctionSession announceTask(Task task) {
        Objects.requireNonNull(task, "task cannot be null");
        task.setStatus(Task.Status.AUCTIONING);
        task.setAnnouncerNodeId(thisNodeId);

        AuctionSession session = new AuctionSession(task, lamportClock.getTime());
        activeAuctions.put(task.getTaskId(), session);

        // Schedule timeout for collecting bids
        ScheduledFuture<?> timeoutTask = scheduler.schedule(
                () -> closeAuction(task.getTaskId()),
                auctionTimeoutMs,
                TimeUnit.MILLISECONDS
        );
        session.setTimeoutTask(timeoutTask);

        // Build and broadcast TASK_ANNOUNCE payload
        TaskAnnouncePayload payload = new TaskAnnouncePayload(
                task.getTaskId(),
                task.getPickupX(),
                task.getPickupY(),
                task.getDeliveryX(),
                task.getDeliveryY(),
                thisNodeId
        );

        long timestamp = lamportClock.tick();
        AgvMessage announceMsg = AgvMessage.create(
                Opcode.TASK_ANNOUNCE,
                sequenceSupplier.getAsLong(),
                timestamp,
                thisNodeId,
                payload.toBytes()
        );

        log.info("Node {}: Announcing task {} for auction (timeout={}ms, peers={})",
                thisNodeId, task.getTaskId(), auctionTimeoutMs, peerManager.getActivePeerCount());
        peerManager.broadcast(announceMsg);

        return session;
    }

    /**
     * Handles an incoming {@link Opcode#TASK_ANNOUNCE} broadcast from a manager AGV.
     *
     * @param message incoming message frame
     */
    public void onTaskAnnounceReceived(AgvMessage message) {
        lamportClock.updateAndGet(message.getHeader().lamportTimestamp());
        TaskAnnouncePayload payload = TaskAnnouncePayload.fromBytes(message.getPayload());

        // Ignore announcements issued by self
        if (payload.announcerNodeId() == thisNodeId) {
            return;
        }

        observedTasks.put(payload.taskId(), payload);

        // 1. Check Battery Threshold (<= 15.0% cannot bid and enters charging)
        double battery = batterySupplier.get();
        if (battery <= AgvBattery.LOW_BATTERY_THRESHOLD) {
            log.warn("Node {}: Battery critical ({}% <= {}%), skipping auction for task {}",
                    thisNodeId, String.format(java.util.Locale.US, "%.1f", battery), AgvBattery.LOW_BATTERY_THRESHOLD, payload.taskId());
            // CnpEngine ONLY refuses bidding; AgvRobot is the sole authority managing charging lifecycle.
            return;
        }

        // 2. Check Operational State: Must be IDLE to participate
        AgentState currentState = stateSupplier.get();
        if (currentState != AgentState.IDLE) {
            log.debug("Node {}: Agent is {} (not IDLE), ignoring auction for task {}",
                    thisNodeId, currentState, payload.taskId());
            return;
        }

        // 3. Compute cost function:
        // dist = Math.hypot(pickupX - currentX, pickupY - currentY)
        // cost = 0.5 * dist + 0.3 * (100.0 - batteryLevel) + 0.2 * maintenanceWeight
        Position2D pos = positionSupplier.get();
        double dist = Math.hypot(payload.pickupX() - pos.x(), payload.pickupY() - pos.y());
        double cost = 0.5 * dist + 0.3 * (100.0 - battery) + 0.2 * maintenanceWeight;

        // 4. Transition to BIDDING and submit unicast TASK_BID to announcer
        stateConsumer.accept(AgentState.BIDDING);
        mySubmittedBids.put(payload.taskId(), payload.announcerNodeId());

        TaskBidPayload bidPayload = new TaskBidPayload(payload.taskId(), thisNodeId, cost, battery);
        long timestamp = lamportClock.tick();
        AgvMessage bidMsg = AgvMessage.create(
                Opcode.TASK_BID,
                sequenceSupplier.getAsLong(),
                timestamp,
                thisNodeId,
                bidPayload.toBytes()
        );

        log.info("Node {}: Submitted BID for task {} to Node {} (dist={.2f}m, battery={.1f}%, cost={.2f})",
                thisNodeId, payload.taskId(), payload.announcerNodeId(), dist, battery, cost);
        peerManager.send(payload.announcerNodeId(), bidMsg);
    }

    /**
     * Handles an incoming {@link Opcode#TASK_BID} response from a contractor AGV.
     *
     * @param message incoming message frame
     */
    public void onTaskBidReceived(AgvMessage message) {
        lamportClock.updateAndGet(message.getHeader().lamportTimestamp());
        TaskBidPayload bid = TaskBidPayload.fromBytes(message.getPayload());

        AuctionSession session = activeAuctions.get(bid.taskId());
        if (session != null && !session.isClosed()) {
            session.addBid(bid);
            log.debug("Node {}: Accepted bid from Node {} for task {} (cost={.2f})",
                    thisNodeId, bid.bidderNodeId(), bid.taskId(), bid.cost());
        }
    }

    /**
     * Closes an auction session, evaluates candidate bids, and awards the task to the min-cost bidder.
     *
     * @param taskId task identifier
     */
    public void closeAuction(String taskId) {
        AuctionSession session = activeAuctions.get(taskId);
        if (session == null || !session.close()) {
            return;
        }

        Collection<TaskBidPayload> bids = session.getBids();
        if (bids.isEmpty()) {
            log.info("Node {}: Auction closed for task {} with 0 bids. Reverting task to UNASSIGNED.",
                    thisNodeId, taskId);
            session.getTask().setStatus(Task.Status.UNASSIGNED);
            session.getAwardFuture().complete(-1L);
            return;
        }

        // Find min-cost bidder (tie broken by lowest Node ID)
        TaskBidPayload bestBid = bids.stream()
                .min(Comparator.comparingDouble(TaskBidPayload::cost)
                        .thenComparingLong(TaskBidPayload::bidderNodeId))
                .orElseThrow();

        long winnerNodeId = bestBid.bidderNodeId();
        session.getTask().setStatus(Task.Status.ASSIGNED);
        session.getTask().setAssignedNodeId(winnerNodeId);
        session.getAwardFuture().complete(winnerNodeId);

        // Broadcast TASK_AWARD (Opcode 0x22)
        TaskAwardPayload awardPayload = new TaskAwardPayload(taskId, winnerNodeId);
        long timestamp = lamportClock.tick();
        AgvMessage awardMsg = AgvMessage.create(
                Opcode.TASK_AWARD,
                sequenceSupplier.getAsLong(),
                timestamp,
                thisNodeId,
                awardPayload.toBytes()
        );

        log.info("Node {}: AWARDED task {} to winning Node {} (minCost={.2f}). Broadcasting TASK_AWARD.",
                thisNodeId, taskId, winnerNodeId, bestBid.cost());
        peerManager.broadcast(awardMsg);
    }

    /**
     * Handles an incoming {@link Opcode#TASK_AWARD} announcement.
     *
     * @param message incoming message frame
     */
    public void onTaskAwardReceived(AgvMessage message) {
        lamportClock.updateAndGet(message.getHeader().lamportTimestamp());
        TaskAwardPayload award = TaskAwardPayload.fromBytes(message.getPayload());

        String taskId = award.taskId();
        long winnerNodeId = award.winnerNodeId();

        if (winnerNodeId == thisNodeId) {
            // This AGV won the task!
            stateConsumer.accept(AgentState.BUSY);

            TaskAnnouncePayload announce = observedTasks.get(taskId);
            Task task;
            if (announce != null) {
                task = new Task(taskId, announce.pickupX(), announce.pickupY(), announce.deliveryX(), announce.deliveryY());
            } else {
                task = new Task(taskId, 0, 0, 0, 0);
            }
            task.setStatus(Task.Status.ASSIGNED);
            task.setAssignedNodeId(thisNodeId);
            long announcerId = (announce != null) ? announce.announcerNodeId() : message.getHeader().senderNodeId();
            task.setAnnouncerNodeId(announcerId);

            log.info("Node {}: WON auction for task {}! Transitioned state to BUSY.", thisNodeId, taskId);
            for (TaskAssignmentListener listener : assignmentListeners) {
                try {
                    listener.onTaskAssigned(task);
                } catch (Exception e) {
                    log.error("Error notifying task assignment listener", e);
                }
            }
        } else {
            // Another AGV won. If this AGV was bidding for this task, revert to IDLE
            if (mySubmittedBids.containsKey(taskId)) {
                if (stateSupplier.get() == AgentState.BIDDING) {
                    stateConsumer.accept(AgentState.IDLE);
                    log.info("Node {}: Lost auction for task {} to Node {}. Reverted state to IDLE.",
                            thisNodeId, taskId, winnerNodeId);
                }
            }
        }

        mySubmittedBids.remove(taskId);
    }

    /**
     * Handles incoming {@link Opcode#TASK_COMPLETE} from a contractor that finished its assigned task.
     *
     * @param message incoming message frame
     */
    public void onTaskCompleteReceived(AgvMessage message) {
        lamportClock.updateAndGet(message.getHeader().lamportTimestamp());
        TaskCompletePayload payload = TaskCompletePayload.fromBytes(message.getPayload());
        onTaskComplete(payload.taskId(), payload.contractorNodeId());
    }

    /**
     * Marks the task as completed and cleanly removes its session from activeAuctions.
     *
     * @param taskId           completed task ID
     * @param contractorNodeId node ID of contractor that completed the task
     */
    public void onTaskComplete(String taskId, long contractorNodeId) {
        log.info("Node {}: Received TASK_COMPLETE for task {} from contractor Node {}",
                thisNodeId, taskId, contractorNodeId);
        AuctionSession session = activeAuctions.get(taskId);
        if (session != null) {
            session.getTask().setStatus(Task.Status.COMPLETED);
            activeAuctions.remove(taskId);
        }
    }

    /**
     * Handles fault-tolerance when a connected peer drops or crashes.
     *
     * @param droppedNodeId ID of the dropped node
     */
    public void onPeerDropped(long droppedNodeId) {
        // Case 1: The dropped node was the contractor holding an assigned task we announced
        for (AuctionSession session : activeAuctions.values()) {
            Task task = session.getTask();
            if (task.getStatus() == Task.Status.ASSIGNED && Objects.equals(task.getAssignedNodeId(), droppedNodeId)) {
                log.warn("Node {}: Assigned contractor Node {} crashed before completing task {}. Re-announcing task!",
                        thisNodeId, droppedNodeId, task.getTaskId());
                activeAuctions.remove(task.getTaskId());
                announceTask(task);
            }
        }

        // Case 2: The dropped node was the announcer of an auction we were bidding on
        mySubmittedBids.entrySet().removeIf(entry -> {
            String taskId = entry.getKey();
            Long announcerId = entry.getValue();
            if (announcerId != null && announcerId == droppedNodeId) {
                if (stateSupplier.get() == AgentState.BIDDING) {
                    stateConsumer.accept(AgentState.IDLE);
                    log.info("Node {}: Announcer Node {} crashed during auction for task {}. Reverted state to IDLE.",
                            thisNodeId, droppedNodeId, taskId);
                }
                return true;
            }
            return false;
        });
    }

    public void addAssignmentListener(TaskAssignmentListener listener) {
        assignmentListeners.add(Objects.requireNonNull(listener, "listener cannot be null"));
    }

    public void removeAssignmentListener(TaskAssignmentListener listener) {
        assignmentListeners.remove(listener);
    }

    public AuctionSession getAuctionSession(String taskId) {
        return activeAuctions.get(taskId);
    }

    public long getThisNodeId() {
        return thisNodeId;
    }
}
