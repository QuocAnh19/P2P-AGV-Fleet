package com.agv.fleet.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MapScaleHelperTest {

    @Test
    @DisplayName("calculateCellSize: returns min(freeW/24, freeH/16) bounded by min size")
    void testCalculateCellSize() {
        // Square aspect fit: 1200 / 24 = 50, 800 / 16 = 50 -> cellSize = 50.0
        double size1 = MapScaleHelper.calculateCellSize(1200, 800);
        assertThat(size1).isEqualTo(50.0);

        // Width constrained: 720 / 24 = 30, 800 / 16 = 50 -> cellSize = 30.0
        double size2 = MapScaleHelper.calculateCellSize(720, 800);
        assertThat(size2).isEqualTo(30.0);

        // Height constrained: 1200 / 24 = 50, 480 / 16 = 30 -> cellSize = 30.0
        double size3 = MapScaleHelper.calculateCellSize(1200, 480);
        assertThat(size3).isEqualTo(30.0);

        // Negative or zero fallback to default (28.0)
        double defaultSize = MapScaleHelper.calculateCellSize(0, -10);
        assertThat(defaultSize).isEqualTo(MapScaleHelper.DEFAULT_CELL_SIZE);

        // Very small space bounded by MIN_CELL_SIZE (12.0)
        double minSize = MapScaleHelper.calculateCellSize(100, 100);
        assertThat(minSize).isEqualTo(MapScaleHelper.MIN_CELL_SIZE);
    }

    @Test
    @DisplayName("mouseToGrid: projects pixel coordinates accurately to grid indices")
    void testMouseToGridProjection() {
        double cellSize = 30.0;
        double offsetX = 0.0;
        double offsetY = 0.0;

        // Inside cell (0, 0)
        assertThat(MapScaleHelper.mouseToGridX(15.0, cellSize, offsetX)).isEqualTo(0);
        assertThat(MapScaleHelper.mouseToGridY(15.0, cellSize, offsetY)).isEqualTo(0);

        // Cell boundaries: 30.0 is index 1
        assertThat(MapScaleHelper.mouseToGridX(30.0, cellSize, offsetX)).isEqualTo(1);
        assertThat(MapScaleHelper.mouseToGridY(60.0, cellSize, offsetY)).isEqualTo(2);

        // Cell (7, 6)
        assertThat(MapScaleHelper.mouseToGridX(7 * cellSize + 5, cellSize, offsetX)).isEqualTo(7);
        assertThat(MapScaleHelper.mouseToGridY(6 * cellSize + 12, cellSize, offsetY)).isEqualTo(6);

        // With offset
        assertThat(MapScaleHelper.mouseToGridX(100.0, cellSize, 10.0)).isEqualTo(3); // (100 - 10) / 30 = 3
        assertThat(MapScaleHelper.mouseToGridY(100.0, cellSize, 10.0)).isEqualTo(3);
    }

    @Test
    @DisplayName("Window layout responsiveness: min window bounds 1100x650 at 62% map split yields valid square cells")
    void testWindowSplitScaling() {
        // At 1100 width: 62% for map = 682 px, height = 650 px (minus top/bottom bars ~120px = 530px)
        double freeW = 682.0;
        double freeH = 530.0;
        double cellSize = MapScaleHelper.calculateCellSize(freeW, freeH);

        // 682 / 24 = 28.41, 530 / 16 = 33.12 -> cellSize = 28.41
        assertThat(cellSize).isBetween(28.0, 29.0);

        // Grid bounds
        double canvasW = MapScaleHelper.GRID_COLS * cellSize;
        double canvasH = MapScaleHelper.GRID_ROWS * cellSize;
        assertThat(canvasW).isLessThanOrEqualTo(freeW);
        assertThat(canvasH).isLessThanOrEqualTo(freeH);
    }
}
