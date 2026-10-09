package com.agv.fleet.core.map;

/**
 * Classification of grid cells in the automated warehouse map.
 */
public enum CellType {

    /**
     * Open corridor or driving lane where AGVs can freely traverse.
     */
    EMPTY,

    /**
     * Impassable warehouse storage racks, structural pillars, or walls.
     */
    OBSTACLE,

    /**
     * 3-way or 4-way intersection crossing requiring Ricart-Agrawala Mutual Exclusion.
     */
    INTERSECTION,

    /**
     * Automated battery charging dock.
     */
    CHARGING_STATION
}
