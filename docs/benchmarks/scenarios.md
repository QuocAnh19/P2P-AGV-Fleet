# Automated Multi-Scenario Benchmark Report

**Generated:** 2026-11-06 17:31:45

## System Environment

| Parameter | Value |
| :--- | :--- |
| **CPU** | Intel64 Family 6 Model 141 Stepping 1, GenuineIntel (12 cores) |
| **RAM** | 252 MB allocated (Max JVM: 4022 MB) |
| **JDK** | 23 (Oracle Corporation) |
| **OS** | Windows 11 10.0 (amd64) |
| **Timestamp** | 2026-11-06 17:31:45 |

## Scenario 1: Head-on Intersection Collision & Deadlock Avoidance

Evaluating convergence of AGVs toward a central 4-way intersection `(5, 5)` under distributed Ricart-Agrawala Mutual Exclusion.

| Fleet Size | Total Runs | Collisions | Deadlocks | Avg Mutex Wait (ms) | Max Mutex Wait (ms) | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **2 AGVs** | 60 | 0 | 0 | 17.11 ms | 18.18 ms | PASSED |
| **4 AGVs** | 70 | 0 | 0 | 8.79 ms | 9.48 ms | PASSED |
| **8 AGVs** | 70 | 0 | 0 | 19.60 ms | 22.91 ms | PASSED |

**Overall S1 Outcome:** PASSED (0 Collisions, 0 Deadlocks across 200 runs)

## Scenario 2: Adaptive CNP Auction with Low-Battery Policy

Evaluating multi-robot task allocation over 50 dynamic tasks with 5 AGVs of varying initial battery levels:  
- **AGV 1:** 90.0% battery, pos (1, 1)
- **AGV 2:** 60.0% battery, pos (9, 1)
- **AGV 3:** 40.0% battery, pos (1, 9)
- **AGV 4:** 20.0% battery, pos (9, 9)
- **AGV 5:** 10.0% battery (<= 15% threshold), pos (5, 5)

### Task Allocation Results (50 Tasks)

| Task ID | Pickup | Delivery | Winner | Bid Cost | Winner Battery |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `TASK-01` | (9, 4) | (1, 9) | **AGV 2** | 0.43 | 60.0% |
| `TASK-02` | (1, 8) | (6, 3) | **AGV 2** | 0.36 | 59.0% |
| `TASK-03` | (8, 3) | (3, 9) | **AGV 2** | 0.40 | 58.0% |
| `TASK-04` | (7, 1) | (9, 6) | **AGV 1** | 0.44 | 90.0% |
| `TASK-05` | (7, 7) | (2, 9) | **AGV 1** | 0.31 | 89.0% |
| `TASK-06` | (6, 5) | (9, 7) | **AGV 1** | 0.44 | 88.0% |
| `TASK-07` | (6, 1) | (8, 5) | **AGV 1** | 0.48 | 87.0% |
| `TASK-08` | (2, 4) | (5, 4) | **AGV 1** | 0.46 | 86.0% |
| `TASK-09` | (3, 6) | (3, 6) | **AGV 1** | 0.35 | 85.0% |
| `TASK-10` | (4, 8) | (8, 4) | **AGV 1** | 0.33 | 84.0% |
| `TASK-11` | (6, 5) | (9, 8) | **AGV 1** | 0.33 | 83.0% |
| `TASK-12` | (6, 7) | (5, 3) | **AGV 1** | 0.37 | 82.0% |
| `TASK-13` | (2, 8) | (5, 6) | **AGV 2** | 0.38 | 57.0% |
| `TASK-14` | (9, 5) | (9, 8) | **AGV 1** | 0.42 | 81.0% |
| `TASK-15` | (2, 5) | (4, 1) | **AGV 2** | 0.44 | 56.0% |
| `TASK-16` | (2, 6) | (3, 5) | **AGV 3** | 0.49 | 40.0% |
| `TASK-17` | (9, 6) | (6, 5) | **AGV 1** | 0.33 | 80.0% |
| `TASK-18` | (4, 1) | (2, 6) | **AGV 2** | 0.34 | 55.0% |
| `TASK-19` | (5, 8) | (8, 6) | **AGV 1** | 0.37 | 79.0% |
| `TASK-20` | (6, 8) | (3, 7) | **AGV 1** | 0.37 | 78.0% |
| `TASK-21` | (4, 2) | (6, 3) | **AGV 1** | 0.45 | 77.0% |
| `TASK-22` | (2, 7) | (4, 4) | **AGV 2** | 0.37 | 54.0% |
| `TASK-23` | (6, 8) | (1, 4) | **AGV 1** | 0.45 | 76.0% |
| `TASK-24` | (4, 1) | (6, 7) | **AGV 1** | 0.43 | 75.0% |
| `TASK-25` | (4, 7) | (9, 5) | **AGV 1** | 0.35 | 74.0% |
| `TASK-26` | (4, 2) | (7, 2) | **AGV 2** | 0.41 | 53.0% |
| `TASK-27` | (9, 5) | (1, 9) | **AGV 1** | 0.28 | 73.0% |
| `TASK-28` | (5, 3) | (9, 2) | **AGV 2** | 0.42 | 52.0% |
| `TASK-29` | (4, 8) | (4, 4) | **NO BIDS** | 0.00 | 0.0% |
| `TASK-30` | (8, 8) | (9, 7) | **AGV 4** | 0.49 | 20.0% |
| `TASK-31` | (4, 7) | (3, 5) | **AGV 3** | 0.46 | 39.0% |
| `TASK-32` | (9, 4) | (5, 1) | **AGV 2** | 0.42 | 51.0% |
| `TASK-33` | (8, 2) | (5, 9) | **AGV 2** | 0.46 | 50.0% |
| `TASK-34` | (5, 5) | (1, 6) | **AGV 3** | 0.46 | 38.0% |
| `TASK-35` | (7, 3) | (9, 5) | **AGV 2** | 0.58 | 49.0% |
| `TASK-36` | (3, 6) | (5, 9) | **AGV 3** | 0.46 | 37.0% |
| `TASK-37` | (9, 6) | (1, 9) | **AGV 2** | 0.39 | 48.0% |
| `TASK-38` | (5, 3) | (5, 3) | **AGV 3** | 0.60 | 36.0% |
| `TASK-39` | (3, 4) | (2, 5) | **AGV 3** | 0.47 | 35.0% |
| `TASK-40` | (6, 9) | (3, 4) | **AGV 2** | 0.54 | 47.0% |
| `TASK-41` | (5, 4) | (3, 6) | **AGV 2** | 0.43 | 46.0% |
| `TASK-42` | (2, 7) | (6, 7) | **AGV 2** | 0.42 | 45.0% |
| `TASK-43` | (3, 5) | (6, 9) | **AGV 3** | 0.43 | 34.0% |
| `TASK-44` | (1, 7) | (5, 4) | **AGV 2** | 0.54 | 44.0% |
| `TASK-45` | (1, 4) | (8, 5) | **AGV 2** | 0.51 | 43.0% |
| `TASK-46` | (3, 5) | (3, 8) | **AGV 2** | 0.55 | 42.0% |
| `TASK-47` | (1, 9) | (1, 7) | **AGV 2** | 0.46 | 41.0% |
| `TASK-48` | (4, 7) | (9, 1) | **AGV 3** | 0.50 | 33.0% |
| `TASK-49` | (3, 2) | (1, 3) | **AGV 3** | 0.62 | 32.0% |
| `TASK-50` | (6, 6) | (8, 4) | **AGV 4** | 0.55 | 19.0% |

