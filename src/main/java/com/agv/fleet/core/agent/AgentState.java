package com.agv.fleet.core.agent;

/**
 * Operational states of an autonomous AGV agent in the fleet.
 */
public enum AgentState {

    /**
     * Agent is available and ready to bid on newly announced transport tasks.
     */
    IDLE,

    /**
     * Agent has submitted a bid for an active auction and is awaiting the award result.
     */
    BIDDING,

    /**
     * Agent is currently executing an assigned transport task.
     */
    BUSY,

    /**
     * Battery level is critically low (&lt;= 15%). Agent cannot bid and must navigate to charging station.
     */
    LOW_BATTERY_CHARGING
}
