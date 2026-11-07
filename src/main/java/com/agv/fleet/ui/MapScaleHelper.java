package com.agv.fleet.ui;

/**
 * Utility helper computing responsive cell sizing and coordinate projection
 * for the Warehouse Grid Map (24 x 16 cells).
 */
public final class MapScaleHelper {

    public static final int GRID_COLS = 24;
    public static final int GRID_ROWS = 16;
    public static final double DEFAULT_CELL_SIZE = 28.0;
    public static final double MIN_CELL_SIZE = 12.0;

    private MapScaleHelper() {}

    /**
     * Computes the square cellSize fitting within the available width and height.
     * cellSize = min(freeW / 24, freeH / 16)
     */
    public static double calculateCellSize(double freeWidth, double freeHeight) {
        if (freeWidth <= 0 || freeHeight <= 0) {
            return DEFAULT_CELL_SIZE;
        }
        double cellW = freeWidth / GRID_COLS;
        double cellH = freeHeight / GRID_ROWS;
        double size = Math.min(cellW, cellH);
        return Math.max(MIN_CELL_SIZE, size);
    }

    /**
     * Converts a mouse X coordinate to a grid column index.
     */
    public static int mouseToGridX(double mouseX, double cellSize, double offsetX) {
        if (cellSize <= 0) return -1;
        return (int) Math.floor((mouseX - offsetX) / cellSize);
    }

    /**
     * Converts a mouse Y coordinate to a grid row index.
     */
    public static int mouseToGridY(double mouseY, double cellSize, double offsetY) {
        if (cellSize <= 0) return -1;
        return (int) Math.floor((mouseY - offsetY) / cellSize);
    }
}
