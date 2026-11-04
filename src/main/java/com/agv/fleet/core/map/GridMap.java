package com.agv.fleet.core.map;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2D Discrete Grid Map representing an automated warehouse layout.
 * Manages impassable storage racks (OBSTACLES), crossing zones (INTERSECTIONS),
 * charging docks (CHARGING_STATIONS), and traversal lanes (EMPTY).
 */
public class GridMap {

    public static final int DEFAULT_WIDTH = 24;
    public static final int DEFAULT_HEIGHT = 16;

    private final int width;
    private final int height;
    private final CellType[][] grid;

    private final List<GridPoint> intersections = new ArrayList<>();
    private final List<GridPoint> chargingStations = new ArrayList<>();

    public GridMap(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Map dimensions must be positive: " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.grid = new CellType[width][height];

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                grid[x][y] = CellType.EMPTY;
            }
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean isInBounds(int x, int y) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    public boolean isInBounds(GridPoint p) {
        return p != null && isInBounds(p.x(), p.y());
    }

    public CellType getCellType(int x, int y) {
        if (!isInBounds(x, y)) {
            return CellType.OBSTACLE;
        }
        return grid[x][y];
    }

    public CellType getCellType(GridPoint p) {
        return p != null ? getCellType(p.x(), p.y()) : CellType.OBSTACLE;
    }

    public void setCellType(int x, int y, CellType type) {
        if (!isInBounds(x, y)) {
            return;
        }
        grid[x][y] = Objects.requireNonNull(type, "CellType cannot be null");
        rebuildSpecialCellLists();
    }

    public void setCellType(GridPoint p, CellType type) {
        if (p != null) {
            setCellType(p.x(), p.y(), type);
        }
    }

    public boolean isWalkable(int x, int y) {
        if (!isInBounds(x, y)) {
            return false;
        }
        return grid[x][y] != CellType.OBSTACLE;
    }

    public boolean isWalkable(GridPoint p) {
        return p != null && isWalkable(p.x(), p.y());
    }

    public boolean isIntersection(int x, int y) {
        return isInBounds(x, y) && grid[x][y] == CellType.INTERSECTION;
    }

    public boolean isIntersection(GridPoint p) {
        return p != null && isIntersection(p.x(), p.y());
    }

    public boolean isChargingStation(int x, int y) {
        return isInBounds(x, y) && grid[x][y] == CellType.CHARGING_STATION;
    }

    public boolean isChargingStation(GridPoint p) {
        return p != null && isChargingStation(p.x(), p.y());
    }

    /**
     * Generates a deterministic, unique Cell ID for Ricart-Agrawala Mutual Exclusion.
     */
    public String getIntersectionCellId(int x, int y) {
        return "INTERSECTION_" + x + "_" + y;
    }

    public String getIntersectionCellId(GridPoint p) {
        return p != null ? getIntersectionCellId(p.x(), p.y()) : "INTERSECTION_UNKNOWN";
    }

    public List<GridPoint> getIntersections() {
        return Collections.unmodifiableList(intersections);
    }

    public List<GridPoint> getChargingStations() {
        return Collections.unmodifiableList(chargingStations);
    }

    /**
     * Finds the nearest charging station to a given location.
     */
    public GridPoint getNearestChargingStation(GridPoint from) {
        if (from == null || chargingStations.isEmpty()) {
            return chargingStations.isEmpty() ? null : chargingStations.get(0);
        }

        return chargingStations.stream()
                .min(Comparator.comparingInt(from::manhattanDistance))
                .orElse(chargingStations.get(0));
    }

