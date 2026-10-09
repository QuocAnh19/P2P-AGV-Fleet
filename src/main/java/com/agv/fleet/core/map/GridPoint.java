package com.agv.fleet.core.map;

import com.agv.fleet.core.agent.Position2D;

/**
 * Discrete 2D integer coordinate on the warehouse grid map.
 *
 * @param x Column index (0 to width - 1)
 * @param y Row index (0 to height - 1)
 */
public record GridPoint(int x, int y) {

    /**
     * Calculates Euclidean distance to another grid point.
     */
    public double distance(GridPoint other) {
        if (other == null) {
            return Double.MAX_VALUE;
        }
        return Math.hypot(this.x - other.x, this.y - other.y);
    }

    /**
     * Calculates Manhattan distance (L1 norm) to another grid point.
     */
    public int manhattanDistance(GridPoint other) {
        if (other == null) {
            return Integer.MAX_VALUE;
        }
        return Math.abs(this.x - other.x) + Math.abs(this.y - other.y);
    }

    /**
     * Converts grid coordinates to spatial Position2D.
     */
    public Position2D toPosition2D() {
        return new Position2D(this.x, this.y);
    }

    /**
     * Converts continuous Position2D to discrete GridPoint.
     */
    public static GridPoint fromPosition2D(Position2D pos) {
        if (pos == null) {
            return new GridPoint(0, 0);
        }
        return new GridPoint((int) Math.round(pos.x()), (int) Math.round(pos.y()));
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ")";
    }
}