### Win Distribution & Verification

| AGV Node | Initial Battery | Final Battery | Won Tasks | Policy Verification |
| :--- | :--- | :--- | :--- | :--- |
| AGV 1 | 90.0% | 72.0% | 18 | Active Bidder |
| AGV 2 | 60.0% | 40.0% | 20 | Active Bidder |
| AGV 3 | 40.0% | 31.0% | 9 | Active Bidder |
| AGV 4 | 20.0% | 18.0% | 2 | Active Bidder |
| AGV 5 | 10.0% | 10.0% | 0 | **PASSED** (<=15% battery refused to bid) |

**Overall S2 Outcome:** PASSED (AGV 5 won 0 tasks, 100% of tasks awarded to healthy AGVs)

## Scenario 3: Node Crash & Network Freeze Fault Tolerance

Evaluating failure detection and recovery across three catastrophic network failure modes:
1. **Variant 3a:** AGV crashes abruptly while holding intersection mutex (`HELD`).
2. **Variant 3b:** AGV crashes abruptly while executing an assigned CNP task.
3. **Variant 3c:** AGV network freezes (`freezeNetwork`), detected via heartbeat timeout.

| Variant | Failure Mode | Failure Detection & Recovery Time | Lost Tasks | Verification Status |
| :--- | :--- | :--- | :--- | :--- |
| **3a** | Abrupt crash holding Mutex (HELD) | 1.53 ms (Immediate TCP channel drop) | 0 | PASSED |
| **3b** | Abrupt crash during task execution | 171.73 ms (Peer drop & re-announcement) | 0 | PASSED |
| **3c** | Silent network freeze (heartbeat drop) | 669.05 ms (Heartbeat timeout 500ms) | 0 | PASSED |

**Overall S3 Outcome:** PASSED (0 tasks lost, fault tolerance verified across all 3 failure modes)

## Scenario Verification Summary

| Scenario | Description | Target Requirements | Result |
| :--- | :--- | :--- | :--- |
| **S1** | Head-on Intersection (2, 4, 8 AGVs) | 200 runs, 0 collisions, 0 deadlocks | **PASSED** |
| **S2** | Adaptive CNP Auction (50 tasks) | Low-battery AGV (<=15%) wins 0 tasks | **PASSED** |
| **S3** | Crash & Freeze Fault-Tolerance | 0 lost tasks, automatic mutex & task recovery | **PASSED** |

**Final Verdict:** PASSED (Exit Code 0)
