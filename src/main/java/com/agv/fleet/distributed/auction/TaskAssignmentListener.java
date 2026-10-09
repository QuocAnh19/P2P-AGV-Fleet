package com.agv.fleet.distributed.auction;

import com.agv.fleet.core.agent.Task;

/**
 * Callback listener invoked when an AGV agent is awarded a task through Contract Net Protocol.
 */
@FunctionalInterface
public interface TaskAssignmentListener {

    /**
     * Triggered when this AGV wins an auction and is assigned a task.
     *
     * @param task the awarded transport task
     */
    void onTaskAssigned(Task task);
}
