package com.agv.fleet.core.agent;

/**
 * 2D spatial coordinate representing the position of an AGV or task destination in the warehouse.
 *
 * @param x X-coordinate in meters
 * @param y Y-coordinate in meters
 */
public record Position2D(double x, double y) {

    /**
     * Calculates Euclidean distance to another position.
     *
     * @param other target position
     * @return Euclidean distance in meters
     */
    public double distance(Position2D other) {
        if (other == null) {
            return Double.MAX_VALUE;
        }
        return Math.hypot(this.x - other.x, this.y - other.y);
    }

    /**
     * Calculates Euclidean distance to specific (x, y) coordinates.
     *
     * @param targetX target X
     * @param targetY target Y
     * @return Euclidean distance in meters
     */
    public double distance(double targetX, double targetY) {
        return Math.hypot(this.x - targetX, this.y - targetY);
    }
}
