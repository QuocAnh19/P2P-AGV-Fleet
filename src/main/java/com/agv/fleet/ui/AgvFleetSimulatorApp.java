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
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Screen;
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
 * Enterprise Fleet Management System (FMS) Dashboard & Digital Twin.
 * <p>
 * Decentralized P2P AGV Fleet Coordination with:
 * <ul>
 *   <li>Netty Binary Codec (36-byte header, zero broker)</li>
 *   <li>UDP Multicast Auto-Discovery & TCP Full-Mesh Connectivity</li>
 *   <li>Lamport Logical Clock & Ricart-Agrawala Mutual Exclusion</li>
 *   <li>Contract Net Protocol (CNP) Multi-Agent Task Auctioning</li>
 *   <li>Dynamic A* Obstacle-Avoidance Navigation</li>
 *   <li>Fully Configurable Industrial Task & AGV Dispatching</li>
 *   <li>Repeatable & Continuous Intersection Conflict Simulation</li>
 * </ul>
 */
public class AgvFleetSimulatorApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(AgvFleetSimulatorApp.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final double CELL_SIZE = 28.0;
    private static final int MULTICAST_PORT = 19876;

    private final GridMap map = GridMap.createDefaultWarehouseMap();
    private final DynamicAStarPathfinder pathfinder = new DynamicAStarPathfinder();
    private final List<AgvRobot> robots = new CopyOnWriteArrayList<>();
    private final List<Task> allTasks = new CopyOnWriteArrayList<>();

    private final AtomicInteger taskCounter = new AtomicInteger(1001);
    private final AtomicInteger nextNodeId = new AtomicInteger(4);

    // Conflict Simulation State
    private volatile boolean continuousConflictActive = false;
    private volatile GridPoint activeConflictIntersection = null;
    private volatile List<AgvRobot> conflictParticipants = new ArrayList<>();
    private volatile int conflictCycleCount = 0;
    private volatile String conflictModeName = "None";

    // Simulation Speed & Physics
    private volatile double simulationSpeedMultiplier = 1.0;
    private volatile boolean fleetPaused = false;
    private long lastPhysicsTime = 0;
    private ScheduledExecutorService simExecutor;
    private AnimationTimer renderLoop;

    // Selection & Inspection
    private volatile AgvRobot selectedRobot = null;
    private volatile GridPoint hoveredCell = null;

    // UI Controls
    private Stage primaryStage;
    private Canvas canvas;
    private VBox telemetryList;
    private VBox tasksListVBox;
    private ListView<String> gossipListView;
    private Label activeNodesLabel;
    private Label activeTasksLabel;
    private Label completedTasksLabel;
    private Label activeMutexLabel;
    private Label maxLamportLabel;
    private Label fleetHealthLabel;
    private Label mapStatusBar;
    private Label conflictStatusLabel;
    private Button stopConflictBtn;
    private Button pauseFleetBtn;
    private String gossipFilter = "ALL"; // ALL, MUTEX, CNP, HEARTBEAT

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        primaryStage.setTitle("P2P AGV Fleet Management System (FMS) - Enterprise Dispatcher");

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #0B0F19;");

        // 1. Top Enterprise KPI Bar
        root.setTop(createTopKpiBar());

        // 2. Center: Interactive Map & Canvas
        root.setCenter(createCenterMapArea());

        // 3. Right: Tabbed Operations Panel
        root.setRight(createRightTabbedSidebar());

        // 4. Bottom: Enterprise Actions Bar
        root.setBottom(createEnterpriseControlBar());

        Rectangle2D visualBounds = Screen.getPrimary().getVisualBounds();
        double prefWidth = Math.min(1240, visualBounds.getWidth() * 0.96);
        double prefHeight = Math.min(650, visualBounds.getHeight() * 0.88);

        Scene scene = new Scene(root, prefWidth, prefHeight);
        primaryStage.setScene(scene);
        primaryStage.setMaxHeight(visualBounds.getHeight());
        primaryStage.setMaxWidth(visualBounds.getWidth());
        primaryStage.centerOnScreen();
        primaryStage.show();

        // 5. Initialize fleet
        initFleet();

        // 6. Start Render & Simulation loops
        startSimulationLoops();

        primaryStage.setOnCloseRequest(e -> shutdown());
    }

    // =========================================================================
    // 1. TOP KPI & OPERATIONAL CONTROL BAR
    // =========================================================================
    private Node createTopKpiBar() {
        VBox topContainer = new VBox(0);
        topContainer.setStyle("-fx-background-color: #111827; -fx-border-color: #1F2937; -fx-border-width: 0 0 1 0;");

        // Title Row
        HBox titleRow = new HBox(12);
        titleRow.setPadding(new Insets(6, 14, 2, 14));
        titleRow.setAlignment(Pos.CENTER_LEFT);

        VBox titleBox = new VBox(1);
        Text title = new Text("ENTERPRISE P2P AGV FLEET DISPATCHER");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        title.setFill(Color.WHITE);

        Text subTitle = new Text("Decentralized Multi-Agent Bus • Zero Broker • Ricart-Agrawala Mutex • CNP Adaptive Auction");
        subTitle.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 10));
        subTitle.setFill(Color.web("#94A3B8"));
        titleBox.getChildren().addAll(title, subTitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Simulation Speed & Global Controls
        HBox speedControls = new HBox(6);
        speedControls.setAlignment(Pos.CENTER_RIGHT);

        Label speedLabel = new Label("Sim Speed:");
        speedLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");

        ToggleGroup speedGroup = new ToggleGroup();
        RadioButton sHalf = createSpeedRadio("0.5x", 0.5, speedGroup, false);
        RadioButton s1 = createSpeedRadio("1.0x", 1.0, speedGroup, true);
        RadioButton s2 = createSpeedRadio("2.0x", 2.0, speedGroup, false);
        RadioButton s5 = createSpeedRadio("5.0x", 5.0, speedGroup, false);

        pauseFleetBtn = new Button("⏸ Pause");
        pauseFleetBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 10px; -fx-padding: 3 8 3 8; -fx-cursor: hand; -fx-background-radius: 4px;");
        pauseFleetBtn.setOnAction(e -> toggleFleetPause());

        speedControls.getChildren().addAll(speedLabel, sHalf, s1, s2, s5, pauseFleetBtn);

        titleRow.getChildren().addAll(titleBox, spacer, speedControls);

        // KPI Metrics Row
        HBox kpiRow = new HBox(8);
        kpiRow.setPadding(new Insets(3, 14, 5, 14));
        kpiRow.setAlignment(Pos.CENTER_LEFT);

        activeNodesLabel = createKpiCard("FLEET SIZE", "3 Active / 3 Total", "#3B82F6");
        activeTasksLabel = createKpiCard("ACTIVE ORDERS", "0 In-Transit", "#06B6D4");
        completedTasksLabel = createKpiCard("DELIVERED ORDERS", "0 Completed", "#10B981");
        activeMutexLabel = createKpiCard("INTERSECTION MUTEX", "0 Contested", "#F59E0B");
        maxLamportLabel = createKpiCard("MAX LAMPORT CLOCK", "L = 0", "#8B5CF6");
        fleetHealthLabel = createKpiCard("FLEET HEALTH", "100% Online", "#10B981");

        conflictStatusLabel = new Label("Conflict Demo: Inactive");
        conflictStatusLabel.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #94A3B8; -fx-padding: 4 8 4 8; -fx-background-radius: 5px; -fx-border-color: #334155; -fx-border-radius: 5px; -fx-font-size: 10px; -fx-font-weight: bold;");

        stopConflictBtn = new Button("⏹ Stop");
        stopConflictBtn.setVisible(false);
        stopConflictBtn.setStyle("-fx-background-color: #DC2626; -fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 4 8 4 8; -fx-background-radius: 5px;");
        stopConflictBtn.setOnAction(e -> stopConflictSimulation());

        Region kpiSpacer = new Region();
        HBox.setHgrow(kpiSpacer, Priority.ALWAYS);

        kpiRow.getChildren().addAll(activeNodesLabel, activeTasksLabel, completedTasksLabel, activeMutexLabel, maxLamportLabel, fleetHealthLabel, kpiSpacer, conflictStatusLabel, stopConflictBtn);

        topContainer.getChildren().addAll(titleRow, kpiRow);
        return topContainer;
    }

    private RadioButton createSpeedRadio(String text, double speed, ToggleGroup group, boolean selected) {
        RadioButton rb = new RadioButton(text);
        rb.setToggleGroup(group);
        rb.setSelected(selected);
        rb.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 10px; -fx-cursor: hand;");
        rb.setOnAction(e -> {
            simulationSpeedMultiplier = speed;
            logGossip("[CONFIG] Simulation speed changed to " + text);
        });
        return rb;
    }

    private Label createKpiCard(String labelText, String valText, String colorHex) {
        Label card = new Label(labelText + "\n" + valText);
        card.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9.5));
        card.setTextFill(Color.WHITE);
        card.setPadding(new Insets(3, 8, 3, 8));
        card.setStyle("-fx-background-color: " + colorHex + "22; -fx-border-color: " + colorHex + "88; -fx-border-radius: 5px; -fx-background-radius: 5px;");
        return card;
    }

    private void toggleFleetPause() {
        fleetPaused = !fleetPaused;
        for (AgvRobot robot : robots) {
            robot.setPaused(fleetPaused);
        }
        if (fleetPaused) {
            pauseFleetBtn.setText("▶ Resume Fleet");
            pauseFleetBtn.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 4 10 4 10; -fx-cursor: hand; -fx-background-radius: 4px;");
            logGossip("[FLEET] Entire fleet operation PAUSED by operator.");
        } else {
            pauseFleetBtn.setText("⏸ Pause Fleet");
            pauseFleetBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 4 10 4 10; -fx-cursor: hand; -fx-background-radius: 4px;");
            logGossip("[FLEET] Fleet operation RESUMED.");
        }
    }

    // =========================================================================
    // 2. CENTER INTERACTIVE DIGITAL TWIN MAP
    // =========================================================================
    private Node createCenterMapArea() {
        canvas = new Canvas(map.getWidth() * CELL_SIZE, map.getHeight() * CELL_SIZE);
        canvas.setStyle("-fx-cursor: crosshair;");

        // Hover & Click Interaction
        canvas.setOnMouseMoved(this::onCanvasMouseMoved);
        canvas.setOnMouseClicked(this::onCanvasMouseClicked);
        canvas.setOnMouseExited(e -> {
            hoveredCell = null;
            updateMapStatusBar();
        });

        StackPane canvasWrapper = new StackPane(canvas);
        canvasWrapper.setPadding(new Insets(4));
        canvasWrapper.setStyle("-fx-background-color: #0F172A; -fx-border-color: #1E293B; -fx-border-width: 1.5px; -fx-border-radius: 8px; -fx-background-radius: 8px;");

        // Status bar under map
        mapStatusBar = new Label("Warehouse Map: 24 x 16 Grid | Click on any AGV to select & inspect | Hover to inspect cell coordinates");
        mapStatusBar.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 10));
        mapStatusBar.setTextFill(Color.web("#94A3B8"));
        mapStatusBar.setPadding(new Insets(2, 6, 2, 6));

        VBox centerBox = new VBox(4, canvasWrapper, mapStatusBar);
        centerBox.setPadding(new Insets(4, 10, 4, 10));
        centerBox.setAlignment(Pos.CENTER);

        ScrollPane scrollPane = new ScrollPane(centerBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background: #0B0F19; -fx-border-color: transparent; -fx-background-color: transparent;");
        return scrollPane;
    }

    private void onCanvasMouseMoved(MouseEvent e) {
        int gx = (int) (e.getX() / CELL_SIZE);
        int gy = (int) (e.getY() / CELL_SIZE);
        if (map.isInBounds(gx, gy)) {
            hoveredCell = new GridPoint(gx, gy);
        } else {
            hoveredCell = null;
        }
        updateMapStatusBar();
    }

    private void onCanvasMouseClicked(MouseEvent e) {
        int gx = (int) (e.getX() / CELL_SIZE);
        int gy = (int) (e.getY() / CELL_SIZE);
        if (!map.isInBounds(gx, gy)) return;

        GridPoint clicked = new GridPoint(gx, gy);

        // Check if an AGV was clicked
        Optional<AgvRobot> clickedRobot = robots.stream()
                .filter(r -> r.getCurrentGridPos().equals(clicked) || Math.hypot(r.getRenderX() - gx, r.getRenderY() - gy) < 0.8)
                .findFirst();

        if (clickedRobot.isPresent()) {
            selectedRobot = clickedRobot.get();
            logGossip("[INSPECT] Selected AGV #" + selectedRobot.getNodeId() + " (" + selectedRobot.getModelName() + ")");
        } else {
            // Clicked on map cell
            if (selectedRobot != null && !selectedRobot.isCrashed() && map.isWalkable(clicked)) {
                // Manual dispatch clicked!
                showQuickDispatchConfirmation(selectedRobot, clicked);
            }
        }
    }

    private void showQuickDispatchConfirmation(AgvRobot robot, GridPoint destination) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(primaryStage);
        alert.setTitle("Direct Dispatch AGV");
        alert.setHeaderText("Dispatch AGV #" + robot.getNodeId() + " to " + destination + "?");
        alert.setContentText("The robot will compute a dynamic A* path and navigate immediately.");

        alert.showAndWait().ifPresent(res -> {
            if (res == ButtonType.OK) {
                robot.setDestination(destination);
                robot.getNode().setAgentState(AgentState.MOVING);
                logGossip(String.format("[DISPATCH] Manually dispatched AGV-%d to %s", robot.getNodeId(), destination));
            }
        });
    }

    private void updateMapStatusBar() {
        if (hoveredCell != null) {
            CellType type = map.getCellType(hoveredCell);
            String occupant = robots.stream()
                    .filter(r -> r.getCurrentGridPos().equals(hoveredCell))
                    .map(r -> "AGV #" + r.getNodeId() + " (" + r.getState() + ")")
                    .findFirst().orElse("None");

            String selText = selectedRobot != null ? " | Selected: AGV #" + selectedRobot.getNodeId() + " (" + selectedRobot.getState() + ", Bat=" + (int) selectedRobot.getBatteryLevel() + "%)" : "";
            mapStatusBar.setText(String.format("Coordinates: (%d, %d) | Type: %s | Occupant: %s%s",
                    hoveredCell.x(), hoveredCell.y(), type, occupant, selText));
        } else {
            String selText = selectedRobot != null ? " | Selected: AGV #" + selectedRobot.getNodeId() + " (" + selectedRobot.getState() + ")" : "";
            mapStatusBar.setText("Warehouse Map: 24 x 16 Grid | Click on any AGV to inspect" + selText);
        }
    }

    // =========================================================================
    // 3. RIGHT TABBED ENTERPRISE SIDEBAR
    // =========================================================================
    private Node createRightTabbedSidebar() {
        TabPane tabPane = new TabPane();
        tabPane.setPrefWidth(410);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.setStyle("-fx-background-color: #0F172A; -fx-border-color: #1E293B; -fx-border-width: 0 0 0 1px;");

        // Tab 1: Fleet Operations & AGV Telemetry
        Tab fleetTab = new Tab("🚜 Fleet Operations", createFleetTabContent());

        // Tab 2: Orders & Task Manager
        Tab ordersTab = new Tab("📋 Orders & Tasks", createOrdersTabContent());

        // Tab 3: P2P Network Wire & Gossip
        Tab networkTab = new Tab("📡 P2P Wire Traffic", createNetworkTabContent());

        tabPane.getTabs().addAll(fleetTab, ordersTab, networkTab);
        return tabPane;
    }

    private Node createFleetTabContent() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));
        box.setStyle("-fx-background-color: #0F172A;");

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        Label lbl = new Label("ACTIVE AGVs IN DECENTRALIZED MESH");
        lbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        lbl.setTextFill(Color.web("#E2E8F0"));

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button addBtn = new Button("➕ Deploy AGV");
        addBtn.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 4 10 4 10; -fx-background-radius: 4px;");
        addBtn.setOnAction(e -> showAgvConfigDialog());

        top.getChildren().addAll(lbl, sp, addBtn);

        telemetryList = new VBox(8);
        ScrollPane scroll = new ScrollPane(telemetryList);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: #0F172A; -fx-border-color: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        box.getChildren().addAll(top, scroll);
        return box;
    }

    private Node createOrdersTabContent() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));
        box.setStyle("-fx-background-color: #0F172A;");

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        Label lbl = new Label("ENTERPRISE ORDER & TASK QUEUE");
        lbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        lbl.setTextFill(Color.web("#E2E8F0"));

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button newTaskBtn = new Button("📦 New Order");
        newTaskBtn.setStyle("-fx-background-color: #3B82F6; -fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 4 10 4 10; -fx-background-radius: 4px;");
        newTaskBtn.setOnAction(e -> showTaskConfigDialog());

        top.getChildren().addAll(lbl, sp, newTaskBtn);

        tasksListVBox = new VBox(8);
        ScrollPane scroll = new ScrollPane(tasksListVBox);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: #0F172A; -fx-border-color: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        box.getChildren().addAll(top, scroll);
        return box;
    }

    private Node createNetworkTabContent() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));
        box.setStyle("-fx-background-color: #0F172A;");

        // Filter Controls
        HBox filterBox = new HBox(8);
        filterBox.setAlignment(Pos.CENTER_LEFT);

        Label filterLbl = new Label("Filter:");
        filterLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");

        ComboBox<String> filterCombo = new ComboBox<>();
        filterCombo.getItems().addAll("ALL", "MUTEX ONLY", "CNP AUCTION ONLY", "DISCOVERY & HEARTBEAT");
        filterCombo.setValue("ALL");
        filterCombo.setStyle("-fx-background-color: #1E293B; -fx-text-fill: white; -fx-font-size: 11px;");
        filterCombo.setOnAction(e -> {
            gossipFilter = filterCombo.getValue();
            logGossip("[FILTER] Wire traffic filter set to " + gossipFilter);
        });

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button clearBtn = new Button("Clear Log");
        clearBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 3 8 3 8;");
        clearBtn.setOnAction(e -> gossipListView.getItems().clear());

        filterBox.getChildren().addAll(filterLbl, filterCombo, sp, clearBtn);

        gossipListView = new ListView<>();
        gossipListView.setStyle("-fx-control-inner-background: #0B0F19; -fx-background-color: #0B0F19; -fx-border-color: #1E293B;");
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
                    } else if (item.contains("CRASH") || item.contains("FAIL")) {
                        setTextFill(Color.web("#F87171")); // Red
                    } else if (item.contains("DISPATCH") || item.contains("CONFIG")) {
                        setTextFill(Color.web("#38BDF8")); // Cyan
                    } else {
                        setTextFill(Color.web("#94A3B8")); // Grey
                    }
                }
            }
        });
        VBox.setVgrow(gossipListView, Priority.ALWAYS);

        box.getChildren().addAll(filterBox, gossipListView);
        return box;
    }

    // =========================================================================
    // 4. BOTTOM ENTERPRISE ACTION BAR
    // =========================================================================
    private Node createEnterpriseControlBar() {
        HBox bar = new HBox(8);
        bar.setPadding(new Insets(6, 14, 6, 14));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: #111827; -fx-border-color: #1F2937; -fx-border-width: 1 0 0 0;");

        // Action Buttons
        Button newTaskBtn = createStyledButton("📦 Custom Task...", "#2563EB");
        newTaskBtn.setOnAction(e -> showTaskConfigDialog());

        Button quickTaskBtn = createStyledButton("⚡ Quick Random Task", "#1D4ED8");
        quickTaskBtn.setOnAction(e -> spawnRandomTask());

        Button deployAgvBtn = createStyledButton("➕ Deploy AGV...", "#059669");
        deployAgvBtn.setOnAction(e -> showAgvConfigDialog());

        Button conflictBtn = createStyledButton("⚔️ Conflict Simulator...", "#D97706");
        conflictBtn.setOnAction(e -> showConflictConfigDialog());

        Button chargeAllBtn = createStyledButton("⚡ Charge Fleet", "#475569");
        chargeAllBtn.setOnAction(e -> recallAllToCharging());

        Button clearCompletedBtn = createStyledButton("🧹 Clear Completed", "#334155");
        clearCompletedBtn.setOnAction(e -> allTasks.removeIf(t -> t.getStatus() == Task.Status.COMPLETED));

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Label legend = new Label("Legend: 🟦 IDLE  🟪 BIDDING  🟩 MOVING  🟧 MUTEX  🟥 CRASH");
        legend.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 10));
        legend.setTextFill(Color.web("#94A3B8"));

        bar.getChildren().addAll(newTaskBtn, quickTaskBtn, deployAgvBtn, conflictBtn, chargeAllBtn, clearCompletedBtn, sp, legend);
        return bar;
    }

    private Button createStyledButton(String text, String hexColor) {
        Button btn = new Button(text);
        btn.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10.5));
        btn.setTextFill(Color.WHITE);
        btn.setStyle("-fx-background-color: " + hexColor + "; -fx-background-radius: 5px; -fx-padding: 5 10 5 10; -fx-cursor: hand;");
        btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: derive(" + hexColor + ", 18%); -fx-background-radius: 5px; -fx-padding: 5 10 5 10; -fx-cursor: hand;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: " + hexColor + "; -fx-background-radius: 5px; -fx-padding: 5 10 5 10; -fx-cursor: hand;"));
        return btn;
    }

    // =========================================================================
    // 5. INITIALIZATION & SIMULATION EXECUTION
    // =========================================================================
    private void initFleet() {
        createAndStartRobot(1L, new GridPoint(2, 2), 100.0, "Pallet AGV-Pro", 1.0);
        createAndStartRobot(2L, new GridPoint(12, 6), 90.0, "Forklift AGV-Heavy", 1.1);
        createAndStartRobot(3L, new GridPoint(22, 10), 85.0, "Tugger AGV-Fast", 0.9);
        logGossip("[SYSTEM] Enterprise P2P Fleet initialized with 3 autonomous agents.");
    }

    private AgvRobot createAndStartRobot(long nodeId, GridPoint initialPos, double battery, String model, double maintenanceWeight) {
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
                .maintenanceWeight(maintenanceWeight)
                .build();

        AgvRobot robot = new AgvRobot(node, map, pathfinder, initialPos);
        robot.setModelName(model);

        // Register wire gossip logger
        node.registerMessageListener((msg, ch) -> logWireMessage(nodeId, msg));

        try {
            node.start();
            robots.add(robot);
            logGossip(String.format("[AGV-%d] %s started at %s on TCP port %d", nodeId, model, initialPos, node.getTcpPort()));
        } catch (Exception e) {
            log.error("Failed to start AGV Node {}", nodeId, e);
        }
        return robot;
    }

    private void logWireMessage(long receiverId, AgvMessage msg) {
        Opcode op = msg.getOpcode();

        // Apply traffic filter
        if ("MUTEX ONLY".equals(gossipFilter) && op != Opcode.MUTEX_REQUEST && op != Opcode.MUTEX_REPLY && op != Opcode.MUTEX_RELEASE) {
            return;
        }
        if ("CNP AUCTION ONLY".equals(gossipFilter) && op != Opcode.TASK_ANNOUNCE && op != Opcode.TASK_BID && op != Opcode.TASK_AWARD) {
            return;
        }
        if ("DISCOVERY & HEARTBEAT".equals(gossipFilter) && op != Opcode.PEER_DISCOVERY && op != Opcode.HEARTBEAT) {
            return;
        }
        if ("ALL".equals(gossipFilter) && op == Opcode.HEARTBEAT) {
            // throttle heartbeat spam
            if (ThreadLocalRandom.current().nextInt(10) > 0) return;
        }

        String details = switch (op) {
            case PEER_DISCOVERY -> "PEER_DISCOVERY Handshake";
            case HEARTBEAT -> "HEARTBEAT Ping";
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
                yield "TASK_AWARD (" + p.taskId() + " -> AGV-" + p.winnerNodeId() + ")";
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
                if (gossipListView.getItems().size() > 300) {
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

                // Periodic telemetry update (every ~150ms)
                long now = System.currentTimeMillis();
                if (now - lastPhysicsTime > 150) {
                    lastPhysicsTime = now;
                    updateDashboardTelemetry();
                }
            }
        };
        renderLoop.start();

        // 2. Physics & Logic step executor (runs at 10 Hz)
        simExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agv-enterprise-physics");
            t.setDaemon(true);
            return t;
        });

        simExecutor.scheduleAtFixedRate(() -> {
            try {
                if (fleetPaused) return;

                // Dynamic obstacles
                Set<GridPoint> occupied = robots.stream()
                        .filter(r -> !r.isCrashed())
                        .map(AgvRobot::getCurrentGridPos)
                        .collect(Collectors.toSet());

                for (AgvRobot robot : robots) {
                    Set<GridPoint> others = new HashSet<>(occupied);
                    others.remove(robot.getCurrentGridPos());
                    robot.updatePhysicsAndLogic(others);
                }

                // Check Continuous Conflict Loop
                if (continuousConflictActive) {
                    checkAndCycleContinuousConflict();
                }
            } catch (Exception e) {
                log.error("Error in simulation physics loop", e);
            }
        }, 200, (long) (100 / simulationSpeedMultiplier), TimeUnit.MILLISECONDS);
    }

    // =========================================================================
    // 6. RENDER ENGINE (CANVAS 2D)
    // =========================================================================
    private void renderCanvas() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

        // Floor
        gc.setFill(Color.web("#0F172A"));
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        // Grid lines
        gc.setStroke(Color.web("#1E293B"));
        gc.setLineWidth(1.0);
        for (int x = 0; x <= map.getWidth(); x++) {
            gc.strokeLine(x * CELL_SIZE, 0, x * CELL_SIZE, map.getHeight() * CELL_SIZE);
        }
        for (int y = 0; y <= map.getHeight(); y++) {
            gc.strokeLine(0, y * CELL_SIZE, map.getWidth() * CELL_SIZE, y * CELL_SIZE);
        }

        // Warehouse Zone Labels
        drawZoneAnnotations(gc);

        // Render Cells
        for (int x = 0; x < map.getWidth(); x++) {
            for (int y = 0; y < map.getHeight(); y++) {
                CellType type = map.getCellType(x, y);
                double px = x * CELL_SIZE;
                double py = y * CELL_SIZE;

                switch (type) {
                    case OBSTACLE -> {
                        // Storage Racks
                        gc.setFill(Color.web("#1E293B"));
                        gc.fillRoundRect(px + 2, py + 2, CELL_SIZE - 4, CELL_SIZE - 4, 4, 4);
                        gc.setStroke(Color.web("#334155"));
                        gc.setLineWidth(1.5);
                        gc.strokeRoundRect(px + 2, py + 2, CELL_SIZE - 4, CELL_SIZE - 4, 4, 4);

                        // Double rack bar pattern
                        gc.setStroke(Color.web("#293548"));
                        gc.setLineWidth(1.0);
                        gc.strokeLine(px + 3, py + CELL_SIZE / 2, px + CELL_SIZE - 3, py + CELL_SIZE / 2);
                    }
                    case CHARGING_STATION -> {
                        // Charging Docks
                        gc.setFill(Color.web("#064E3B", 0.5));
                        gc.fillRect(px + 1, py + 1, CELL_SIZE - 2, CELL_SIZE - 2);
                        gc.setStroke(Color.web("#10B981"));
                        gc.setLineWidth(1.5);
                        gc.strokeRect(px + 1, py + 1, CELL_SIZE - 2, CELL_SIZE - 2);

                        gc.setFill(Color.web("#34D399"));
                        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
                        gc.fillText("⚡", px + 8, py + 18);
                    }
                    case INTERSECTION -> {
                        String cellId = map.getIntersectionCellId(x, y);
                        Optional<AgvRobot> holdingRobot = robots.stream()
                                .filter(r -> cellId.equals(r.getHeldIntersection()))
                                .findFirst();
                        Optional<AgvRobot> waitingRobot = robots.stream()
                                .filter(r -> cellId.equals(r.getWaitingIntersection()))
                                .findFirst();

                        if (holdingRobot.isPresent()) {
                            // Currently HELD by robot: Glowing Warm Orange
                            gc.setFill(Color.web("#EA580C", 0.6));
                            gc.fillRect(px + 1, py + 1, CELL_SIZE - 2, CELL_SIZE - 2);
                            gc.setStroke(Color.web("#F97316"));
                            gc.setLineWidth(2.5);
                            gc.strokeRect(px + 1, py + 1, CELL_SIZE - 2, CELL_SIZE - 2);

                            gc.setFill(Color.WHITE);
                            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8.5));
                            gc.fillText("🔒A" + holdingRobot.get().getNodeId(), px + 2, py + 18);
                        } else if (waitingRobot.isPresent()) {
                            // Requested / Waiting
                            gc.setFill(Color.web("#CA8A04", 0.35));
                            gc.fillRect(px + 1, py + 1, CELL_SIZE - 2, CELL_SIZE - 2);
                            gc.setStroke(Color.web("#EAB308"));
                            gc.setLineWidth(2.0);
                            gc.strokeRect(px + 1, py + 1, CELL_SIZE - 2, CELL_SIZE - 2);

                            gc.setFill(Color.web("#FDE047"));
                            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8.0));
                            gc.fillText("⏳REQ", px + 1, py + 18);
                        } else {
                            // Free Intersection
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

        // Active Tasks
        for (Task task : allTasks) {
            if (task.getStatus() == Task.Status.AUCTIONING || task.getStatus() == Task.Status.ASSIGNED || task.getStatus() == Task.Status.IN_TRANSIT) {
                double px = task.getPickupX() * CELL_SIZE + CELL_SIZE / 2;
                double py = task.getPickupY() * CELL_SIZE + CELL_SIZE / 2;
                double dx = task.getDeliveryX() * CELL_SIZE + CELL_SIZE / 2;
                double dy = task.getDeliveryY() * CELL_SIZE + CELL_SIZE / 2;

                // Pickup Marker
                gc.setFill(Color.web("#10B981", 0.35));
                gc.fillOval(px - 10, py - 10, 20, 20);
                gc.setStroke(Color.web("#34D399"));
                gc.setLineWidth(1.5);
                gc.strokeOval(px - 7, py - 7, 14, 14);
                gc.setFill(Color.WHITE);
                gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8.5));
                gc.fillText("📦 " + task.getTaskId(), px - 15, py - 11);

                // Delivery Marker
                gc.setFill(Color.web("#3B82F6", 0.35));
                gc.fillOval(dx - 10, dy - 10, 20, 20);
                gc.setStroke(Color.web("#60A5FA"));
                gc.setLineWidth(1.5);
                gc.strokeOval(dx - 7, dy - 7, 14, 14);
                gc.setFill(Color.WHITE);
                gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8.5));
                gc.fillText("🏁 " + task.getTaskId(), dx - 15, dy - 11);
            }
        }

        // Draw Waypoints Trails
        for (AgvRobot robot : robots) {
            if (robot.isCrashed()) continue;
            List<GridPoint> path = robot.getRemainingPath();
            if (!path.isEmpty()) {
                Color trailColor = robot == selectedRobot ? Color.web("#F59E0B", 0.8) : Color.web("#38BDF8", 0.35);
                gc.setStroke(trailColor);
                gc.setLineWidth(robot == selectedRobot ? 2.5 : 1.5);

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

        // Draw AGVs
        for (AgvRobot robot : robots) {
            double cx = robot.getRenderX() * CELL_SIZE + CELL_SIZE / 2;
            double cy = robot.getRenderY() * CELL_SIZE + CELL_SIZE / 2;
            double radius = 10.5;

            Color statusColor = switch (robot.getState()) {
                case IDLE -> Color.web("#3B82F6");              // Blue
                case BIDDING -> Color.web("#8B5CF6");           // Purple
                case MOVING, BUSY -> Color.web("#06B6D4");      // Teal
                case WAITING_MUTEX -> Color.web("#F59E0B");     // Amber
                case LOW_BATTERY_CHARGING -> Color.web("#10B981"); // Green
                case CRASHED -> Color.web("#EF4444");           // Crimson
            };

            // Selection ring if selected
            if (robot == selectedRobot) {
                gc.setStroke(Color.web("#F59E0B"));
                gc.setLineWidth(2.0);
                gc.strokeOval(cx - radius - 4, cy - radius - 4, (radius + 4) * 2, (radius + 4) * 2);
            }

            // Glow
            gc.setFill(statusColor.deriveColor(0, 1, 1, 0.25));
            gc.fillOval(cx - radius - 2, cy - radius - 2, (radius + 2) * 2, (radius + 2) * 2);

            // Body
            gc.setFill(Color.web("#0F172A"));
            gc.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
            gc.setStroke(statusColor);
            gc.setLineWidth(2.2);
            gc.strokeOval(cx - radius, cy - radius, radius * 2, radius * 2);

            // Node ID
            gc.setFill(Color.WHITE);
            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8.5));
            gc.fillText("A" + robot.getNodeId(), cx - 5.5, cy + 3.5);

            // Cargo Box
            if (robot.isCarryingCargo()) {
                gc.setFill(Color.web("#F59E0B"));
                gc.fillRect(cx + 5, cy - 6, 6, 6);
                gc.setStroke(Color.WHITE);
                gc.setLineWidth(1.0);
                gc.strokeRect(cx + 5, cy - 6, 6, 6);
            }

            // Battery Bar
            double batWidth = 18.0;
            double batHeight = 3.0;
            double batX = cx - batWidth / 2;
            double batY = cy - radius - 5;

            gc.setFill(Color.web("#334155"));
            gc.fillRect(batX, batY, batWidth, batHeight);

            double level = robot.getBatteryLevel();
            double fillW = batWidth * (level / 100.0);
            Color batColor = level > 50 ? Color.web("#10B981") : (level > 20 ? Color.web("#F59E0B") : Color.web("#EF4444"));
            gc.setFill(batColor);
            gc.fillRect(batX, batY, fillW, batHeight);
        }
    }

    private void drawZoneAnnotations(GraphicsContext gc) {
        gc.setFill(Color.web("#64748B", 0.45));
        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9.0));

        gc.fillText("INBOUND DOCKS", 6, 12);
        gc.fillText("OUTBOUND BAYS", canvas.getWidth() - 90, 12);
        gc.fillText("ZONE A", 4 * CELL_SIZE, 3 * CELL_SIZE - 2);
        gc.fillText("ZONE B", 9 * CELL_SIZE, 3 * CELL_SIZE - 2);
        gc.fillText("ZONE C", 14 * CELL_SIZE, 3 * CELL_SIZE - 2);
        gc.fillText("ZONE D", 19 * CELL_SIZE, 3 * CELL_SIZE - 2);
    }

    // =========================================================================
    // 7. DASHBOARD TELEMETRY UPDATES
    // =========================================================================
    private void updateDashboardTelemetry() {
        Platform.runLater(() -> {
            long onlineCount = robots.stream().filter(r -> !r.isCrashed()).count();
            activeNodesLabel.setText("FLEET SIZE\n" + onlineCount + " Active / " + robots.size() + " Total");

            long inTransit = allTasks.stream().filter(t -> t.getStatus() == Task.Status.ASSIGNED || t.getStatus() == Task.Status.AUCTIONING).count();
            activeTasksLabel.setText("ACTIVE ORDERS\n" + inTransit + " In-Transit");

            long completed = allTasks.stream().filter(t -> t.getStatus() == Task.Status.COMPLETED).count();
            completedTasksLabel.setText("DELIVERED ORDERS\n" + completed + " Completed");

            long activeMutexes = robots.stream().filter(r -> r.getHeldIntersection() != null).count();
            activeMutexLabel.setText("INTERSECTION MUTEX\n" + activeMutexes + " Active Locks");

            long maxLamport = robots.stream().mapToLong(r -> r.getNode().getLamportClock().getTime()).max().orElse(0);
            maxLamportLabel.setText("MAX LAMPORT CLOCK\nL = " + maxLamport);

            double healthPercent = robots.isEmpty() ? 100.0 : (onlineCount * 100.0 / robots.size());
            fleetHealthLabel.setText(String.format("FLEET HEALTH\n%.0f%% Online", healthPercent));

            // Update Telemetry Cards
            telemetryList.getChildren().clear();
            for (AgvRobot robot : robots) {
                telemetryList.getChildren().add(createAgvTelemetryCard(robot));
            }

            // Update Orders List
            tasksListVBox.getChildren().clear();
            if (allTasks.isEmpty()) {
                Label emptyLbl = new Label("No orders dispatched yet. Click 'New Order' below.");
                emptyLbl.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px;");
                tasksListVBox.getChildren().add(emptyLbl);
            } else {
                for (Task task : allTasks) {
                    tasksListVBox.getChildren().add(createTaskCard(task));
                }
            }
        });
    }

    private Node createAgvTelemetryCard(AgvRobot robot) {
        VBox card = new VBox(5);
        card.setPadding(new Insets(8, 10, 8, 10));
        boolean isSelected = robot == selectedRobot;
        card.setStyle("-fx-background-color: " + (isSelected ? "#1E293B" : "#131C2E") + "; -fx-border-color: " + (isSelected ? "#3B82F6" : "#334155") + "; -fx-border-width: 1.5px; -fx-border-radius: 6px; -fx-background-radius: 6px;");

        // Top Row: ID, Model, State Badge
        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);

        Label idLabel = new Label("AGV #" + robot.getNodeId());
        idLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        idLabel.setTextFill(Color.WHITE);

        Label modelLabel = new Label("(" + robot.getModelName() + ")");
        modelLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");

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
        stateBadge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9.5));
        stateBadge.setTextFill(Color.WHITE);
        stateBadge.setPadding(new Insets(2, 6, 2, 6));
        stateBadge.setStyle("-fx-background-color: " + badgeColor + "; -fx-background-radius: 4px;");

        top.getChildren().addAll(idLabel, modelLabel, sp, stateBadge);

        // Details Row
        HBox details = new HBox(12);
        Label posLbl = new Label(String.format("Pos: (%d, %d)", robot.getCurrentGridPos().x(), robot.getCurrentGridPos().y()));
        posLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");

        Label batLbl = new Label(String.format("Bat: %.1f%%", robot.getBatteryLevel()));
        batLbl.setStyle("-fx-text-fill: " + (robot.getBatteryLevel() > 20 ? "#34D399" : "#F87171") + "; -fx-font-size: 10px; -fx-font-weight: bold;");

        Label clockLbl = new Label("L: " + robot.getNode().getLamportClock().getTime());
        clockLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");

        Label tasksDoneLbl = new Label("Done: " + robot.getTotalTasksCompleted());
        tasksDoneLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");

        details.getChildren().addAll(posLbl, batLbl, clockLbl, tasksDoneLbl);

        // Mutex status line if applicable
        if (robot.getWaitingIntersection() != null) {
            Label m = new Label("⏳ Waiting Mutex: " + robot.getWaitingIntersection());
            m.setStyle("-fx-text-fill: #FBBF24; -fx-font-size: 10px; -fx-font-weight: bold;");
            card.getChildren().addAll(top, details, m);
        } else if (robot.getHeldIntersection() != null) {
            Label m = new Label("🔒 Holding Mutex: " + robot.getHeldIntersection());
            m.setStyle("-fx-text-fill: #F97316; -fx-font-size: 10px; -fx-font-weight: bold;");
            card.getChildren().addAll(top, details, m);
        } else {
            card.getChildren().addAll(top, details);
        }

        // Action Buttons Row
        HBox actions = new HBox(6);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new Insets(2, 0, 0, 0));

        Button selectBtn = new Button(isSelected ? "Selected" : "Select");
        selectBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 9.5px; -fx-padding: 2 6 2 6;");
        selectBtn.setOnAction(e -> {
            selectedRobot = robot;
            updateDashboardTelemetry();
        });

        Button chargeBtn = new Button("⚡ Charge");
        chargeBtn.setStyle("-fx-background-color: #065F46; -fx-text-fill: white; -fx-font-size: 9.5px; -fx-padding: 2 6 2 6;");
        chargeBtn.setOnAction(e -> {
            GridPoint station = map.getNearestChargingStation(robot.getCurrentGridPos());
            if (station != null) {
                robot.setDestination(station);
                robot.getNode().setAgentState(AgentState.LOW_BATTERY_CHARGING);
                logGossip("[FLEET] Dispatched AGV #" + robot.getNodeId() + " to charging dock " + station);
            }
        });

        Button crashRebootBtn = new Button(robot.isCrashed() ? "🔄 Reboot" : "💥 Crash");
        crashRebootBtn.setStyle("-fx-background-color: " + (robot.isCrashed() ? "#2563EB" : "#7F1D1D") + "; -fx-text-fill: white; -fx-font-size: 9.5px; -fx-padding: 2 6 2 6;");
        crashRebootBtn.setOnAction(e -> {
            if (robot.isCrashed()) {
                robot.recover();
                logGossip("[RECOVER] AGV #" + robot.getNodeId() + " rebooted.");
            } else {
                robot.crash();
                logGossip("[CRASH] AGV #" + robot.getNodeId() + " crashed.");
            }
        });

        actions.getChildren().addAll(selectBtn, chargeBtn, crashRebootBtn);
        card.getChildren().add(actions);
        return card;
    }

    private Node createTaskCard(Task task) {
        VBox card = new VBox(4);
        card.setPadding(new Insets(6, 10, 6, 10));
        card.setStyle("-fx-background-color: #131C2E; -fx-border-color: #334155; -fx-border-radius: 6px; -fx-background-radius: 6px;");

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);

        Label idLbl = new Label(task.getTaskId());
        idLbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        idLbl.setTextFill(Color.WHITE);

        String pColor = switch (task.getPriority()) {
            case URGENT -> "#EF4444";
            case HIGH -> "#F59E0B";
            case NORMAL -> "#3B82F6";
            case LOW -> "#64748B";
        };
        Label pBadge = new Label(task.getPriority().name());
        pBadge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9));
        pBadge.setTextFill(Color.WHITE);
        pBadge.setPadding(new Insets(1, 4, 1, 4));
        pBadge.setStyle("-fx-background-color: " + pColor + "; -fx-background-radius: 3px;");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        String statusColor = switch (task.getStatus()) {
            case COMPLETED -> "#10B981";
            case ASSIGNED, IN_TRANSIT -> "#06B6D4";
            case AUCTIONING -> "#8B5CF6";
            case UNASSIGNED -> "#64748B";
            case CANCELLED -> "#EF4444";
        };
        Label stBadge = new Label(task.getStatus().name());
        stBadge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9));
        stBadge.setTextFill(Color.WHITE);
        stBadge.setPadding(new Insets(1, 5, 1, 5));
        stBadge.setStyle("-fx-background-color: " + statusColor + "; -fx-background-radius: 3px;");

        top.getChildren().addAll(idLbl, pBadge, sp, stBadge);

        HBox details = new HBox(12);
        Label cargoLbl = new Label("Cargo: " + task.getCargoType());
        cargoLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");

        Label routeLbl = new Label(String.format("Route: (%d,%d) -> (%d,%d)",
                (int) task.getPickupX(), (int) task.getPickupY(),
                (int) task.getDeliveryX(), (int) task.getDeliveryY()));
        routeLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");

        String assignedTo = task.getAssignedNodeId() != null ? "AGV #" + task.getAssignedNodeId() : "Unassigned";
        Label agvLbl = new Label("Carrier: " + assignedTo);
        agvLbl.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 10px; -fx-font-weight: bold;");

        details.getChildren().addAll(cargoLbl, routeLbl, agvLbl);
        card.getChildren().addAll(top, details);
        return card;
    }

    // =========================================================================
    // 8. ENTERPRISE TASK & AGV CONFIGURATION DIALOGS
    // =========================================================================
    private void showTaskConfigDialog() {
        Dialog<Task> dialog = new Dialog<>();
        dialog.initOwner(primaryStage);
        dialog.setTitle("Create Custom Transport Task");
        dialog.setHeaderText("Enterprise Order Dispatcher & CNP Auction Configuration");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(20, 20, 10, 10));

        TextField taskIdField = new TextField("ORD-" + taskCounter.getAndIncrement());
        ComboBox<Task.Priority> priorityCombo = new ComboBox<>();
        priorityCombo.getItems().addAll(Task.Priority.values());
        priorityCombo.setValue(Task.Priority.NORMAL);

        ComboBox<String> cargoCombo = new ComboBox<>();
        cargoCombo.getItems().addAll(
                "Standard Euro Pallet (500kg)",
                "High-Value Electronics",
                "Automotive Assemblies",
                "Cold-Chain Perishables",
                "Heavy Raw Material Pallet"
        );
        cargoCombo.setValue("Standard Euro Pallet (500kg)");

        // Pickup Location
        Map<String, GridPoint> zones = map.getNamedZones();
        ComboBox<String> pickupZoneCombo = new ComboBox<>();
        pickupZoneCombo.getItems().addAll(zones.keySet());
        pickupZoneCombo.setValue("Inbound Receiving Dock 1");

        Spinner<Integer> pickupXSpinner = new Spinner<>(0, map.getWidth() - 1, 0);
        Spinner<Integer> pickupYSpinner = new Spinner<>(0, map.getHeight() - 1, 3);
        pickupXSpinner.setDisable(true);
        pickupYSpinner.setDisable(true);

        RadioButton pickupPresetRadio = new RadioButton("Preset Zone");
        RadioButton pickupCustomRadio = new RadioButton("Custom (X, Y)");
        ToggleGroup pickupGroup = new ToggleGroup();
        pickupPresetRadio.setToggleGroup(pickupGroup);
        pickupCustomRadio.setToggleGroup(pickupGroup);
        pickupPresetRadio.setSelected(true);

        pickupPresetRadio.setOnAction(e -> {
            pickupZoneCombo.setDisable(false);
            pickupXSpinner.setDisable(true);
            pickupYSpinner.setDisable(true);
        });
        pickupCustomRadio.setOnAction(e -> {
            pickupZoneCombo.setDisable(true);
            pickupXSpinner.setDisable(false);
            pickupYSpinner.setDisable(false);
        });

        // Delivery Location
        ComboBox<String> deliveryZoneCombo = new ComboBox<>();
        deliveryZoneCombo.getItems().addAll(zones.keySet());
        deliveryZoneCombo.setValue("Outbound Shipping Bay 1");

        Spinner<Integer> deliveryXSpinner = new Spinner<>(0, map.getWidth() - 1, 23);
        Spinner<Integer> deliveryYSpinner = new Spinner<>(0, map.getHeight() - 1, 4);
        deliveryXSpinner.setDisable(true);
        deliveryYSpinner.setDisable(true);

        RadioButton deliveryPresetRadio = new RadioButton("Preset Zone");
        RadioButton deliveryCustomRadio = new RadioButton("Custom (X, Y)");
        ToggleGroup deliveryGroup = new ToggleGroup();
        deliveryPresetRadio.setToggleGroup(deliveryGroup);
        deliveryCustomRadio.setToggleGroup(deliveryGroup);
        deliveryPresetRadio.setSelected(true);

        deliveryPresetRadio.setOnAction(e -> {
            deliveryZoneCombo.setDisable(false);
            deliveryXSpinner.setDisable(true);
            deliveryYSpinner.setDisable(true);
        });
        deliveryCustomRadio.setOnAction(e -> {
            deliveryZoneCombo.setDisable(true);
            deliveryXSpinner.setDisable(false);
            deliveryYSpinner.setDisable(false);
        });

        // Dispatch Mode
        RadioButton dispatchAuction = new RadioButton("Autonomous P2P CNP Auction (Min-Cost Bid Wins)");
        RadioButton dispatchDirect = new RadioButton("Direct Assignment to AGV:");
        ToggleGroup dispatchGroup = new ToggleGroup();
        dispatchAuction.setToggleGroup(dispatchGroup);
        dispatchDirect.setToggleGroup(dispatchGroup);
        dispatchAuction.setSelected(true);

        ComboBox<Long> directAgvCombo = new ComboBox<>();
        for (AgvRobot r : robots) directAgvCombo.getItems().add(r.getNodeId());
        if (!directAgvCombo.getItems().isEmpty()) directAgvCombo.setValue(directAgvCombo.getItems().get(0));
        directAgvCombo.setDisable(true);

        dispatchAuction.setOnAction(e -> directAgvCombo.setDisable(true));
        dispatchDirect.setOnAction(e -> directAgvCombo.setDisable(false));

        // Layout
        grid.add(new Label("Order / Task ID:"), 0, 0);
        grid.add(taskIdField, 1, 0);
        grid.add(new Label("Priority:"), 0, 1);
        grid.add(priorityCombo, 1, 1);
        grid.add(new Label("Cargo Type:"), 0, 2);
        grid.add(cargoCombo, 1, 2);

        grid.add(new Separator(), 0, 3, 2, 1);

        grid.add(new Label("Pickup Location:"), 0, 4);
        HBox pickupTypeBox = new HBox(10, pickupPresetRadio, pickupCustomRadio);
        grid.add(pickupTypeBox, 1, 4);
        HBox pickupCoordBox = new HBox(6, new Label("X:"), pickupXSpinner, new Label("Y:"), pickupYSpinner);
        grid.add(pickupZoneCombo, 1, 5);
        grid.add(pickupCoordBox, 1, 6);

        grid.add(new Separator(), 0, 7, 2, 1);

        grid.add(new Label("Delivery Location:"), 0, 8);
        HBox deliveryTypeBox = new HBox(10, deliveryPresetRadio, deliveryCustomRadio);
        grid.add(deliveryTypeBox, 1, 8);
        HBox deliveryCoordBox = new HBox(6, new Label("X:"), deliveryXSpinner, new Label("Y:"), deliveryYSpinner);
        grid.add(deliveryZoneCombo, 1, 9);
        grid.add(deliveryCoordBox, 1, 10);

        grid.add(new Separator(), 0, 11, 2, 1);

        grid.add(new Label("Allocation Mode:"), 0, 12);
        VBox allocBox = new VBox(6, dispatchAuction, new HBox(10, dispatchDirect, directAgvCombo));
        grid.add(allocBox, 1, 12);

        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.setMaxHeight(450);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");
        dialog.getDialogPane().setContent(scrollPane);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                GridPoint pPoint = pickupPresetRadio.isSelected() ? zones.get(pickupZoneCombo.getValue()) : new GridPoint(pickupXSpinner.getValue(), pickupYSpinner.getValue());
                GridPoint dPoint = deliveryPresetRadio.isSelected() ? zones.get(deliveryZoneCombo.getValue()) : new GridPoint(deliveryXSpinner.getValue(), deliveryYSpinner.getValue());

                Task task = new Task(taskIdField.getText(), pPoint.x(), pPoint.y(), dPoint.x(), dPoint.y(), priorityCombo.getValue(), cargoCombo.getValue());

                allTasks.add(task);

                if (dispatchAuction.isSelected()) {
                    // Find an active node to announce auction
                    Optional<AgvRobot> manager = robots.stream().filter(r -> !r.isCrashed()).findFirst();
                    if (manager.isPresent()) {
                        logGossip(String.format("[AUCTION] Announcing %s via AGV-%d (Pickup: %s -> Delivery: %s)",
                                task.getTaskId(), manager.get().getNodeId(), pPoint, dPoint));
                        manager.get().getNode().announceTask(task);
                    }
                } else {
                    // Direct dispatch
                    Long targetId = directAgvCombo.getValue();
                    Optional<AgvRobot> targetRobot = robots.stream().filter(r -> r.getNodeId() == targetId).findFirst();
                    if (targetRobot.isPresent()) {
                        task.setStatus(Task.Status.ASSIGNED);
                        task.setAssignedNodeId(targetId);
                        targetRobot.get().setDestination(pPoint);
                        targetRobot.get().getNode().setAgentState(AgentState.MOVING);
                        logGossip(String.format("[DISPATCH] Direct dispatched %s to AGV-%d", task.getTaskId(), targetId));
                    }
                }
                return task;
            }
            return null;
        });

        dialog.showAndWait();
    }

    private void showAgvConfigDialog() {
        Dialog<AgvRobot> dialog = new Dialog<>();
        dialog.initOwner(primaryStage);
        dialog.setTitle("Deploy New AGV Node to P2P Mesh");
        dialog.setHeaderText("Configure Autonomous Mobile Robot Parameters");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(20, 20, 10, 10));

        TextField idField = new TextField(String.valueOf(nextNodeId.getAndIncrement()));
        ComboBox<String> modelCombo = new ComboBox<>();
        modelCombo.getItems().addAll("Pallet AGV-Pro (1000kg)", "Heavy Forklift AGV (2000kg)", "Fast Light Tugger (300kg)");
        modelCombo.setValue("Pallet AGV-Pro (1000kg)");

        Slider batterySlider = new Slider(10, 100, 100);
        batterySlider.setShowTickLabels(true);
        batterySlider.setShowTickMarks(true);
        batterySlider.setMajorTickUnit(20);
        Label batValLbl = new Label("100% (Fully Charged)");
        batterySlider.valueProperty().addListener((obs, oldV, newV) -> {
            int val = newV.intValue();
            batValLbl.setText(val <= 15 ? val + "% (Critical - Tests Charging)" : val + "%");
        });

        Slider maintenanceSlider = new Slider(0.5, 2.0, 1.0);
        maintenanceSlider.setShowTickLabels(true);
        maintenanceSlider.setShowTickMarks(true);
        maintenanceSlider.setMajorTickUnit(0.5);

        // Spawn Location
        List<GridPoint> chargingDocks = map.getChargingStations();
        ComboBox<GridPoint> dockCombo = new ComboBox<>();
        dockCombo.getItems().addAll(chargingDocks);
        if (!chargingDocks.isEmpty()) dockCombo.setValue(chargingDocks.get(0));

        grid.add(new Label("AGV Node ID:"), 0, 0);
        grid.add(idField, 1, 0);
        grid.add(new Label("Robot Model:"), 0, 1);
        grid.add(modelCombo, 1, 1);
        grid.add(new Label("Initial Battery:"), 0, 2);
        grid.add(new VBox(4, batterySlider, batValLbl), 1, 2);
        grid.add(new Label("Maintenance Factor:"), 0, 3);
        grid.add(maintenanceSlider, 1, 3);
        grid.add(new Label("Spawn Dock:"), 0, 4);
        grid.add(dockCombo, 1, 4);

        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.setMaxHeight(450);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");
        dialog.getDialogPane().setContent(scrollPane);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                long nodeId = Long.parseLong(idField.getText().trim());
                double battery = batterySlider.getValue();
                GridPoint spawnPos = dockCombo.getValue() != null ? dockCombo.getValue() : new GridPoint(0, 1);
                double mWeight = maintenanceSlider.getValue();
                String model = modelCombo.getValue();

                AgvRobot robot = createAndStartRobot(nodeId, spawnPos, battery, model, mWeight);
                logGossip(String.format("[DEPLOY] Deployed AGV #%d (%s) at %s with %.0f%% battery.",
                        nodeId, model, spawnPos, battery));
                return robot;
            }
            return null;
        });

        dialog.showAndWait();
    }

    // =========================================================================
    // 9. REPEATABLE & CONTINUOUS CONFLICT SIMULATOR
    // =========================================================================
    private void showConflictConfigDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(primaryStage);
        dialog.setTitle("Intersection Conflict & Deadlock Simulator");
        dialog.setHeaderText("Test Ricart-Agrawala Mutual Exclusion & Lamport Logical Clocks");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(20, 20, 10, 10));

        ComboBox<String> conflictTypeCombo = new ComboBox<>();
        conflictTypeCombo.getItems().addAll(
                "Perpendicular Crossing (East-West vs North-South)",
                "Head-On Convergence & Turning (Opposite Directions)",
                "3-Way Multi-Vehicle Convergence"
        );
        conflictTypeCombo.setValue("Perpendicular Crossing (East-West vs North-South)");

        ComboBox<String> intersectionCombo = new ComboBox<>();
        for (GridPoint ip : map.getIntersections()) {
            intersectionCombo.getItems().add(String.format("Intersection (%d, %d)", ip.x(), ip.y()));
        }
        intersectionCombo.setValue("Intersection (7, 6)");

        RadioButton singleRunRadio = new RadioButton("Single Conflict Run (Auto-reposition & run once)");
        RadioButton continuousRadio = new RadioButton("Continuous Stress Mode (Looping back-and-forth indefinitely)");
        ToggleGroup modeGroup = new ToggleGroup();
        singleRunRadio.setToggleGroup(modeGroup);
        continuousRadio.setToggleGroup(modeGroup);
        singleRunRadio.setSelected(true);

        grid.add(new Label("Conflict Scenario:"), 0, 0);
        grid.add(conflictTypeCombo, 1, 0);
        grid.add(new Label("Test Intersection:"), 0, 1);
        grid.add(intersectionCombo, 1, 1);
        grid.add(new Label("Execution Mode:"), 0, 2);
        grid.add(new VBox(8, singleRunRadio, continuousRadio), 1, 2);

        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.setMaxHeight(450);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");
        dialog.getDialogPane().setContent(scrollPane);
        dialog.getDialogPane().getButtonTypes().addAll(new ButtonType("Launch Conflict", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);

        dialog.setResultConverter(btn -> {
            if (btn.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                String selInter = intersectionCombo.getValue();
                int ix = Integer.parseInt(selInter.substring(selInter.indexOf('(') + 1, selInter.indexOf(',')).trim());
                int iy = Integer.parseInt(selInter.substring(selInter.indexOf(',') + 1, selInter.indexOf(')')).trim());
                GridPoint targetIntersection = new GridPoint(ix, iy);

                boolean isContinuous = continuousRadio.isSelected();
                String scenario = conflictTypeCombo.getValue();

                launchConflictSimulation(targetIntersection, scenario, isContinuous);
            }
            return null;
        });

        dialog.showAndWait();
    }

    private void launchConflictSimulation(GridPoint intersection, String scenario, boolean continuous) {
        if (robots.size() < 2) {
            logGossip("[WARN] Need at least 2 active AGVs for conflict test! Deploy more AGVs.");
            return;
        }

        // Stop any previous conflict mode
        stopConflictSimulation();

        activeConflictIntersection = intersection;
        continuousConflictActive = continuous;
        conflictModeName = scenario;
        conflictCycleCount = 1;

        conflictParticipants.clear();
        AgvRobot r1 = robots.get(0);
        AgvRobot r2 = robots.get(1);
        conflictParticipants.add(r1);
        conflictParticipants.add(r2);

        if (scenario.contains("3-Way") && robots.size() >= 3) {
            conflictParticipants.add(robots.get(2));
        }

        executeConflictSetupAndRun();

        Platform.runLater(() -> {
            conflictStatusLabel.setText(String.format("Conflict Demo: ACTIVE [%s] (Cycle #%d)", continuous ? "LOOP" : "SINGLE", conflictCycleCount));
            conflictStatusLabel.setStyle("-fx-background-color: #D97706; -fx-text-fill: white; -fx-padding: 6 12 6 12; -fx-background-radius: 6px; -fx-font-size: 11px; -fx-font-weight: bold;");
            stopConflictBtn.setVisible(true);
        });
    }

    private void executeConflictSetupAndRun() {
        int ix = activeConflictIntersection.x();
        int iy = activeConflictIntersection.y();

        AgvRobot r1 = conflictParticipants.get(0);
        AgvRobot r2 = conflictParticipants.get(1);

        logGossip(String.format("⚔️ [CONFLICT RUN #%d] Launching Ricart-Agrawala test at Intersection (%d, %d)",
                conflictCycleCount, ix, iy));

        if (conflictModeName.contains("Perpendicular")) {
            // Vehicle 1 approaches from West (ix - 4, iy) heading East to (ix + 4, iy)
            GridPoint start1 = new GridPoint(Math.max(0, ix - 4), iy);
            GridPoint dest1 = new GridPoint(Math.min(map.getWidth() - 1, ix + 4), iy);

            // Vehicle 2 approaches from North (ix, iy - 4) heading South to (ix, iy + 4)
            GridPoint start2 = new GridPoint(ix, Math.max(0, iy - 4));
            GridPoint dest2 = new GridPoint(ix, Math.min(map.getHeight() - 1, iy + 4));

            r1.teleportTo(start1);
            r2.teleportTo(start2);

            r1.setDestination(dest1);
            r2.setDestination(dest2);
            r1.getNode().setAgentState(AgentState.MOVING);
            r2.getNode().setAgentState(AgentState.MOVING);

            logGossip(String.format("AGV-%d at %s -> %s (Eastbound) vs AGV-%d at %s -> %s (Southbound)",
                    r1.getNodeId(), start1, dest1, r2.getNodeId(), start2, dest2));
        } else {
            // Head-On Convergence & Turn cleanly
            GridPoint start1 = new GridPoint(Math.max(0, ix - 4), iy);
            GridPoint dest1 = new GridPoint(ix, Math.min(map.getHeight() - 1, iy + 4)); // Turns South

            GridPoint start2 = new GridPoint(Math.min(map.getWidth() - 1, ix + 4), iy);
            GridPoint dest2 = new GridPoint(ix, Math.max(0, iy - 4)); // Turns North

            r1.teleportTo(start1);
            r2.teleportTo(start2);

            r1.setDestination(dest1);
            r2.setDestination(dest2);
            r1.getNode().setAgentState(AgentState.MOVING);
            r2.getNode().setAgentState(AgentState.MOVING);

            logGossip(String.format("AGV-%d at %s -> Turn South vs AGV-%d at %s -> Turn North across (%d, %d)",
                    r1.getNodeId(), start1, r2.getNodeId(), start2, ix, iy));
        }
    }

    private void checkAndCycleContinuousConflict() {
        if (!continuousConflictActive || conflictParticipants.size() < 2) return;

        boolean allReached = true;
        for (AgvRobot r : conflictParticipants) {
            if (!r.getRemainingPath().isEmpty() || r.getState() == AgentState.WAITING_MUTEX) {
                allReached = false;
                break;
            }
        }

        if (allReached) {
            conflictCycleCount++;
            logGossip(String.format("🔄 [CONFLICT CYCLE] Both AGVs cleared intersection. Starting Cycle #%d...", conflictCycleCount));
            Platform.runLater(() -> {
                conflictStatusLabel.setText(String.format("Conflict Demo: LOOPING (Cycle #%d)", conflictCycleCount));
            });
            executeConflictSetupAndRun();
        }
    }

    private void stopConflictSimulation() {
        continuousConflictActive = false;
        activeConflictIntersection = null;
        conflictParticipants.clear();
        conflictCycleCount = 0;

        Platform.runLater(() -> {
            conflictStatusLabel.setText("Conflict Demo: Inactive");
            conflictStatusLabel.setStyle("-fx-background-color: #1E293B; -fx-text-fill: #94A3B8; -fx-padding: 6 12 6 12; -fx-background-radius: 6px; -fx-border-color: #334155; -fx-border-radius: 6px; -fx-font-size: 11px; -fx-font-weight: bold;");
            stopConflictBtn.setVisible(false);
        });
        logGossip("[CONFLICT] Intersection Conflict Simulator stopped.");
    }

    // =========================================================================
    // 10. QUICK ACTIONS
    // =========================================================================
    private void spawnRandomTask() {
        List<GridPoint> emptyPoints = map.getWalkableEmptyPoints();
        if (emptyPoints.size() < 2) return;

        Collections.shuffle(emptyPoints);
        GridPoint p = emptyPoints.get(0);
        GridPoint d = emptyPoints.get(1);

        String taskId = "ORD-" + taskCounter.getAndIncrement();
        Task task = new Task(taskId, p.x(), p.y(), d.x(), d.y());
        allTasks.add(task);

        Optional<AgvRobot> manager = robots.stream().filter(r -> !r.isCrashed()).findFirst();
        if (manager.isPresent()) {
            logGossip(String.format("[TASK] Quick spawned %s (Pickup: %s -> Delivery: %s). Announcing via AGV-%d...",
                    taskId, p, d, manager.get().getNodeId()));
            manager.get().getNode().announceTask(task);
        }
    }

    private void recallAllToCharging() {
        logGossip("[FLEET] Operator command: Recalling all available AGVs to charging docks.");
        List<GridPoint> docks = map.getChargingStations();
        int dockIdx = 0;
        for (AgvRobot r : robots) {
            if (!r.isCrashed() && r.getState() == AgentState.IDLE) {
                GridPoint dock = docks.get(dockIdx % docks.size());
                r.setDestination(dock);
                r.getNode().setAgentState(AgentState.LOW_BATTERY_CHARGING);
                dockIdx++;
            }
        }
    }

    private void shutdown() {
        log.info("Shutting down AGV Fleet Simulator...");
        stopConflictSimulation();
        if (renderLoop != null) renderLoop.stop();
        if (simExecutor != null) simExecutor.shutdownNow();
        for (AgvRobot robot : robots) {
            try {
                robot.getNode().stop();
            } catch (Exception ignored) {
            }
        }
        log.info("AGV Fleet Simulator shutdown cleanly.");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
