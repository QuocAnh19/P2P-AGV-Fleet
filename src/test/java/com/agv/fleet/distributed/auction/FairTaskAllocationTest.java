package com.agv.fleet.distributed.auction;

import com.agv.fleet.core.agent.AgentState;
import com.agv.fleet.core.agent.Position2D;
import com.agv.fleet.distributed.clock.LamportClock;
import com.agv.fleet.network.mesh.PeerManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Fair Task Distribution in CNP (Bid Cost Normalization & Load Balancing)")
class FairTaskAllocationTest {

    private record VehicleNode(long nodeId, double battery, Position2D pos, CnpEngine engine, ScheduledExecutorService scheduler) {}

    private VehicleNode createVehicle(long nodeId, double battery, Position2D pos) {
        AtomicReference<AgentState> state = new AtomicReference<>(AgentState.IDLE);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        PeerManager peerManager = new PeerManager(nodeId);
        CnpEngine engine = new CnpEngine(
                nodeId,
                peerManager,
                new LamportClock(),
                () -> 1L,
                scheduler,
                () -> battery,
                () -> pos,
                state::get,
                state::set
        );
        engine.setMapDimensions(24, 16);
        return new VehicleNode(nodeId, battery, pos, engine, scheduler);
    }

    @Test
    @DisplayName("60 tasks across 6 diverse AGVs: fair distribution with load balancing and critical battery refusal")
    void testFairTaskAllocationAcrossFleet() {
        // Fleet of 6 diverse AGVs with battery levels 95% down to 30%
        List<VehicleNode> fleet = List.of(
                createVehicle(1L, 95.0, new Position2D(2, 2)),
                createVehicle(2L, 80.0, new Position2D(8, 2)),
                createVehicle(3L, 65.0, new Position2D(14, 6)),
                createVehicle(4L, 50.0, new Position2D(20, 10)),
                createVehicle(5L, 40.0, new Position2D(4, 12)),
                createVehicle(6L, 30.0, new Position2D(18, 14)),
                // Vehicle 7 has critical battery <= 15% (must NEVER bid/win)
                createVehicle(7L, 12.0, new Position2D(0, 0))
        );

        try {
            Map<Long, Integer> winCounts = new HashMap<>();
            Random taskRandom = new Random(42);

            // Auction 60 tasks distributed across the warehouse
            for (int i = 0; i < 60; i++) {
                double pickupX = taskRandom.nextInt(24);
                double pickupY = taskRandom.nextInt(16);

                VehicleNode bestBidder = null;
                double lowestCost = Double.MAX_VALUE;

                for (VehicleNode v : fleet) {
                    if (!v.engine().isEligibleToBid()) {
                        continue; // e.g. Node 7 battery <= 15%
                    }
                    double cost = v.engine().computeBidCost(pickupX, pickupY);
                    if (cost < lowestCost) {
                        lowestCost = cost;
                        bestBidder = v;
                    }
                }

                assertThat(bestBidder).isNotNull();
                assertThat(bestBidder.battery()).isGreaterThan(15.0);

                // Winner accepts task
                bestBidder.engine().recordTaskAccepted();
                winCounts.merge(bestBidder.nodeId(), 1, Integer::sum);
            }

            // Output allocation summary
            System.out.println("=== 60-Task Fair Allocation Results ===");
            winCounts.forEach((nodeId, count) ->
                    System.out.printf(Locale.US, "Node %d: %d wins (%.1f%%)\n", nodeId, count, (count / 60.0) * 100));

            // 1. Assert >= 4 different vehicles win
            assertThat(winCounts.keySet().size())
                    .as("At least 4 different vehicles must win tasks due to multi-criteria fairness")
                    .isGreaterThanOrEqualTo(4);

            // 2. Assert no single vehicle wins > 40% of tasks (max 24 out of 60)
            int maxWinsAllowed = (int) (60 * 0.40);
            for (Map.Entry<Long, Integer> entry : winCounts.entrySet()) {
                assertThat(entry.getValue())
                        .as("Vehicle " + entry.getKey() + " must not monopolize tasks (>40%)")
                        .isLessThanOrEqualTo(maxWinsAllowed);
            }

            // 3. Assert battery <= 15% never wins
            assertThat(winCounts.getOrDefault(7L, 0))
                    .as("Vehicle with critical battery <= 15% must never win any tasks")
                    .isZero();
        } finally {
            for (VehicleNode v : fleet) {
                v.scheduler().shutdownNow();
            }
        }
    }
}
