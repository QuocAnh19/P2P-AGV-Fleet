package com.agv.fleet.ui;

import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import com.agv.fleet.common.protocol.payload.*;
import com.agv.fleet.core.AgvNode;
import com.agv.fleet.core.agent.AgentState;
import com.agv.fleet.core.agent.AgvRobot;
import com.agv.fleet.core.agent.Task;
import com.agv.fleet.core.map.CellType;
import com.agv.fleet.core.map.GridMap;
import com.agv.fleet.core.map.GridPoint;
import com.agv.fleet.core.navigation.DynamicAStarPathfinder;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Modern JavaFX 2D Canvas Dashboard for Decentralized P2P AGV Fleet Simulation.
 * <p>
 * Visualizes:
 * <ul>
 *   <li>Warehouse 2D Grid with dynamic obstacles, shelves, charging docks, and intersections</li>
 *   <li>Real-time Autonomous AGVs with smooth animated movement & state color coding</li>
 *   <li>Live Ricart-Agrawala intersection mutual exclusion resolution (Zero Deadlock)</li>
 *   <li>Contract Net Protocol automated task auctioning, cost evaluation, and award</li>
 *   <li>P2P wire traffic and gossip message logging</li>
 * </ul>
 */
public class AgvFleetSimulatorApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(AgvFleetSimulatorApp.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final double CELL_SIZE = 36.0;
    private static final int MULTICAST_PORT = 19876;

    private final GridMap map = GridMap.createDefaultWarehouseMap();
    private final DynamicAStarPathfinder pathfinder = new DynamicAStarPathfinder();
    private final List<AgvRobot> robots = new CopyOnWriteArrayList<>();
    private final List<Task> activeTasks = new CopyOnWriteArrayList<>();

    private final AtomicInteger taskCounter = new AtomicInteger(101);
    private final AtomicInteger nextNodeId = new AtomicInteger(4);

    // UI Controls
    private Canvas canvas;
    private VBox telemetryList;
    private ListView<String> gossipListView;
    private Label activeNodesLabel;
    private Label activeTasksLabel;
    private Label maxLamportLabel;
    private ComboBox<Long> targetRobotCombo;
    private CheckBox filterHeartbeatCheck;

    private AnimationTimer renderLoop;
    private ScheduledExecutorService simExecutor;
    private long lastPhysicsTime = 0;

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("P2P AGV Fleet Management - Decentralized Event Bus & Multi-Agent Simulator");

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #0B0F19;");

        // 1. Top Header Bar
        root.setTop(createHeaderBar());

        // 2. Center: Canvas
        canvas = new Canvas(map.getWidth() * CELL_SIZE, map.getHeight() * CELL_SIZE);
        StackPane canvasContainer = new StackPane(canvas);
        canvasContainer.setPadding(new Insets(10));
        canvasContainer.setStyle("-fx-background-color: #0F172A; -fx-border-color: #1E293B; -fx-border-width: 1px; -fx-border-radius: 8px; -fx-background-radius: 8px;");

        VBox centerBox = new VBox(10, canvasContainer);
        centerBox.setPadding(new Insets(10));
        centerBox.setAlignment(Pos.CENTER);
        root.setCenter(centerBox);

        // 3. Right Sidebar: Telemetry & Wire Gossip Log
        root.setRight(createRightSidebar());

        // 4. Bottom Controls Bar
        root.setBottom(createControlBar());

        Scene scene = new Scene(root, 1340, 840);
        primaryStage.setScene(scene);
        primaryStage.show();

        // 5. Initialize simulation and P2P fleet
        initFleet();

        // 6. Start Render & Physics loops
        startSimulationLoops();

        primaryStage.setOnCloseRequest(e -> shutdown());
    }

    private Node createHeaderBar() {
        HBox header = new HBox(20);
        header.setPadding(new Insets(12, 20, 12, 20));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #111827; -fx-border-color: #1F2937; -fx-border-width: 0 0 1 0;");

        VBox titleBox = new VBox(2);
        Text title = new Text("AGV FLEET DECENTRALIZED P2P SIMULATOR");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));
        title.setFill(Color.WHITE);

        Text subTitle = new Text("Pure P2P Mesh • Ricart-Agrawala Mutex • Contract Net Protocol • Dynamic A*");
        subTitle.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 11));
        subTitle.setFill(Color.web("#9CA3AF"));
        titleBox.getChildren().addAll(title, subTitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Metrics pills
        activeNodesLabel = createMetricBadge("AGVs Online", "3", "#3B82F6");
        activeTasksLabel = createMetricBadge("Active Tasks", "0", "#10B981");
        maxLamportLabel = createMetricBadge("Max Lamport Clock", "0", "#F59E0B");

        header.getChildren().addAll(titleBox, spacer, activeNodesLabel, activeTasksLabel, maxLamportLabel);
        return header;
    }

    private Label createMetricBadge(String title, String val, String colorHex) {
        Label badge = new Label(title + ": " + val);
        badge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        badge.setTextFill(Color.WHITE);
        badge.setPadding(new Insets(6, 12, 6, 12));
        badge.setStyle("-fx-background-color: " + colorHex + "22; -fx-border-color: " + colorHex + "; -fx-border-radius: 6px; -fx-background-radius: 6px;");
        return badge;
    }

    private Node createRightSidebar() {
        VBox sidebar = new VBox(12);
        sidebar.setPrefWidth(420);
        sidebar.setPadding(new Insets(10, 15, 10, 5));
        sidebar.setStyle("-fx-background-color: #0B0F19;");

        // Telemetry section
        Label telemetryHeader = new Label("⚡ FLEET TELEMETRY & ROBOT STATUS");
        telemetryHeader.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        telemetryHeader.setTextFill(Color.web("#E2E8F0"));

        telemetryList = new VBox(8);
        ScrollPane telemetryScroll = new ScrollPane(telemetryList);
        telemetryScroll.setFitToWidth(true);
        telemetryScroll.setPrefHeight(260);
        telemetryScroll.setStyle("-fx-background: #0F172A; -fx-border-color: #1E293B; -fx-background-color: transparent;");

        // Gossip log section
        HBox gossipHeaderBox = new HBox(10);
        gossipHeaderBox.setAlignment(Pos.CENTER_LEFT);

        Label gossipHeader = new Label("📡 P2P WIRE TRAFFIC & GOSSIP LOG");
        gossipHeader.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        gossipHeader.setTextFill(Color.web("#E2E8F0"));

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        filterHeartbeatCheck = new CheckBox("Hide Heartbeat");
        filterHeartbeatCheck.setSelected(true);
        filterHeartbeatCheck.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");

        Button clearLogBtn = new Button("Clear");
        clearLogBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 10px; -fx-padding: 2 6 2 6;");
        clearLogBtn.setOnAction(e -> gossipListView.getItems().clear());

        gossipHeaderBox.getChildren().addAll(gossipHeader, sp, filterHeartbeatCheck, clearLogBtn);

        gossipListView = new ListView<>();
        gossipListView.setPrefHeight(290);
        gossipListView.setStyle("-fx-control-inner-background: #0F172A; -fx-background-color: #0F172A; -fx-border-color: #1E293B;");
        gossipListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    setFont(Font.font("Consolas", 10.5));
                    if (item.contains("MUTEX")) {
                        setTextFill(Color.web("#FBBF24")); // Amber
                    } else if (item.contains("TASK_AWARD")) {
                        setTextFill(Color.web("#34D399")); // Emerald
                    } else if (item.contains("TASK_BID") || item.contains("TASK_ANNOUNCE")) {
                        setTextFill(Color.web("#A78BFA")); // Purple
                    } else if (item.contains("CRASH")) {
                        setTextFill(Color.web("#F87171")); // Red
                    } else {
                        setTextFill(Color.web("#94A3B8")); // Grey/Blue
                    }
                }
            }
        });

        sidebar.getChildren().addAll(telemetryHeader, telemetryScroll, gossipHeaderBox, gossipListView);
        VBox.setVgrow(gossipListView, Priority.ALWAYS);
        return sidebar;
    }

    private Node createControlBar() {
        HBox bar = new HBox(12);
        bar.setPadding(new Insets(12, 20, 12, 20));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: #111827; -fx-border-color: #1F2937; -fx-border-width: 1 0 0 0;");

        Button spawnTaskBtn = createActionButton("📦 Spawn New Task", "#3B82F6");
        spawnTaskBtn.setOnAction(e -> spawnRandomTask());

        Button deadlockBtn = createActionButton("⚔️ Simulate Head-On Conflict", "#F59E0B");
        deadlockBtn.setOnAction(e -> triggerHeadOnConflict());

        targetRobotCombo = new ComboBox<>();
        targetRobotCombo.setPromptText("Select AGV");
        targetRobotCombo.setStyle("-fx-background-color: #1E293B; -fx-text-fill: white; -fx-border-color: #334155;");

        Button killRobotBtn = createActionButton("⚡ Kill Selected AGV", "#EF4444");
        killRobotBtn.setOnAction(e -> killSelectedRobot());

        Button addRobotBtn = createActionButton("➕ Add New AGV", "#10B981");
        addRobotBtn.setOnAction(e -> addNewAgvNode());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label legend = new Label("Status: 🟦 IDLE  🟪 BIDDING  🟩 MOVING  🟧 MUTEX  🟥 CRASH");
        legend.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 11));
        legend.setTextFill(Color.web("#94A3B8"));

        bar.getChildren().addAll(spawnTaskBtn, deadlockBtn, targetRobotCombo, killRobotBtn, addRobotBtn, spacer, legend);
        return bar;
    }

    private Button createActionButton(String text, String hexColor) {
        Button btn = new Button(text);
        btn.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        btn.setTextFill(Color.WHITE);
        btn.setStyle("-fx-background-color: " + hexColor + "; -fx-background-radius: 6px; -fx-padding: 8 14 8 14; -fx-cursor: hand;");
        btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: derive(" + hexColor + ", 15%); -fx-background-radius: 6px; -fx-padding: 8 14 8 14; -fx-cursor: hand;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: " + hexColor + "; -fx-background-radius: 6px; -fx-padding: 8 14 8 14; -fx-cursor: hand;"));
        return btn;
    }

    /**
     * Initializes the default 3-AGV peer mesh fleet.
     */
    private void initFleet() {
        createAndStartRobot(1L, new GridPoint(2, 2), 100.0);
        createAndStartRobot(2L, new GridPoint(12, 6), 90.0);
        createAndStartRobot(3L, new GridPoint(22, 10), 85.0);
        updateTargetRobotCombo();
    }

    private AgvRobot createAndStartRobot(long nodeId, GridPoint initialPos, double battery) {
        AgvNode node = AgvNode.builder()
                .nodeId(nodeId)
                .host("127.0.0.1")
                .tcpPort(0) // Ephemeral random port
                .multicastPort(MULTICAST_PORT)
                .discoveryIntervalMs(300)
                .heartbeatIntervalMs(400)
                .heartbeatTimeoutMs(1500)
                .initialBattery(battery)
                .initialPosition(initialPos.toPosition2D())
                .build();

        AgvRobot robot = new AgvRobot(node, map, pathfinder, initialPos);

        // Register gossip wire logger
        node.registerMessageListener((msg, ch) -> logWireMessage(nodeId, msg));

        try {
            node.start();
            robots.add(robot);
            logGossip("[AGV-" + nodeId + "] Node started on port " + node.getTcpPort() + " at " + initialPos);
        } catch (Exception e) {
            log.error("Failed to start AGV Node {}", nodeId, e);
        }
        return robot;
    }

    private void logWireMessage(long receiverId, AgvMessage msg) {
        Opcode op = msg.getOpcode();
        if (op == Opcode.HEARTBEAT && filterHeartbeatCheck != null && filterHeartbeatCheck.isSelected()) {
            return;
        }

        String details = switch (op) {
            case PEER_DISCOVERY -> "PEER_DISCOVERY Handshake";
            case HEARTBEAT -> "HEARTBEAT (Ping)";
            case MUTEX_REQUEST -> {
                MutexRequestPayload p = MutexRequestPayload.fromBytes(msg.getPayload());
                yield "MUTEX_REQ (" + p.cellId() + ", L=" + p.requestTimestamp() + ")";
            }
            case MUTEX_REPLY -> {
                MutexReplyPayload p = MutexReplyPayload.fromBytes(msg.getPayload());
                yield "MUTEX_REPLY (Granted " + p.cellId() + ")";
            }
            case MUTEX_RELEASE -> {
                MutexReleasePayload p = MutexReleasePayload.fromBytes(msg.getPayload());
                yield "MUTEX_RELEASE (" + p.cellId() + ")";
            }
            case TASK_ANNOUNCE -> {
                TaskAnnouncePayload p = TaskAnnouncePayload.fromBytes(msg.getPayload());
                yield "TASK_ANNOUNCE (" + p.taskId() + " at [" + (int) p.pickupX() + "," + (int) p.pickupY() + "])";
            }
            case TASK_BID -> {
                TaskBidPayload p = TaskBidPayload.fromBytes(msg.getPayload());
                yield "TASK_BID (" + p.taskId() + ", Cost=" + String.format("%.2f", p.cost()) + ", Bat=" + (int) p.batteryLevel() + "%)";
            }
            case TASK_AWARD -> {
                TaskAwardPayload p = TaskAwardPayload.fromBytes(msg.getPayload());
                yield "TASK_AWARD (" + p.taskId() + " -> Winner AGV-" + p.winnerNodeId() + ")";
            }
        };

        String logEntry = String.format("[%s] AGV-%d -> AGV-%d %s [Seq=%d, L=%d]",
                LocalTime.now().format(TIME_FMT),
                msg.getSenderNodeId(),
                receiverId,
                details,
                msg.getHeader().sequenceId(),
                msg.getHeader().lamportTimestamp());

        logGossip(logEntry);
    }

    private void logGossip(String line) {
        Platform.runLater(() -> {
            if (gossipListView != null) {
                gossipListView.getItems().add(line);
                if (gossipListView.getItems().size() > 200) {
                    gossipListView.getItems().remove(0);
                }
                gossipListView.scrollTo(gossipListView.getItems().size() - 1);
            }
        });
    }

    private void startSimulationLoops() {
        // 1. Render loop (AnimationTimer running at ~60 FPS)
        renderLoop = new AnimationTimer() {
            private long lastNanoTime = 0;

            @Override
            public void handle(long currentNanoTime) {
                if (lastNanoTime == 0) {
                    lastNanoTime = currentNanoTime;
                    return;
                }
                double deltaSeconds = (currentNanoTime - lastNanoTime) / 1_000_000_000.0;
                lastNanoTime = currentNanoTime;

                // Interpolate visual positions
                for (AgvRobot robot : robots) {
                    robot.interpolateVisualPosition(deltaSeconds);
                }

                // Render Map and Fleet
                renderCanvas();

                // Periodic telemetry update (every ~200ms)
                long now = System.currentTimeMillis();
                if (now - lastPhysicsTime > 120) {
                    lastPhysicsTime = now;
                    updateTelemetryUI();
                }
            }
        };
        renderLoop.start();

        // 2. Physics & Logic step executor (runs at 10 Hz)
        simExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agv-sim-physics");
            t.setDaemon(true);
            return t;
        });

        simExecutor.scheduleAtFixedRate(() -> {
            try {
                Set<GridPoint> occupied = robots.stream()
                        .filter(r -> !r.isCrashed())
                        .map(AgvRobot::getCurrentGridPos)
                        .collect(Collectors.toSet());

                for (AgvRobot robot : robots) {
                    Set<GridPoint> others = new HashSet<>(occupied);
                    others.remove(robot.getCurrentGridPos());
                    robot.updatePhysicsAndLogic(others);
                }
            } catch (Exception e) {
                log.error("Error in simulation physics loop", e);
            }
        }, 200, 100, TimeUnit.MILLISECONDS);
    }

    private void renderCanvas() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

        // 1. Background Grid & Lanes
        gc.setFill(Color.web("#0F172A"));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        gc.setStroke(Color.web("#1E293B"));
        gc.setLineWidth(1.0);
        for (int x = 0; x <= map.getWidth(); x++) {
            gc.strokeLine(x * CELL_SIZE, 0, x * CELL_SIZE, map.getHeight() * CELL_SIZE);
        }
        for (int y = 0; y <= map.getHeight(); y++) {
            gc.strokeLine(0, y * CELL_SIZE, map.getWidth() * CELL_SIZE, y * CELL_SIZE);
        }

        // 2. Render Map Cells (Obstacles, Intersections, Charging Docks)
        for (int x = 0; x < map.getWidth(); x++) {
            for (int y = 0; y < map.getHeight(); y++) {
                CellType type = map.getCellType(x, y);
                double px = x * CELL_SIZE;
                double py = y * CELL_SIZE;

                switch (type) {
                    case OBSTACLE -> {
                        // Storage Racks
                        gc.setFill(Color.web("#1E293B"));
                        gc.fillRoundRect(px + 2, py + 2, CELL_SIZE - 4, CELL_SIZE - 4, 6, 6);
                        gc.setStroke(Color.web("#334155"));
                        gc.setLineWidth(1.5);
                        gc.strokeRoundRect(px + 2, py + 2, CELL_SIZE - 4, CELL_SIZE - 4, 6, 6);

                        // Double rack bar pattern
                        gc.setStroke(Color.web("#293548"));
                        gc.setLineWidth(1.0);
                        gc.strokeLine(px + 4, py + CELL_SIZE / 2, px + CELL_SIZE - 4, py + CELL_SIZE / 2);
                    }
                    case CHARGING_STATION -> {
                        // Charging Docks
                        gc.setFill(Color.web("#064E3B", 0.5));
                        gc.fillRect(px + 1, py + 1, CELL_SIZE - 2, CELL_SIZE - 2);
                        gc.setStroke(Color.web("#10B981"));
                        gc.setLineWidth(1.5);
                        gc.strokeRect(px + 1, py + 1, CELL_SIZE - 2, CELL_SIZE - 2);

                        gc.setFill(Color.web("#34D399"));
                        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
                        gc.fillText("⚡", px + 12, py + 22);
                    }
                    case INTERSECTION -> {
                        // Intersections
                        String cellId = map.getIntersectionCellId(x, y);
                        boolean isHeld = robots.stream().anyMatch(r -> cellId.equals(r.getHeldIntersection()));
                        boolean isWaited = robots.stream().anyMatch(r -> cellId.equals(r.getWaitingIntersection()));

                        if (isHeld) {
                            // Currently HELD by a robot: Glowing Warm Orange
                            gc.setFill(Color.web("#EA580C", 0.5));
                            gc.fillRect(px + 1, py + 1, CELL_SIZE - 2, CELL_SIZE - 2);
                            gc.setStroke(Color.web("#F97316"));
                            gc.setLineWidth(2.0);
                            gc.strokeRect(px + 1, py + 1, CELL_SIZE - 2, CELL_SIZE - 2);

                            gc.setFill(Color.WHITE);
                            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9));
                            gc.fillText("🔒 HELD", px + 3, py + 22);
                        } else if (isWaited) {
                            // Waiting mutex
                            gc.setFill(Color.web("#CA8A04", 0.3));
                            gc.fillRect(px + 1, py + 1, CELL_SIZE - 2, CELL_SIZE - 2);
                            gc.setStroke(Color.web("#EAB308"));
                            gc.setLineWidth(2.0);
                            gc.strokeRect(px + 1, py + 1, CELL_SIZE - 2, CELL_SIZE - 2);

                            gc.setFill(Color.web("#FDE047"));
                            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9));
                            gc.fillText("⏳ REQ", px + 4, py + 22);
                        } else {
                            // Uncontested intersection
                            gc.setStroke(Color.web("#CA8A04", 0.5));
                            gc.setLineWidth(1.0);
                            gc.strokeRect(px + 2, py + 2, CELL_SIZE - 4, CELL_SIZE - 4);
                        }
                    }
                    case EMPTY -> {
                    }
                }
            }
        }

        // 3. Render Active Tasks (Pickup & Delivery markers)
        for (Task task : activeTasks) {
            if (task.getStatus() != Task.Status.COMPLETED) {
                double px = task.getPickupX() * CELL_SIZE + CELL_SIZE / 2;
                double py = task.getPickupY() * CELL_SIZE + CELL_SIZE / 2;
                double dx = task.getDeliveryX() * CELL_SIZE + CELL_SIZE / 2;
                double dy = task.getDeliveryY() * CELL_SIZE + CELL_SIZE / 2;

                // Pickup: Glowing Emerald Circle
                gc.setFill(Color.web("#10B981", 0.3));
                gc.fillOval(px - 14, py - 14, 28, 28);
                gc.setStroke(Color.web("#34D399"));
                gc.setLineWidth(1.5);
                gc.strokeOval(px - 10, py - 10, 20, 20);
                gc.setFill(Color.WHITE);
                gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9));
                gc.fillText("📦 " + task.getTaskId(), px - 18, py - 14);

                // Delivery: Checkered Target
                gc.setFill(Color.web("#3B82F6", 0.3));
                gc.fillOval(dx - 14, dy - 14, 28, 28);
                gc.setStroke(Color.web("#60A5FA"));
                gc.setLineWidth(1.5);
                gc.strokeOval(dx - 10, dy - 10, 20, 20);
                gc.setFill(Color.WHITE);
                gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9));
                gc.fillText("🏁 Goal", dx - 12, dy - 14);
            }
        }

        // 4. Render Planned Paths (Waypoints trails)
        for (AgvRobot robot : robots) {
            if (robot.isCrashed()) continue;
            List<GridPoint> path = robot.getRemainingPath();
            if (!path.isEmpty()) {
                gc.setStroke(Color.web("#3B82F6", 0.35));
                gc.setLineWidth(2.0);
                double prevX = robot.getRenderX() * CELL_SIZE + CELL_SIZE / 2;
                double prevY = robot.getRenderY() * CELL_SIZE + CELL_SIZE / 2;
                for (GridPoint wp : path) {
                    double wx = wp.x() * CELL_SIZE + CELL_SIZE / 2;
                    double wy = wp.y() * CELL_SIZE + CELL_SIZE / 2;
                    gc.strokeLine(prevX, prevY, wx, wy);
                    prevX = wx;
                    prevY = wy;
                }
            }
        }

        // 5. Render AGV Robots
        for (AgvRobot robot : robots) {
            double cx = robot.getRenderX() * CELL_SIZE + CELL_SIZE / 2;
            double cy = robot.getRenderY() * CELL_SIZE + CELL_SIZE / 2;
            double radius = 14.0;

            Color statusColor = switch (robot.getState()) {
                case IDLE -> Color.web("#3B82F6");              // Blue
                case BIDDING -> Color.web("#8B5CF6");           // Purple
                case MOVING, BUSY -> Color.web("#06B6D4");      // Teal
                case WAITING_MUTEX -> Color.web("#F59E0B");     // Amber
                case LOW_BATTERY_CHARGING -> Color.web("#10B981"); // Green
                case CRASHED -> Color.web("#EF4444");           // Crimson
            };

            // Outer glow
            gc.setFill(statusColor.deriveColor(0, 1, 1, 0.25));
            gc.fillOval(cx - radius - 4, cy - radius - 4, (radius + 4) * 2, (radius + 4) * 2);

            // Robot Body
            gc.setFill(Color.web("#0F172A"));
            gc.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
            gc.setStroke(statusColor);
            gc.setLineWidth(2.5);
            gc.strokeOval(cx - radius, cy - radius, radius * 2, radius * 2);

            // Node ID Label
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
            gc.fillText("A" + robot.getNodeId(), cx - 7, cy + 4);

            // Cargo Box Icon
            if (robot.isCarryingCargo()) {
                gc.setFill(Color.web("#F59E0B"));
                gc.fillRect(cx + 8, cy - 8, 8, 8);
                gc.setStroke(Color.WHITE);
                gc.setLineWidth(1.0);
                gc.strokeRect(cx + 8, cy - 8, 8, 8);
            }

            // Battery bar above robot
            double batWidth = 22.0;
            double batHeight = 3.5;
            double batX = cx - batWidth / 2;
            double batY = cy - radius - 8;

            gc.setFill(Color.web("#334155"));
            gc.fillRect(batX, batY, batWidth, batHeight);

            double level = robot.getBatteryLevel();
            double fillW = batWidth * (level / 100.0);
            Color batColor = level > 50 ? Color.web("#10B981") : (level > 20 ? Color.web("#F59E0B") : Color.web("#EF4444"));
            gc.setFill(batColor);
            gc.fillRect(batX, batY, fillW, batHeight);
        }
    }

    private void updateTelemetryUI() {
        Platform.runLater(() -> {
            long aliveCount = robots.stream().filter(r -> !r.isCrashed()).count();
            activeNodesLabel.setText("AGVs Online: " + aliveCount);
            activeTasksLabel.setText("Active Tasks: " + activeTasks.stream().filter(t -> t.getStatus() != Task.Status.COMPLETED).count());

            long maxLamport = robots.stream().mapToLong(r -> r.getNode().getLamportClock().getTime()).max().orElse(0);
            maxLamportLabel.setText("Max Lamport Clock: " + maxLamport);

            telemetryList.getChildren().clear();
            for (AgvRobot r : robots) {
                telemetryList.getChildren().add(createRobotTelemetryCard(r));
            }
        });
    }

    private Node createRobotTelemetryCard(AgvRobot robot) {
        VBox card = new VBox(4);
        card.setPadding(new Insets(8, 10, 8, 10));
        card.setStyle("-fx-background-color: #1E293B; -fx-border-color: #334155; -fx-border-radius: 6px; -fx-background-radius: 6px;");

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);

        Label idLabel = new Label("AGV #" + robot.getNodeId());
        idLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        idLabel.setTextFill(Color.WHITE);

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        AgentState st = robot.getState();
        String badgeColor = switch (st) {
            case IDLE -> "#3B82F6";
            case BIDDING -> "#8B5CF6";
            case MOVING, BUSY -> "#06B6D4";
            case WAITING_MUTEX -> "#F59E0B";
            case LOW_BATTERY_CHARGING -> "#10B981";
            case CRASHED -> "#EF4444";
        };
        Label stateBadge = new Label(st.name());
        stateBadge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
        stateBadge.setTextFill(Color.WHITE);
        stateBadge.setPadding(new Insets(2, 6, 2, 6));
        stateBadge.setStyle("-fx-background-color: " + badgeColor + "; -fx-background-radius: 4px;");

        top.getChildren().addAll(idLabel, sp, stateBadge);

        HBox details = new HBox(12);
        Label posLbl = new Label(String.format("Pos: (%d, %d)", robot.getCurrentGridPos().x(), robot.getCurrentGridPos().y()));
        posLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");

        Label batLbl = new Label(String.format("Bat: %.1f%%", robot.getBatteryLevel()));
        batLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");

        Label clockLbl = new Label("L: " + robot.getNode().getLamportClock().getTime());
        clockLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");

        details.getChildren().addAll(posLbl, batLbl, clockLbl);

        if (robot.getWaitingIntersection() != null) {
            Label mutexLbl = new Label("Waiting Mutex: " + robot.getWaitingIntersection());
            mutexLbl.setStyle("-fx-text-fill: #FBBF24; -fx-font-size: 10px;");
            card.getChildren().addAll(top, details, mutexLbl);
        } else if (robot.getHeldIntersection() != null) {
            Label mutexLbl = new Label("Holding Mutex: " + robot.getHeldIntersection());
            mutexLbl.setStyle("-fx-text-fill: #F97316; -fx-font-size: 10px;");
            card.getChildren().addAll(top, details, mutexLbl);
        } else {
            card.getChildren().addAll(top, details);
        }

        return card;
    }

    private void updateTargetRobotCombo() {
        Platform.runLater(() -> {
            targetRobotCombo.getItems().clear();
            for (AgvRobot r : robots) {
                targetRobotCombo.getItems().add(r.getNodeId());
            }
            if (!targetRobotCombo.getItems().isEmpty()) {
                targetRobotCombo.getSelectionModel().selectFirst();
            }
        });
    }

    // ================= ACTIONS =================

    /**
     * Spawns a random transport task and initiates Contract Net Protocol auction.
     */
    private void spawnRandomTask() {
        List<GridPoint> emptyPoints = map.getWalkableEmptyPoints();
        if (emptyPoints.size() < 2) return;

        Collections.shuffle(emptyPoints);
        GridPoint pickup = emptyPoints.get(0);
        GridPoint delivery = emptyPoints.get(1);

        String taskId = "TASK-" + taskCounter.getAndIncrement();
        Task task = new Task(taskId, pickup.x(), pickup.y(), delivery.x(), delivery.y());
        activeTasks.add(task);

        // Find an active node to act as Auction Manager
        Optional<AgvRobot> manager = robots.stream().filter(r -> !r.isCrashed()).findFirst();
        if (manager.isPresent()) {
            logGossip(String.format("[USER] Spawned %s (Pickup: %s -> Delivery: %s). AGV-%d announcing auction...",
                    taskId, pickup, delivery, manager.get().getNodeId()));
            manager.get().getNode().announceTask(task);
        } else {
            logGossip("[WARN] No active AGV available to announce task!");
        }
    }

    /**
     * Simulates a head-on intersection conflict to showcase Ricart-Agrawala deadlock-free resolution.
     */
    private void triggerHeadOnConflict() {
        if (robots.size() < 2) {
            logGossip("[WARN] Need at least 2 AGVs to simulate conflict!");
            return;
        }

        AgvRobot r1 = robots.get(0);
        AgvRobot r2 = robots.get(1);

        if (r1.isCrashed() || r2.isCrashed()) {
            logGossip("[WARN] Cannot simulate: One of the test AGVs is crashed.");
            return;
        }

        // Direct both robots through intersection at (7, 6)
        GridPoint conflictIntersection = new GridPoint(7, 6);
        GridPoint target1 = new GridPoint(12, 6); // R1 moves from left (2,6) to right (12,6)
        GridPoint target2 = new GridPoint(2, 6);  // R2 moves from right (12,6) to left (2,6)

        logGossip(String.format("[SIMULATE] Directing AGV-%d and AGV-%d into head-on path across Intersection (7, 6)!",
                r1.getNodeId(), r2.getNodeId()));

        r1.setDestination(target1);
        r2.setDestination(target2);
    }

    /**
     * Kills selected AGV to demonstrate fault-tolerance and dynamic re-auctioning.
     */
    private void killSelectedRobot() {
        Long selectedId = targetRobotCombo.getValue();
        if (selectedId == null) return;

        robots.stream()
                .filter(r -> r.getNodeId() == selectedId)
                .findFirst()
                .ifPresent(r -> {
                    logGossip("[CRASH] Power failure simulated on AGV-" + selectedId + "! Terminating node...");
                    r.crash();
                });
    }

    /**
     * Dynamically adds a new AGV node to the P2P mesh network.
     */
    private void addNewAgvNode() {
        long newId = nextNodeId.getAndIncrement();
        List<GridPoint> emptyPoints = map.getWalkableEmptyPoints();
        GridPoint spawnPoint = emptyPoints.isEmpty() ? new GridPoint(0, 0) : emptyPoints.get(ThreadLocalRandom.current().nextInt(emptyPoints.size()));

        AgvRobot newRobot = createAndStartRobot(newId, spawnPoint, 100.0);
        updateTargetRobotCombo();
        logGossip("[SPAWN] New AGV-" + newId + " initialized at " + spawnPoint + ". Joining P2P Mesh...");
    }

    private void shutdown() {
        log.info("Shutting down AGV Fleet Simulator...");
        if (renderLoop != null) {
            renderLoop.stop();
        }
        if (simExecutor != null) {
            simExecutor.shutdownNow();
        }
        for (AgvRobot robot : robots) {
            try {
                robot.getNode().stop();
            } catch (Exception ignored) {
            }
        }
        log.info("AGV Fleet Simulator shutdown complete.");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
