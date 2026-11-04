package com.agv.fleet.core.agent;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Domain model representing a warehouse transport task to be allocated via Contract Net Protocol.
 */
public class Task {

    /**
     * Lifecycle statuses of a transport task.
     */
    public enum Status {
        UNASSIGNED,
        AUCTIONING,
        ASSIGNED,
        IN_TRANSIT,
        COMPLETED,
        CANCELLED
    }

    /**
     * Priority levels for warehouse enterprise orders.
     */
    public enum Priority {
        LOW,
        NORMAL,
        HIGH,
        URGENT
    }

    private final String taskId;
    private final double pickupX;
    private final double pickupY;
    private final double deliveryX;
    private final double deliveryY;
    private Priority priority = Priority.NORMAL;
    private String cargoType = "Standard Pallet";
    private final long creationTimestamp;

    private final ReentrantLock lock = new ReentrantLock();
    private Status status = Status.UNASSIGNED;
    private Long assignedNodeId = null;
    private Long announcerNodeId = null;

    public Task(String taskId, double pickupX, double pickupY, double deliveryX, double deliveryY) {
        this(taskId, pickupX, pickupY, deliveryX, deliveryY, Priority.NORMAL, "Standard Pallet");
    }

    public Task(String taskId, double pickupX, double pickupY, double deliveryX, double deliveryY, Priority priority, String cargoType) {
        this.taskId = Objects.requireNonNull(taskId, "taskId cannot be null");
        this.pickupX = pickupX;
        this.pickupY = pickupY;
        this.deliveryX = deliveryX;
        this.deliveryY = deliveryY;
        this.priority = priority != null ? priority : Priority.NORMAL;
        this.cargoType = cargoType != null ? cargoType : "Standard Pallet";
        this.creationTimestamp = System.currentTimeMillis();
    }

    public String getTaskId() {
        return taskId;
    }

    public double getPickupX() {
        return pickupX;
    }

    public double getPickupY() {
        return pickupY;
    }

    public double getDeliveryX() {
        return deliveryX;
    }

    public double getDeliveryY() {
        return deliveryY;
    }

    public Position2D getPickupPosition() {
        return new Position2D(pickupX, pickupY);
    }

    public Position2D getDeliveryPosition() {
        return new Position2D(deliveryX, deliveryY);
    }

    public Status getStatus() {
        lock.lock();
        try {
            return status;
        } finally {
            lock.unlock();
        }
    }

    public void setStatus(Status status) {
        lock.lock();
        try {
            this.status = Objects.requireNonNull(status, "status cannot be null");
        } finally {
            lock.unlock();
        }
    }

    public Long getAssignedNodeId() {
        lock.lock();
        try {
            return assignedNodeId;
        } finally {
            lock.unlock();
        }
    }

    public void setAssignedNodeId(Long assignedNodeId) {
        lock.lock();
        try {
            this.assignedNodeId = assignedNodeId;
        } finally {
            lock.unlock();
        }
    }

    public Long getAnnouncerNodeId() {
        lock.lock();
        try {
            return announcerNodeId;
        } finally {
            lock.unlock();
        }
    }

    public void setAnnouncerNodeId(Long announcerNodeId) {
        lock.lock();
        try {
            this.announcerNodeId = announcerNodeId;
        } finally {
            lock.unlock();
        }
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = Objects.requireNonNull(priority, "priority cannot be null");
    }

    public String getCargoType() {
        return cargoType;
    }

    public void setCargoType(String cargoType) {
        this.cargoType = Objects.requireNonNull(cargoType, "cargoType cannot be null");
    }

    public long getCreationTimestamp() {
        return creationTimestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Task task = (Task) o;
        return Objects.equals(taskId, task.taskId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(taskId);
    }

    @Override
    public String toString() {
        lock.lock();
        try {
            return String.format("Task[id='%s', pickup=(%.1f, %.1f), delivery=(%.1f, %.1f), status=%s, assignedNode=%s]",
                    taskId, pickupX, pickupY, deliveryX, deliveryY, status, assignedNodeId);
        } finally {
            lock.unlock();
        }
    }
}
