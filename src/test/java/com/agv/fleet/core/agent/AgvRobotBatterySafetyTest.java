package com.agv.fleet.core.agent;

import com.agv.fleet.common.protocol.payload.TaskAnnouncePayload;
import com.agv.fleet.core.AgvNode;
import com.agv.fleet.core.map.GridMap;
import com.agv.fleet.core.map.GridPoint;
import com.agv.fleet.core.navigation.DynamicAStarPathfinder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class AgvRobotBatterySafetyTest {

    @Test
    @DisplayName("Bug 1: Low battery robot must not get stuck forever when task announcement is received")
    void testLowBatteryRobotDoesNotGetStuckForeverWhenTaskAnnounced() {
        GridMap map = GridMap.createDefaultWarehouseMap();
        DynamicAStarPathfinder pathfinder = new DynamicAStarPathfinder();

        // Node with 10% battery positioned at walkable corridor (2, 6) - not a charging dock
        AgvNode node = AgvNode.builder()
                .nodeId(101L)
                .tcpPort(19001)
                .initialBattery(10.0)
                .initialPosition(2.0, 6.0)
                .build();

        AgvRobot robot = new AgvRobot(node, map, pathfinder, new GridPoint(2, 6));

        // When task announcement is received, CnpEngine must NOT switch state to LOW_BATTERY_CHARGING
        TaskAnnouncePayload announcePayload = new TaskAnnouncePayload("TASK-TEST-1", 0.0, 0.0, 10.0, 10.0, 999L);
        com.agv.fleet.common.protocol.AgvMessage msg = com.agv.fleet.common.protocol.AgvMessage.create(
                com.agv.fleet.common.protocol.Opcode.TASK_ANNOUNCE, 1L, 1L, 999L, announcePayload.toBytes()
        );
        node.getCnpEngine().onTaskAnnounceReceived(msg);

        // State should still be managed by AgvRobot, NOT CnpEngine
        assertThat(node.getAgentState()).as("CnpEngine should not unilaterally set state to LOW_BATTERY_CHARGING")
                .isNotEqualTo(AgentState.LOW_BATTERY_CHARGING);

        // Robot update loop should trigger movement towards nearest charging station
        robot.updatePhysicsAndLogic(Collections.emptySet());

        assertThat(robot.getState()).isEqualTo(AgentState.LOW_BATTERY_CHARGING);
        assertThat(robot.getTargetGridPos()).isEqualTo(map.getNearestChargingStation(new GridPoint(2, 6)));
        assertThat(robot.getRemainingPath()).isNotEmpty();
    }

    @Test
    @DisplayName("Bug 1: Safety branch auto-navigates to charging station if LOW_BATTERY_CHARGING and pathQueue is empty")
    void testSafetyBranchRecoversLowBatteryChargingWithEmptyPath() {
        GridMap map = GridMap.createDefaultWarehouseMap();
        DynamicAStarPathfinder pathfinder = new DynamicAStarPathfinder();

        AgvNode node = AgvNode.builder()
                .nodeId(102L)
                .tcpPort(19002)
                .initialBattery(10.0)
                .initialPosition(2.0, 6.0)
                .build();

        AgvRobot robot = new AgvRobot(node, map, pathfinder, new GridPoint(2, 6));
        node.setAgentState(AgentState.LOW_BATTERY_CHARGING);

        // Currently no destination set, path is empty
        assertThat(robot.getRemainingPath()).isEmpty();

        // Safety fallback must detect this and set destination to nearest charging station
        robot.updatePhysicsAndLogic(Collections.emptySet());

        assertThat(robot.getTargetGridPos()).isEqualTo(map.getNearestChargingStation(new GridPoint(2, 6)));
        assertThat(robot.getRemainingPath()).isNotEmpty();
    }

    @Test
    @DisplayName("Bug 1: Low battery during active task completes task before heading to recharge")
    void testLowBatteryDuringActiveTaskCompletesTaskFirst() {
        GridMap map = GridMap.createDefaultWarehouseMap();
        DynamicAStarPathfinder pathfinder = new DynamicAStarPathfinder();

        AgvNode node = AgvNode.builder()
                .nodeId(103L)
                .tcpPort(19003)
                .initialBattery(10.0) // critical
                .initialPosition(2.0, 6.0)
                .build();

        AgvRobot robot = new AgvRobot(node, map, pathfinder, new GridPoint(2, 6));

        // Assign task to robot with distant pickup at (12, 6)
        Task task = new Task("TASK-IN-PROGRESS", 12.0, 6.0, 12.0, 14.0);
        task.setStatus(Task.Status.ASSIGNED);
        robot.setDestination(new GridPoint(12, 6)); // pickup point
        node.setAgentState(AgentState.MOVING);

        // Artificially assign task to robot
        try {
            var field = AgvRobot.class.getDeclaredField("currentTask");
            field.setAccessible(true);
            field.set(robot, task);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Run update - should NOT redirect to charging station while task is in progress
        robot.updatePhysicsAndLogic(Collections.emptySet());

        assertThat(robot.getCurrentTask()).isNotNull();
        assertThat(robot.getTargetGridPos()).isEqualTo(new GridPoint(12, 6));
    }
}
