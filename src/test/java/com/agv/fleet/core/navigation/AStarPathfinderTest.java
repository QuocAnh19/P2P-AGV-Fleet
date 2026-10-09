package com.agv.fleet.core.navigation;

import com.agv.fleet.core.map.CellType;
import com.agv.fleet.core.map.GridMap;
import com.agv.fleet.core.map.GridPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Dynamic A* Pathfinding Engine Tests")
class AStarPathfinderTest {

    private final DynamicAStarPathfinder pathfinder = new DynamicAStarPathfinder();

    @Test
    @DisplayName("Straight line pathfinding on empty grid")
    void testStraightLinePath() {
        GridMap map = new GridMap(10, 10);
        GridPoint start = new GridPoint(1, 1);
        GridPoint goal = new GridPoint(1, 5);

        List<GridPoint> path = pathfinder.findPath(map, start, goal);
        assertThat(path).isNotEmpty();
        assertThat(path.get(0)).isEqualTo(start);
        assertThat(path.get(path.size() - 1)).isEqualTo(goal);
        assertThat(path).hasSize(5); // (1,1) -> (1,2) -> (1,3) -> (1,4) -> (1,5)
    }

    @Test
    @DisplayName("Pathfinding routes around static obstacles (shelves)")
    void testRoutesAroundObstacle() {
        GridMap map = new GridMap(10, 10);
        GridPoint start = new GridPoint(2, 2);
        GridPoint goal = new GridPoint(4, 2);

        // Place wall obstacle between start and goal at (3, 2)
        map.setCellType(3, 2, CellType.OBSTACLE);

        List<GridPoint> path = pathfinder.findPath(map, start, goal);
        assertThat(path).isNotEmpty();
        assertThat(path.get(0)).isEqualTo(start);
        assertThat(path.get(path.size() - 1)).isEqualTo(goal);
        // Path must not contain the obstacle cell
        assertThat(path).doesNotContain(new GridPoint(3, 2));
    }

    @Test
    @DisplayName("Avoids dynamic obstacles (other parked AGVs)")
    void testAvoidsDynamicObstacles() {
        GridMap map = new GridMap(10, 10);
        GridPoint start = new GridPoint(2, 2);
        GridPoint goal = new GridPoint(2, 4);

        // Dynamic obstacle at (2, 3)
        GridPoint dynamicObstacle = new GridPoint(2, 3);
        List<GridPoint> path = pathfinder.findPath(map, start, goal, Set.of(dynamicObstacle));

        assertThat(path).isNotEmpty();
        assertThat(path.get(0)).isEqualTo(start);
        assertThat(path.get(path.size() - 1)).isEqualTo(goal);
        assertThat(path).doesNotContain(dynamicObstacle);
    }

    @Test
    @DisplayName("Returns empty path when target is completely enclosed by obstacles")
    void testNoPathAvailable() {
        GridMap map = new GridMap(10, 10);
        GridPoint start = new GridPoint(1, 1);
        GridPoint goal = new GridPoint(5, 5);

        // Enclose goal completely
        map.setCellType(4, 5, CellType.OBSTACLE);
        map.setCellType(6, 5, CellType.OBSTACLE);
        map.setCellType(5, 4, CellType.OBSTACLE);
        map.setCellType(5, 6, CellType.OBSTACLE);

        List<GridPoint> path = pathfinder.findPath(map, start, goal);
        assertThat(path).isEmpty();
    }

    @Test
    @DisplayName("Start equals goal returns single-element path")
    void testStartEqualsGoal() {
        GridMap map = new GridMap(10, 10);
        GridPoint start = new GridPoint(3, 3);

        List<GridPoint> path = pathfinder.findPath(map, start, start);
        assertThat(path).containsExactly(start);
    }
}
