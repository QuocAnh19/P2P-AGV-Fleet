package com.agv.fleet.core.navigation;

import com.agv.fleet.core.map.GridMap;
import com.agv.fleet.core.map.GridPoint;

import java.util.*;

/**
 * Dynamic A* (A-Star) Pathfinding Engine on 2D warehouse grid.
 * <p>
 * Finds the shortest collision-free path between start and goal locations,
 * strictly avoiding static obstacles (racks) and dynamic obstacles (other stopped AGVs).
 */
public class DynamicAStarPathfinder {

    private static final int[][] DIRECTIONS = {
            {0, -1}, // North
            {1, 0},  // East
            {0, 1},  // South
            {-1, 0}  // West
    };

    /**
     * Finds the optimal shortest path from start to goal.
     *
     * @param map     warehouse grid map
     * @param start   starting grid coordinates
     * @param goal    target destination grid coordinates
     * @param blocked optional set of temporarily blocked grid cells (e.g., dynamic obstacles, conflicting cells)
     * @return ordered list of {@link GridPoint} from start to goal, or empty list if no path exists
     */
    public List<GridPoint> findPath(GridMap map, GridPoint start, GridPoint goal, Set<GridPoint> blocked) {
        Objects.requireNonNull(map, "map cannot be null");
        Objects.requireNonNull(start, "start cannot be null");
        Objects.requireNonNull(goal, "goal cannot be null");

        if (start.equals(goal)) {
            return List.of(start);
        }

        if (!map.isWalkable(start) || !map.isWalkable(goal)) {
            return Collections.emptyList();
        }

        Set<GridPoint> blockedSet = blocked != null ? blocked : Collections.emptySet();

        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Map<GridPoint, Double> gScores = new HashMap<>();
        Set<GridPoint> closedSet = new HashSet<>();

        Node startNode = new Node(start, 0.0, heuristic(start, goal), null);
        openSet.add(startNode);
        gScores.put(start, 0.0);

        while (!openSet.isEmpty()) {
            Node current = openSet.poll();

            if (current.point.equals(goal)) {
                return reconstructPath(current);
            }

            if (!closedSet.add(current.point)) {
                continue; // Already processed
            }

            for (int[] dir : DIRECTIONS) {
                int nextX = current.point.x() + dir[0];
                int nextY = current.point.y() + dir[1];
                GridPoint neighbor = new GridPoint(nextX, nextY);

                if (!map.isWalkable(neighbor)) {
                    continue; // Out of bounds or static obstacle
                }

                // Skip blocked cells
                if (blockedSet.contains(neighbor)) {
                    continue;
                }

                if (closedSet.contains(neighbor)) {
                    continue;
                }

                double tentativeGScore = current.gScore + 1.0;
                Double existingGScore = gScores.get(neighbor);

                if (existingGScore == null || tentativeGScore < existingGScore) {
                    gScores.put(neighbor, tentativeGScore);
                    double fScore = tentativeGScore + heuristic(neighbor, goal);
                    openSet.add(new Node(neighbor, tentativeGScore, fScore, current));
                }
            }
        }

        // No path found
        return Collections.emptyList();
    }

    /**
     * Overload finding path without dynamic obstacles.
     */
    public List<GridPoint> findPath(GridMap map, GridPoint start, GridPoint goal) {
        return findPath(map, start, goal, Collections.emptySet());
    }

    /**
     * Manhattan distance heuristic for orthogonal grid navigation.
     */
    private double heuristic(GridPoint a, GridPoint b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y());
    }

    private List<GridPoint> reconstructPath(Node endNode) {
        List<GridPoint> path = new ArrayList<>();
        Node curr = endNode;
        while (curr != null) {
            path.add(curr.point);
            curr = curr.parent;
        }
        Collections.reverse(path);
        return path;
    }

    private static class Node implements Comparable<Node> {
        final GridPoint point;
        final double gScore;
        final double fScore;
        final Node parent;

        Node(GridPoint point, double gScore, double fScore, Node parent) {
            this.point = point;
            this.gScore = gScore;
            this.fScore = fScore;
            this.parent = parent;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(this.fScore, o.fScore);
        }
    }
}
