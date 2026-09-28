package com.agv.fleet.distributed.mutex;

/**
 * States of an AGV node with respect to a critical section/intersection cell
 * in the Ricart-Agrawala distributed mutual exclusion algorithm.
 */
public enum MutexState {

    /**
     * The node is not in the critical section and does not intend to enter it.
     * All requests for this resource will be immediately approved.
     */
    RELEASED,

    /**
     * The node has issued a request to enter the critical section and is waiting
     * for replies from all active peer nodes.
     */
    WANTED,

    /**
     * The node has collected all required replies and currently possesses exclusive
     * permission to execute within the critical section.
     */
    HELD
}