    public List<GridPoint> getWalkableEmptyPoints() {
        List<GridPoint> list = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (grid[x][y] == CellType.EMPTY) {
                    list.add(new GridPoint(x, y));
                }
            }
        }
        return list;
    }

    /**
     * Returns predefined industrial warehouse operational zones with ergonomic labels.
     */
    public Map<String, GridPoint> getNamedZones() {
        Map<String, GridPoint> zones = new LinkedHashMap<>();
        zones.put("Inbound Receiving Dock 1", new GridPoint(0, 3));
        zones.put("Inbound Receiving Dock 2", new GridPoint(0, 8));
        zones.put("Storage Aisle A (West-North)", new GridPoint(2, 4));
        zones.put("Storage Aisle A (West-South)", new GridPoint(2, 12));
        zones.put("Storage Aisle B (Mid-West)", new GridPoint(7, 8));
        zones.put("Storage Aisle C (Mid-East)", new GridPoint(12, 8));
        zones.put("Storage Aisle D (East-North)", new GridPoint(17, 4));
        zones.put("Storage Aisle D (East-South)", new GridPoint(17, 12));
        zones.put("Packing Station Alpha", new GridPoint(12, 2));
        zones.put("Packing Station Beta", new GridPoint(12, 14));
        zones.put("Outbound Shipping Bay 1", new GridPoint(23, 4));
        zones.put("Outbound Shipping Bay 2", new GridPoint(23, 8));
        zones.put("Charging Dock NW", new GridPoint(0, 1));
        zones.put("Charging Dock SW", new GridPoint(0, 13));
        zones.put("Charging Dock NE", new GridPoint(23, 1));
        zones.put("Charging Dock SE", new GridPoint(23, 13));
        return zones;
    }

    private void rebuildSpecialCellLists() {
        intersections.clear();
        chargingStations.clear();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (grid[x][y] == CellType.INTERSECTION) {
                    intersections.add(new GridPoint(x, y));
                } else if (grid[x][y] == CellType.CHARGING_STATION) {
                    chargingStations.add(new GridPoint(x, y));
                }
            }
        }
    }

    /**
     * Factory method creating a realistic warehouse layout (24 x 16):
     * <ul>
     *   <li>Main vertical driving corridors at x=2, x=7, x=12, x=17, x=22</li>
     *   <li>Main horizontal cross-aisles at y=2, y=6, y=10, y=14</li>
     *   <li>Intersections at crossing points</li>
     *   <li>Storage racks (OBSTACLES) organized in blocks</li>
     *   <li>Charging docks along borders</li>
     * </ul>
     */
    public static GridMap createDefaultWarehouseMap() {
        GridMap map = new GridMap(DEFAULT_WIDTH, DEFAULT_HEIGHT);

        // 1. Fill storage racks (Obstacles)
        // Racks grouped in 3 tiers of rows
        int[][] rackXRanges = {
                {3, 6}, {8, 11}, {13, 16}, {18, 21}
        };
        int[][] rackYRanges = {
                {3, 5}, {7, 9}, {11, 13}
        };

        for (int[] xRange : rackXRanges) {
            for (int[] yRange : rackYRanges) {
                for (int x = xRange[0]; x <= xRange[1]; x++) {
                    for (int y = yRange[0]; y <= yRange[1]; y++) {
                        map.grid[x][y] = CellType.OBSTACLE;
                    }
                }
            }
        }

        // 2. Add Charging Stations at docking bays
        map.grid[0][1] = CellType.CHARGING_STATION;
        map.grid[0][2] = CellType.CHARGING_STATION;
        map.grid[0][13] = CellType.CHARGING_STATION;
        map.grid[0][14] = CellType.CHARGING_STATION;
        map.grid[23][1] = CellType.CHARGING_STATION;
        map.grid[23][2] = CellType.CHARGING_STATION;
        map.grid[23][13] = CellType.CHARGING_STATION;
        map.grid[23][14] = CellType.CHARGING_STATION;

        // 3. Mark Crossing Intersections
        int[] aisleXs = {2, 7, 12, 17, 22};
        int[] aisleYs = {2, 6, 10, 14};

        for (int x : aisleXs) {
            for (int y : aisleYs) {
                map.grid[x][y] = CellType.INTERSECTION;
            }
        }

        map.rebuildSpecialCellLists();
        return map;
    }
}
