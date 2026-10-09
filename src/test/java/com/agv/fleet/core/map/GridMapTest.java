package com.agv.fleet.core.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Warehouse GridMap Specification Tests")
class GridMapTest {

    @Test
    @DisplayName("GridMap initialization and boundary validation")
    void testGridMapInitialization() {
        GridMap map = new GridMap(20, 15);
        assertThat(map.getWidth()).isEqualTo(20);
        assertThat(map.getHeight()).isEqualTo(15);
        assertThat(map.isInBounds(0, 0)).isTrue();
        assertThat(map.isInBounds(19, 14)).isTrue();
        assertThat(map.isInBounds(20, 15)).isFalse();
        assertThat(map.isInBounds(-1, 0)).isFalse();

        assertThatThrownBy(() -> new GridMap(0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CellType management and walkable status")
    void testCellTypeManagement() {
        GridMap map = new GridMap(10, 10);
        GridPoint p = new GridPoint(3, 4);

        assertThat(map.getCellType(p)).isEqualTo(CellType.EMPTY);
        assertThat(map.isWalkable(p)).isTrue();

        map.setCellType(p, CellType.OBSTACLE);
        assertThat(map.getCellType(p)).isEqualTo(CellType.OBSTACLE);
        assertThat(map.isWalkable(p)).isFalse();

        map.setCellType(p, CellType.INTERSECTION);
        assertThat(map.isIntersection(p)).isTrue();
        assertThat(map.isWalkable(p)).isTrue();
        assertThat(map.getIntersectionCellId(p)).isEqualTo("INTERSECTION_3_4");

        map.setCellType(p, CellType.CHARGING_STATION);
        assertThat(map.isChargingStation(p)).isTrue();
        assertThat(map.isWalkable(p)).isTrue();
    }

    @Test
    @DisplayName("Default warehouse layout generation")
    void testDefaultWarehouseMap() {
        GridMap defaultMap = GridMap.createDefaultWarehouseMap();

        assertThat(defaultMap.getWidth()).isEqualTo(GridMap.DEFAULT_WIDTH);
        assertThat(defaultMap.getHeight()).isEqualTo(GridMap.DEFAULT_HEIGHT);

        List<GridPoint> intersections = defaultMap.getIntersections();
        assertThat(intersections).isNotEmpty();
        for (GridPoint ip : intersections) {
            assertThat(defaultMap.isIntersection(ip)).isTrue();
            assertThat(defaultMap.isWalkable(ip)).isTrue();
        }

        List<GridPoint> chargingStations = defaultMap.getChargingStations();
        assertThat(chargingStations).isNotEmpty();
        for (GridPoint cp : chargingStations) {
            assertThat(defaultMap.isChargingStation(cp)).isTrue();
        }

        GridPoint from = new GridPoint(1, 1);
        GridPoint nearestDock = defaultMap.getNearestChargingStation(from);
        assertThat(nearestDock).isNotNull();
        assertThat(defaultMap.isChargingStation(nearestDock)).isTrue();
    }
}
