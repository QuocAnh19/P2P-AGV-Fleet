package com.agv.fleet.ui;

import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import com.agv.fleet.common.protocol.payload.*;
import com.agv.fleet.core.AgvNode;
import com.agv.fleet.core.agent.AgentState;
import com.agv.fleet.core.agent.AgvRobot;
import com.agv.fleet.core.agent.Position2D;
import com.agv.fleet.core.agent.Task;
import com.agv.fleet.core.map.CellType;
import com.agv.fleet.core.map.GridMap;
import com.agv.fleet.core.map.GridPoint;
import com.agv.fleet.core.navigation.DynamicAStarPathfinder;
import com.agv.fleet.distributed.mutex.MutexState;
import com.agv.fleet.network.mesh.PeerSession;
import com.agv.fleet.ui.metrics.FleetMetricsCollector;
import com.agv.fleet.ui.panel.MetricsPanel;
import com.agv.fleet.ui.panel.PeersPanel;
import com.agv.fleet.ui.panel.PerNodePanel;
import com.agv.fleet.ui.panel.ScenarioBannerOverlay;
import com.agv.fleet.ui.panel.TimelinePanel;
import com.agv.fleet.ui.panel.WireTrafficPanel;
import com.agv.fleet.ui.scenario.UiScenarioController;
import com.agv.fleet.ui.scenario.UiScenarioResult;
import com.agv.fleet.ui.scenario.UiScenarioType;
import com.agv.fleet.ui.simulation.PhysicsLoopController;
import com.agv.fleet.ui.traffic.WireMessageEntry;
import com.agv.fleet.ui.traffic.WireTrafficLog;
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
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
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
 *   <li>Comprehensive Scenario Runner (S1, S2, S3) with Pass/Fail Verification</li>
 *   <li>Live Ground-Truth Metrics, Wire Traffic Logging, and Presentation Mode</li>
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
    private final AtomicInteger nextNodeId = new AtomicInteger(6);
    private final Random seededRandom = new Random(42);
    private final java.util.concurrent.atomic.AtomicBoolean autoDemoActive = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final ScheduledExecutorService backgroundScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "simulator-auto-demo");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> autoDemoFuture = null;
    private ToggleButton autoDemoBtn;

    // Metrics, Traffic, and Scenarios Logic
    private final FleetMetricsCollector metricsCollector = new FleetMetricsCollector();
    private final WireTrafficLog trafficLog = new WireTrafficLog();
    private final UiScenarioController scenarioController = new UiScenarioController(metricsCollector);

    // UI Panels
    private MetricsPanel metricsPanel;
    private WireTrafficPanel wireTrafficPanel;
    private ScenarioBannerOverlay scenarioBannerOverlay;
    private PeersPanel peersPanel;
    private PerNodePanel perNodePanel;
    private TimelinePanel timelinePanel;
    private final Map<Long, GridPoint> lastReportedGridPos = new ConcurrentHashMap<>();

    // Simulation Speed & Physics
    private volatile double simulationSpeedMultiplier = 1.0;
    private volatile boolean fleetPaused = false;
    private PhysicsLoopController physicsController;
    private AnimationTimer renderLoop;
    private Timeline uiRefreshTimeline;

    // Visual Auction & Mutex Animation State
    private volatile Long cnpVisualAnnouncerId = null;
    private final Map<Long, Double> cnpVisualBids = new ConcurrentHashMap<>();
    private volatile Long cnpVisualWinnerId = null;
    private volatile long cnpVisualTimestamp = 0;
    private final Map<String, Long> mutexRequestTimestamps = new ConcurrentHashMap<>();

    // Selection & Inspection
    private volatile AgvRobot selectedRobot = null;
    private volatile GridPoint hoveredCell = null;

    // Presentation Mode & Main Layout
    private Stage primaryStage;
    private BorderPane rootPane;
    private Node rightSidebarNode;
    private StackPane canvasWrapper;
    private Canvas canvas;
    private VBox telemetryList;
    private VBox tasksListVBox;
    private Label activeNodesLabel;
    private Label activeTasksLabel;
    private Label completedTasksLabel;
    private Label activeMutexLabel;
    private Label maxLamportLabel;
    private Label fleetHealthLabel;
    private Label mapStatusBar;
    private Button pauseFleetBtn;
    private Button presentationModeBtn;
    private Slider speedSlider;
    private Label speedValLabel;
    private boolean presentationModeActive = false;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        primaryStage.setTitle("P2P AGV Fleet Management System (FMS) - Enterprise Digital Twin");

        rootPane = new BorderPane();
        rootPane.setStyle("-fx-background-color: #0B0F19;");

        // Initialize decoupled panels
        metricsPanel = new MetricsPanel();
        wireTrafficPanel = new WireTrafficPanel(trafficLog);
        scenarioBannerOverlay = new ScenarioBannerOverlay();
        peersPanel = new PeersPanel(() -> robots);
        perNodePanel = new PerNodePanel(() -> robots);
        timelinePanel = new TimelinePanel(() -> robots);

        // 1. Top Enterprise Control Bar
        rootPane.setTop(createTopKpiBar());

        // 2. Center: Interactive Map & Canvas with Banner Overlay
        rootPane.setCenter(createCenterMapArea());

        // 3. Right: Tabbed Operations Panel
        rightSidebarNode = createRightTabbedSidebar();
        rootPane.setRight(rightSidebarNode);

        // 4. Bottom: Enterprise Actions Bar
        rootPane.setBottom(createEnterpriseControlBar());

        Rectangle2D visualBounds = Screen.getPrimary().getVisualBounds();
        double prefWidth = Math.min(1280, visualBounds.getWidth() * 0.96);
        double prefHeight = Math.min(680, visualBounds.getHeight() * 0.88);

        Scene scene = new Scene(rootPane, prefWidth, prefHeight);

        // Register Keyboard Shortcuts for Presentation Mode
        setupKeyboardShortcuts(scene);

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
    // 1. TOP CONTROL BAR (SPEED SLIDER, SCENARIOS MENU, PAUSE, PRESENTATION)
    // =========================================================================
    private Node createTopKpiBar() {
        VBox topContainer = new VBox(0);
        topContainer.setStyle("-fx-background-color: #111827; -fx-border-color: #1F2937; -fx-border-width: 0 0 1 0;");

        // Title Row with Scenarios Menu & Controls
        HBox titleRow = new HBox(12);
        titleRow.setPadding(new Insets(6, 14, 4, 14));
        titleRow.setAlignment(Pos.CENTER_LEFT);

        VBox titleBox = new VBox(1);
        Text title = new Text("ENTERPRISE P2P AGV FLEET DISPATCHER");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        title.setFill(Color.WHITE);

        Text subTitle = new Text("Decentralized Multi-Agent Bus • Pure P2P • Ricart-Agrawala Mutex • CNP Adaptive Auction");
        subTitle.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 10));
        subTitle.setFill(Color.web("#94A3B8"));
        titleBox.getChildren().addAll(title, subTitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Menu "Scenarios" with 3 Items
        MenuButton scenariosMenu = new MenuButton("🎬 Kịch bản (Scenarios)");
        scenariosMenu.setStyle("-fx-background-color: #2563EB; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px; -fx-cursor: hand; -fx-background-radius: 5px;");
        Tooltip.install(scenariosMenu, new Tooltip("Chọn và chạy các kịch bản kiểm thử thuật toán phân tán chuyên sâu"));

        MenuItem s1Item = new MenuItem("S1: Đối đầu ngã tư (2/4/8 xe) [Phím 1]");
        s1Item.setOnAction(e -> runScenarioS1(2));

        MenuItem s2Item = new MenuItem("S2: Đấu thầu thích ứng mức pin [Phím 2]");
        s2Item.setOnAction(e -> runScenarioS2());

        MenuItem s3Item = new MenuItem("S3: Mô phỏng lỗi & sập node [Phím 3]");
        s3Item.setOnAction(e -> showS3ChoiceDialog());

        scenariosMenu.getItems().addAll(s1Item, s2Item, s3Item);

        // Simulation Speed Slider (0.25x - 4.0x)
        HBox speedControls = new HBox(6);
        speedControls.setAlignment(Pos.CENTER);

        Label speedLabel = new Label("Tốc độ:");
        speedLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");

        speedSlider = new Slider(0.25, 4.0, 1.0);
        speedSlider.setPrefWidth(90);
        speedSlider.setStyle("-fx-cursor: hand;");
        Tooltip.install(speedSlider, new Tooltip("Điều chỉnh tốc độ mô phỏng từ 0.25x đến 4.0x"));

        speedValLabel = new Label("1.0x");
        speedValLabel.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 11px; -fx-font-weight: bold; -fx-min-width: 32px;");

        speedSlider.valueProperty().addListener((obs, oldV, newV) -> {
            if (physicsController != null) {
                physicsController.updateSpeed(newV.doubleValue());
                simulationSpeedMultiplier = physicsController.getCurrentDiscreteSpeed();
                speedValLabel.setText(String.format(Locale.US, "%.2fx", simulationSpeedMultiplier));
            }
        });

        speedControls.getChildren().addAll(speedLabel, speedSlider, speedValLabel);

        // Pause Fleet Button
        pauseFleetBtn = new Button("⏸ Tạm dừng");
        pauseFleetBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 4 10 4 10; -fx-cursor: hand; -fx-background-radius: 4px;");
        Tooltip.install(pauseFleetBtn, new Tooltip("Tạm dừng hoặc tiếp tục toàn bộ đội xe AGV [Phím Space]"));
        pauseFleetBtn.setOnAction(e -> toggleFleetPause());

        // Auto Demo Button
        autoDemoBtn = new ToggleButton("▶ Bắt đầu demo (Auto)");
        autoDemoBtn.setStyle("-fx-background-color: #059669; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 4 10 4 10; -fx-cursor: hand; -fx-background-radius: 4px;");
        Tooltip.install(autoDemoBtn, new Tooltip("Tự động sinh nhiệm vụ mỗi 3-5s với người phát thầu ngẫu nhiên trong các xe sống"));
        autoDemoBtn.setOnAction(e -> toggleAutoDemo());

        // Presentation Mode Button
        presentationModeBtn = new Button("🖥 Trình chiếu");
        presentationModeBtn.setStyle("-fx-background-color: #475569; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 4 10 4 10; -fx-cursor: hand; -fx-background-radius: 4px;");
        Tooltip.install(presentationModeBtn, new Tooltip("Bật/tắt chế độ thuyết trình (phóng to canvas, ẩn sidebar) [Phím P]"));
        presentationModeBtn.setOnAction(e -> togglePresentationMode());

        // Export Report Button
        Button exportReportBtn = new Button("📑 Xuất báo cáo");
        exportReportBtn.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 4 10 4 10; -fx-cursor: hand; -fx-background-radius: 4px;");
        Tooltip.install(exportReportBtn, new Tooltip("Xuất dữ liệu hiệu năng Metrics và Wire Traffic Log ra file CSV"));
        exportReportBtn.setOnAction(e -> exportFullReport());

        titleRow.getChildren().addAll(titleBox, spacer, scenariosMenu, speedControls, pauseFleetBtn, autoDemoBtn, presentationModeBtn, exportReportBtn);

        // KPI Summary Badges Row
        HBox kpiRow = new HBox(8);
        kpiRow.setPadding(new Insets(3, 14, 5, 14));
        kpiRow.setAlignment(Pos.CENTER_LEFT);

        activeNodesLabel = createKpiCard("FLEET SIZE", "3 Active / 3 Total", "#3B82F6");
        activeTasksLabel = createKpiCard("ACTIVE ORDERS", "0 In-Transit", "#06B6D4");
        completedTasksLabel = createKpiCard("DELIVERED ORDERS", "0 Completed", "#10B981");
        activeMutexLabel = createKpiCard("INTERSECTION MUTEX", "0 Contested", "#F59E0B");
        maxLamportLabel = createKpiCard("MAX LAMPORT CLOCK", "L = 0", "#8B5CF6");
        fleetHealthLabel = createKpiCard("FLEET HEALTH", "100% Online", "#10B981");

        Region kpiSpacer = new Region();
        HBox.setHgrow(kpiSpacer, Priority.ALWAYS);

        kpiRow.getChildren().addAll(activeNodesLabel, activeTasksLabel, completedTasksLabel, activeMutexLabel, maxLamportLabel, fleetHealthLabel, kpiSpacer);

        topContainer.getChildren().addAll(titleRow, kpiRow);
        return topContainer;
    }

    private Label createKpiCard(String labelText, String valText, String colorHex) {
        Label card = new Label(labelText + "\n" + valText);
        card.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9.5));
        card.setTextFill(Color.WHITE);
        card.setPadding(new Insets(3, 8, 3, 8));
        card.setStyle("-fx-background-color: " + colorHex + "22; -fx-border-color: " + colorHex + "88; -fx-border-radius: 5px; -fx-background-radius: 5px;");
        return card;
    }

    // =========================================================================
    // 2. CENTER INTERACTIVE MAP & SCENARIO BANNER OVERLAY
    // =========================================================================
    private Node createCenterMapArea() {
        canvas = new Canvas(map.getWidth() * CELL_SIZE, map.getHeight() * CELL_SIZE);
        canvas.setStyle("-fx-cursor: crosshair;");

        canvas.setOnMouseMoved(this::onCanvasMouseMoved);
        canvas.setOnMouseClicked(this::onCanvasMouseClicked);
        canvas.setOnMouseExited(e -> {
            hoveredCell = null;
            updateMapStatusBar();
        });

        canvasWrapper = new StackPane(canvas, scenarioBannerOverlay);
        canvasWrapper.setPadding(new Insets(4));
        canvasWrapper.setStyle("-fx-background-color: #0F172A; -fx-border-color: #1E293B; -fx-border-width: 1.5px; -fx-border-radius: 8px; -fx-background-radius: 8px;");

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

        Optional<AgvRobot> clickedRobot = robots.stream()
                .filter(r -> r.getCurrentGridPos().equals(clicked) || Math.hypot(r.getRenderX() - gx, r.getRenderY() - gy) < 0.8)
                .findFirst();

        if (clickedRobot.isPresent()) {
            selectedRobot = clickedRobot.get();
        } else {
            if (selectedRobot != null && !selectedRobot.isCrashed() && map.isWalkable(clicked)) {
                showQuickDispatchConfirmation(selectedRobot, clicked);
            }
        }
    }

    private void showQuickDispatchConfirmation(AgvRobot robot, GridPoint destination) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(primaryStage);
        alert.setTitle("Điều hướng trực tiếp AGV");
        alert.setHeaderText("Điều xe AGV #" + robot.getNodeId() + " đến tọa độ " + destination + "?");
        alert.setContentText("Xe sẽ tự động tính toán lộ trình Dynamic A* để tránh vật cản.");

        alert.showAndWait().ifPresent(res -> {
            if (res == ButtonType.OK) {
                robot.setDestination(destination);
                robot.getNode().setAgentState(AgentState.MOVING);
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
            mapStatusBar.setText(String.format("Tọa độ: (%d, %d) | Kiểu ô: %s | Hiện diện: %s%s",
                    hoveredCell.x(), hoveredCell.y(), type, occupant, selText));
        } else {
            String selText = selectedRobot != null ? " | Selected: AGV #" + selectedRobot.getNodeId() + " (" + selectedRobot.getState() + ")" : "";
            mapStatusBar.setText("Bản đồ kho 24 x 16 ô | Bấm chọn xe AGV để kiểm tra lộ trình" + selText);
        }
    }

    // =========================================================================
    // 3. RIGHT TABBED ENTERPRISE SIDEBAR
    // =========================================================================
    private Node createRightTabbedSidebar() {
        TabPane tabPane = new TabPane();
        tabPane.setPrefWidth(480);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.setStyle("-fx-background-color: #0F172A; -fx-border-color: #1E293B; -fx-border-width: 0 0 0 1px;");

        Tab fleetTab = new Tab("🚜 Đội xe", createFleetTabContent());
        Tab metricsTab = new Tab("📊 Chỉ số", metricsPanel);
        Tab ordersTab = new Tab("📋 Nhiệm vụ", createOrdersTabContent());
        Tab trafficTab = new Tab("📡 Wire", wireTrafficPanel);
        Tab peersTab = new Tab("🌐 Peers", peersPanel);
        Tab perNodeTab = new Tab("🔍 Per-Node", perNodePanel);
        Tab timelineTab = new Tab("⏱ Timeline", timelinePanel);

        tabPane.getTabs().addAll(fleetTab, metricsTab, ordersTab, trafficTab, peersTab, perNodeTab, timelineTab);
        return tabPane;
    }

    private Node createFleetTabContent() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));
        box.setStyle("-fx-background-color: #0F172A;");

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        Label lbl = new Label("DANH SÁCH XE AGV PHI TẬP TRUNG");
        lbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        lbl.setTextFill(Color.web("#E2E8F0"));

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button addBtn = new Button("➕ Thêm AGV");
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
        Label lbl = new Label("QUẢN LÝ NHIỆM VỤ VẬN CHUYỂN");
        lbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        lbl.setTextFill(Color.web("#E2E8F0"));

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button addOrderBtn = new Button("➕ Đơn hàng mới");
        addOrderBtn.setStyle("-fx-background-color: #2563EB; -fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 4 10 4 10; -fx-background-radius: 4px;");
        addOrderBtn.setOnAction(e -> showTaskConfigDialog());

        top.getChildren().addAll(lbl, sp, addOrderBtn);

        tasksListVBox = new VBox(8);
        ScrollPane scroll = new ScrollPane(tasksListVBox);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: #0F172A; -fx-border-color: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        box.getChildren().addAll(top, scroll);
        return box;
    }

    // =========================================================================
    // 4. BOTTOM ACTION BAR (CUSTOM TASK, AGV DEPLOY, RECALL, CLEAR)
    // =========================================================================
    private Node createEnterpriseControlBar() {
        HBox bar = new HBox(8);
        bar.setPadding(new Insets(6, 14, 6, 14));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: #111827; -fx-border-color: #1F2937; -fx-border-width: 1 0 0 0;");

        Button newTaskBtn = createStyledButton("📦 Tạo nhiệm vụ...", "#2563EB", "Tạo đơn hàng vận chuyển mới với tùy chọn nạp thầu CNP");
        newTaskBtn.setOnAction(e -> showTaskConfigDialog());

        Button quickTaskBtn = createStyledButton("⚡ Nhiệm vụ ngẫu nhiên", "#1D4ED8", "Tự động sinh nhiệm vụ giữa 2 ô trống bất kỳ");
        quickTaskBtn.setOnAction(e -> spawnRandomTask());

        Button deployAgvBtn = createStyledButton("➕ Thêm xe AGV...", "#059669", "Cấu hình và triển khai thêm xe AGV vào mạng mesh");
        deployAgvBtn.setOnAction(e -> showAgvConfigDialog());

        Button chargeAllBtn = createStyledButton("⚡ Thu hồi về sạc", "#475569", "Yêu cầu tất cả xe rảnh rỗi di chuyển về trạm sạc gần nhất");
        chargeAllBtn.setOnAction(e -> recallAllToCharging());

        Button clearCompletedBtn = createStyledButton("🧹 Xóa đơn xong", "#334155", "Xóa các nhiệm vụ đã hoàn thành khỏi danh sách");
        clearCompletedBtn.setOnAction(e -> allTasks.removeIf(t -> t.getStatus() == Task.Status.COMPLETED));

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Label legend = new Label("Trạng thái xe: 🟦 IDLE  🟪 BIDDING  🟩 MOVING  🟧 MUTEX  🟥 CRASH");
        legend.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 10));
        legend.setTextFill(Color.web("#94A3B8"));

        bar.getChildren().addAll(newTaskBtn, quickTaskBtn, deployAgvBtn, chargeAllBtn, clearCompletedBtn, sp, legend);
        return bar;
    }

    private Button createStyledButton(String text, String hexColor, String tooltipText) {
        Button btn = new Button(text);
        btn.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10.5));
        btn.setTextFill(Color.WHITE);
        btn.setStyle("-fx-background-color: " + hexColor + "; -fx-background-radius: 5px; -fx-padding: 5 10 5 10; -fx-cursor: hand;");
        if (tooltipText != null) {
            Tooltip.install(btn, new Tooltip(tooltipText));
        }
        btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: derive(" + hexColor + ", 18%); -fx-background-radius: 5px; -fx-padding: 5 10 5 10; -fx-cursor: hand;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: " + hexColor + "; -fx-background-radius: 5px; -fx-padding: 5 10 5 10; -fx-cursor: hand;"));
        return btn;
    }

    // =========================================================================
    // 5. PRESENTATION MODE & KEYBOARD SHORTCUTS
    // =========================================================================
    private void setupKeyboardShortcuts(Scene scene) {
        scene.setOnKeyPressed(e -> {
            KeyCode code = e.getCode();
            if (code == KeyCode.DIGIT1 || code == KeyCode.NUMPAD1) {
                runScenarioS1(2);
            } else if (code == KeyCode.DIGIT2 || code == KeyCode.NUMPAD2) {
                runScenarioS2();
            } else if (code == KeyCode.DIGIT3 || code == KeyCode.NUMPAD3) {
                showS3ChoiceDialog();
            } else if (code == KeyCode.SPACE) {
                toggleFleetPause();
            } else if (code == KeyCode.R) {
                resetFleetToDefault();
            } else if (code == KeyCode.P || code == KeyCode.F11) {
                togglePresentationMode();
            }
        });
    }

    private void togglePresentationMode() {
        presentationModeActive = !presentationModeActive;
        if (presentationModeActive) {
            presentationModeBtn.setText("🗗 Thoát Trình Chiếu");
            presentationModeBtn.setStyle("-fx-background-color: #7C3AED; -fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 4 10 4 10; -fx-cursor: hand; -fx-background-radius: 4px;");
            rootPane.setRight(null);
            canvasWrapper.setScaleX(1.12);
            canvasWrapper.setScaleY(1.12);
        } else {
            presentationModeBtn.setText("🖥 Trình chiếu");
            presentationModeBtn.setStyle("-fx-background-color: #475569; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 4 10 4 10; -fx-cursor: hand; -fx-background-radius: 4px;");
            rootPane.setRight(rightSidebarNode);
            canvasWrapper.setScaleX(1.0);
            canvasWrapper.setScaleY(1.0);
        }
    }

    private void toggleFleetPause() {
        fleetPaused = !fleetPaused;
        if (physicsController != null) {
            physicsController.setPaused(fleetPaused);
        }
        for (AgvRobot robot : robots) {
            robot.setPaused(fleetPaused);
        }
        if (fleetPaused) {
            pauseFleetBtn.setText("▶ Tiếp tục");
            pauseFleetBtn.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 4 10 4 10; -fx-cursor: hand; -fx-background-radius: 4px;");
        } else {
            pauseFleetBtn.setText("⏸ Tạm dừng");
            pauseFleetBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 4 10 4 10; -fx-cursor: hand; -fx-background-radius: 4px;");
        }
    }

    // =========================================================================
    // 6. SCENARIO 1: HEAD-ON INTERSECTION (2, 4, 8 AGVS)
    // =========================================================================
    public void runScenarioS1(int vehicleCount) {
        scenarioBannerOverlay.hideVerdict();
        stopAndClearFleet();
        allTasks.clear();
        metricsCollector.reset();

        scenarioController.startS1Scenario(vehicleCount);

        Button btn2 = new Button("2 Xe");
        btn2.setStyle("-fx-background-color: #3B82F6; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand;");
        btn2.setOnAction(e -> runScenarioS1(2));

        Button btn4 = new Button("4 Xe");
        btn4.setStyle("-fx-background-color: #06B6D4; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand;");
        btn4.setOnAction(e -> runScenarioS1(4));

        Button btn8 = new Button("8 Xe");
        btn8.setStyle("-fx-background-color: #8B5CF6; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand;");
        btn8.setOnAction(e -> runScenarioS1(8));

        scenarioBannerOverlay.setScenarioControls(btn2, btn4, btn8);
        scenarioBannerOverlay.showBanner(UiScenarioType.S1_HEAD_ON_INTERSECTION,
                String.format("Bước 1/3: Khởi tạo %d xe chuẩn bị cùng tiến vào ngã tư (7, 6)...", vehicleCount));

        int ix = 7;
        int iy = 6;

        if (vehicleCount == 2) {
            AgvRobot r1 = createAndStartRobot(1L, new GridPoint(ix - 4, iy), 100.0, "Pallet AGV-1", 1.0);
            AgvRobot r2 = createAndStartRobot(2L, new GridPoint(ix, iy - 4), 100.0, "Pallet AGV-2", 1.0);

            CompletableFuture.delayedExecutor(1200, TimeUnit.MILLISECONDS).execute(() -> Platform.runLater(() -> {
                scenarioController.updateS1Step2();
                scenarioBannerOverlay.updateStepBanner("Bước 2/3: Cả 2 xe đồng thời tiến vào ngã tư. Thuật toán Ricart-Agrawala giải quyết quyền ưu tiên...");
                r1.setDestination(new GridPoint(ix + 4, iy));
                r2.setDestination(new GridPoint(ix, iy + 4));
                r1.getNode().setAgentState(AgentState.MOVING);
                r2.getNode().setAgentState(AgentState.MOVING);
            }));
        } else if (vehicleCount == 4) {
            AgvRobot r1 = createAndStartRobot(1L, new GridPoint(ix - 4, iy), 100.0, "Pallet AGV-1", 1.0);
            AgvRobot r2 = createAndStartRobot(2L, new GridPoint(ix, iy - 4), 100.0, "Pallet AGV-2", 1.0);
            AgvRobot r3 = createAndStartRobot(3L, new GridPoint(ix + 4, iy), 100.0, "Pallet AGV-3", 1.0);
            AgvRobot r4 = createAndStartRobot(4L, new GridPoint(ix, iy + 4), 100.0, "Pallet AGV-4", 1.0);

            CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS).execute(() -> Platform.runLater(() -> {
                scenarioController.updateS1Step2();
                scenarioBannerOverlay.updateStepBanner("Bước 2/3: 4 xe từ 4 hướng cùng xin vào ngã tư (7, 6)...");
                r1.setDestination(new GridPoint(ix + 4, iy));
                r2.setDestination(new GridPoint(ix, iy + 4));
                r3.setDestination(new GridPoint(ix - 4, iy));
                r4.setDestination(new GridPoint(ix, iy - 4));
                r1.getNode().setAgentState(AgentState.MOVING);
                r2.getNode().setAgentState(AgentState.MOVING);
                r3.getNode().setAgentState(AgentState.MOVING);
                r4.getNode().setAgentState(AgentState.MOVING);
            }));
        } else {
            // 8 vehicles
            AgvRobot r1 = createAndStartRobot(1L, new GridPoint(ix - 3, iy), 100.0, "Pallet AGV-1", 1.0);
            AgvRobot r2 = createAndStartRobot(2L, new GridPoint(ix - 5, iy), 100.0, "Pallet AGV-2", 1.0);
            AgvRobot r3 = createAndStartRobot(3L, new GridPoint(ix, iy - 3), 100.0, "Pallet AGV-3", 1.0);
            AgvRobot r4 = createAndStartRobot(4L, new GridPoint(ix, iy - 5), 100.0, "Pallet AGV-4", 1.0);
            AgvRobot r5 = createAndStartRobot(5L, new GridPoint(ix + 3, iy), 100.0, "Pallet AGV-5", 1.0);
            AgvRobot r6 = createAndStartRobot(6L, new GridPoint(ix + 5, iy), 100.0, "Pallet AGV-6", 1.0);
            AgvRobot r7 = createAndStartRobot(7L, new GridPoint(ix, iy + 3), 100.0, "Pallet AGV-7", 1.0);
            AgvRobot r8 = createAndStartRobot(8L, new GridPoint(ix, iy + 5), 100.0, "Pallet AGV-8", 1.0);

            CompletableFuture.delayedExecutor(1800, TimeUnit.MILLISECONDS).execute(() -> Platform.runLater(() -> {
                scenarioController.updateS1Step2();
                scenarioBannerOverlay.updateStepBanner("Bước 2/3: 8 xe đồng thời xin cấp quyền ngã tư...");
                r1.setDestination(new GridPoint(ix + 4, iy));
                r2.setDestination(new GridPoint(ix + 5, iy));
                r3.setDestination(new GridPoint(ix, iy + 4));
                r4.setDestination(new GridPoint(ix, iy + 5));
                r5.setDestination(new GridPoint(ix - 4, iy));
                r6.setDestination(new GridPoint(ix - 5, iy));
                r7.setDestination(new GridPoint(ix, iy - 4));
                r8.setDestination(new GridPoint(ix, iy - 5));

                for (AgvRobot r : robots) {
                    r.getNode().setAgentState(AgentState.MOVING);
                }
            }));
        }
    }

    // =========================================================================
    // 7. SCENARIO 2: ADAPTIVE CNP TASK AUCTION (5 AGVS WITH DIFFERENT BATTERY)
    // =========================================================================
    public void runScenarioS2() {
        scenarioBannerOverlay.hideVerdict();
        stopAndClearFleet();
        allTasks.clear();
        metricsCollector.reset();

        scenarioController.startS2Scenario();
        scenarioBannerOverlay.showBanner(UiScenarioType.S2_ADAPTIVE_AUCTION,
                "Bước 1/3: Khởi tạo 5 xe với pin: AGV-1 (90%), AGV-2 (60%), AGV-3 (40%), AGV-4 (20%), AGV-5 (10%)...");

        AgvRobot r1 = createAndStartRobot(1L, new GridPoint(2, 2), 90.0, "AGV-1 (90% Bat)", 1.0);
        AgvRobot r2 = createAndStartRobot(2L, new GridPoint(8, 4), 60.0, "AGV-2 (60% Bat)", 1.0);
        AgvRobot r3 = createAndStartRobot(3L, new GridPoint(14, 6), 40.0, "AGV-3 (40% Bat)", 1.0);
        AgvRobot r4 = createAndStartRobot(4L, new GridPoint(18, 10), 20.0, "AGV-4 (20% Bat)", 1.0);
        AgvRobot r5 = createAndStartRobot(5L, new GridPoint(22, 14), 10.0, "AGV-5 (10% Bat)", 1.0);

        CompletableFuture.delayedExecutor(1600, TimeUnit.MILLISECONDS).execute(() -> Platform.runLater(() -> {
            Task task = new Task("TASK-CNP-DEMO", 5, 5, 20, 6, Task.Priority.HIGH, "High-Value Electronics");
            allTasks.add(task);

            cnpVisualAnnouncerId = 1L;
            cnpVisualBids.clear();
            cnpVisualWinnerId = null;
            cnpVisualTimestamp = System.currentTimeMillis();

            scenarioController.updateS2Step2(task.getTaskId());
            scenarioBannerOverlay.updateStepBanner("Bước 2/3: AGV 1 phát thầu TASK-CNP-DEMO. Các xe nộp báo giá cạnh tranh...");

            r1.getNode().announceTask(task);
        }));
    }

    // =========================================================================
    // 8. SCENARIO 3: NODE FAILURE & CRASH RECOVERY
    // =========================================================================
    private void showS3ChoiceDialog() {
        scenarioBannerOverlay.hideVerdict();
        stopAndClearFleet();
        allTasks.clear();
        metricsCollector.reset();

        scenarioController.startS3Scenario("Chọn biến thể");

        Button hardCrashBtn = new Button("1. Hard Crash");
        hardCrashBtn.setStyle("-fx-background-color: #EF4444; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand;");
        hardCrashBtn.setOnAction(e -> runScenarioS3Variant("Hard Crash"));

        Button freezeNetBtn = new Button("2. Freeze Network");
        freezeNetBtn.setStyle("-fx-background-color: #F59E0B; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand;");
        freezeNetBtn.setOnAction(e -> runScenarioS3Variant("Freeze Network"));

        Button mutexCrashBtn = new Button("3. Crash khi giữ ngã tư");
        mutexCrashBtn.setStyle("-fx-background-color: #8B5CF6; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand;");
        mutexCrashBtn.setOnAction(e -> runScenarioS3Variant("Crash Holding Mutex"));

        scenarioBannerOverlay.setScenarioControls(hardCrashBtn, freezeNetBtn, mutexCrashBtn);
        scenarioBannerOverlay.showBanner(UiScenarioType.S3_CRASH_RECOVERY,
                "Bước 1/2: Chọn một trong 3 biến thể sự cố mạng để mô phỏng khả năng tự phục hồi:");
    }

    public void runScenarioS3Variant(String variant) {
        stopAndClearFleet();
        allTasks.clear();
        metricsCollector.reset();

        scenarioController.startS3Scenario(variant);

        if ("Hard Crash".equals(variant)) {
            scenarioBannerOverlay.updateStepBanner("Bước 1/2: Khởi tạo AGV 1 và AGV 2. AGV 1 phát thầu, AGV 2 nhận việc và đột ngột sập nguồn...");
            AgvRobot r1 = createAndStartRobot(1L, new GridPoint(2, 2), 100.0, "Announcer AGV-1", 1.0);
            AgvRobot r2 = createAndStartRobot(2L, new GridPoint(8, 4), 90.0, "Contractor AGV-2", 1.0);
            AgvRobot r3 = createAndStartRobot(3L, new GridPoint(14, 6), 90.0, "Backup AGV-3", 1.0);

            CompletableFuture.delayedExecutor(1400, TimeUnit.MILLISECONDS).execute(() -> Platform.runLater(() -> {
                Task task = new Task("TASK-REALLOC-S3", 4, 4, 18, 6);
                allTasks.add(task);
                r1.getNode().announceTask(task);

                CompletableFuture.delayedExecutor(800, TimeUnit.MILLISECONDS).execute(() -> Platform.runLater(() -> {
                    scenarioBannerOverlay.updateStepBanner("💥 AGV 2 đột ngột sập nguồn (Hard Crash). AGV 1 phát hiện mất kết nối và tự động tái phát thầu...");
                    r2.crash();
                }));
            }));
        } else if ("Freeze Network".equals(variant)) {
            scenarioBannerOverlay.updateStepBanner("Bước 1/2: AGV 2 bị treo mạng (freeze network: giữ socket mở nhưng ngừng gửi heartbeat)...");
            AgvRobot r1 = createAndStartRobot(1L, new GridPoint(2, 2), 100.0, "Monitor AGV-1", 1.0);
            AgvRobot r2 = createAndStartRobot(2L, new GridPoint(10, 4), 90.0, "Hanging AGV-2", 1.0);

            CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS).execute(() -> Platform.runLater(() -> {
                scenarioBannerOverlay.updateStepBanner("⏳ AGV 2 ngừng gửi heartbeat. AGV 1 đang đếm ngược thời gian hết hạn nhịp tim (heartbeat timeout)...");
                r2.getNode().freezeNetwork();

                CompletableFuture.delayedExecutor(1800, TimeUnit.MILLISECONDS).execute(() -> Platform.runLater(() -> {
                    UiScenarioResult res = scenarioController.finishS3Scenario("Freeze Network", true, 1500);
                    scenarioBannerOverlay.showVerdict(res);
                }));
            }));
        } else {
            // Crash Holding Mutex
            scenarioBannerOverlay.updateStepBanner("Bước 1/2: AGV 1 đang chiếm giữ ngã tư (7, 6). AGV 2 đang chờ. AGV 1 bị sập nguồn đột ngột...");
            AgvRobot r1 = createAndStartRobot(1L, new GridPoint(7, 6), 100.0, "Holding AGV-1", 1.0);
            AgvRobot r2 = createAndStartRobot(2L, new GridPoint(7, 2), 100.0, "Waiting AGV-2", 1.0);

            CompletableFuture.delayedExecutor(1200, TimeUnit.MILLISECONDS).execute(() -> Platform.runLater(() -> {
                r1.getNode().acquireIntersection("(7, 6)");
                r2.setDestination(new GridPoint(7, 10));
                r2.getNode().setAgentState(AgentState.MOVING);

                CompletableFuture.delayedExecutor(1000, TimeUnit.MILLISECONDS).execute(() -> Platform.runLater(() -> {
                    scenarioBannerOverlay.updateStepBanner("💥 AGV 1 sập khi đang HELD ngã tư! AGV 2 phát hiện qua cơ chế onPeerDropped và tự động được thăng cấp HELD...");
                    r1.crash();
                }));
            }));
        }
    }

    // =========================================================================
    // 9. SIMULATION LOOPS (3 INDEPENDENT RHYTHMS)
    // (a) Logic xe 4 Hz (250ms at 1x) managed by PhysicsLoopController
    // (b) Render Canvas 60 FPS via AnimationTimer
    // (c) UI Refresh 5 Hz (200ms) via JavaFX Timeline (runs even when paused)
    // =========================================================================
    private void startSimulationLoops() {
        physicsController = new PhysicsLoopController(this::physicsAndLogicTick);
        physicsController.restartPhysicsLoop();

        renderLoop = new AnimationTimer() {
            @Override
            public void handle(long now) {
                renderCanvas();
            }
        };
        renderLoop.start();

        uiRefreshTimeline = new Timeline(new KeyFrame(Duration.millis(200), e -> onUiRefreshTick()));
        uiRefreshTimeline.setCycleCount(Timeline.INDEFINITE);
        uiRefreshTimeline.play();
    }

    private void restartPhysicsLoop() {
        if (physicsController != null) {
            physicsController.restartPhysicsLoop();
        }
    }

    private void onUiRefreshTick() {
        metricsCollector.setAlivePeersCount((int) robots.stream().filter(r -> !r.isCrashed()).count());
        metricsCollector.tickPeriodicSample();

        if (robots.size() >= 2) {
            AgvRobot r1 = robots.get(0);
            AgvRobot r2 = robots.get(1);
            if (!r1.isCrashed() && !r2.isCrashed()) {
                r1.getNode().ping(r2.getNodeId()).whenComplete((rttNanos, err) -> {
                    if (err == null && rttNanos != null) {
                        metricsCollector.recordLatencySample(rttNanos / 1_000_000.0);
                    }
                });
            }
        }

        updateDashboardTelemetry();
        metricsPanel.update(metricsCollector);
        wireTrafficPanel.refreshView();
        if (peersPanel != null) peersPanel.refresh();
        if (perNodePanel != null) perNodePanel.refresh();
        if (timelinePanel != null) timelinePanel.refresh();
    }

    private void physicsAndLogicTick() {
        if (fleetPaused) return;

        try {
            // 1. Update Physics for each robot
            for (AgvRobot robot : robots) {
                robot.updatePhysicsAndLogic(null);
                GridPoint cur = robot.getCurrentGridPos();
                GridPoint prev = lastReportedGridPos.put(robot.getNodeId(), cur);
                if (prev != null && !prev.equals(cur)) {
                    if (perNodePanel != null) perNodePanel.recordEvent(robot.getNodeId(), "Đổi ô sang " + cur);
                    if (timelinePanel != null) timelinePanel.recordEvent(robot.getNodeId(), TimelinePanel.EventCategory.MOVE_STEP, "Đổi ô tới " + cur);
                }
            }

            // 2. Ground-Truth Collision Detection
            for (int i = 0; i < robots.size(); i++) {
                AgvRobot r1 = robots.get(i);
                if (r1.isCrashed()) continue;
                for (int j = i + 1; j < robots.size(); j++) {
                    AgvRobot r2 = robots.get(j);
                    if (r2.isCrashed()) continue;

                    double dist = Math.hypot(r1.getRenderX() - r2.getRenderX(), r1.getRenderY() - r2.getRenderY());
                    if (dist < 0.70) {
                        metricsCollector.recordCollision();
                    }
                }
            }

            // 3. Check S1 Scenario Completion
            if (scenarioController.isRunning() && scenarioController.getActiveScenario() == UiScenarioType.S1_HEAD_ON_INTERSECTION) {
                boolean allDone = true;
                for (AgvRobot r : robots) {
                    if (!r.getRemainingPath().isEmpty() || r.getState() == AgentState.WAITING_MUTEX) {
                        allDone = false;
                        break;
                    }
                }
                if (allDone && !robots.isEmpty()) {
                    Platform.runLater(() -> {
                        UiScenarioResult res = scenarioController.finishS1Scenario(
                                true,
                                metricsCollector.getGroundTruthCollisions(),
                                metricsCollector.getAverageMutexWaitTimeMs()
                        );
                        scenarioBannerOverlay.showVerdict(res);
                    });
                }
            }
        } catch (Exception e) {
            log.error("Error in physics simulation tick", e);
        }
    }

    // =========================================================================
    // 10. CANVAS RENDERING ENGINE (ALGORITHM VISUALIZATION)
    // =========================================================================
    private void renderCanvas() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

        // Background Floor
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

        // Zone Annotations
        drawZoneAnnotations(gc);

        // 1. Render Cells (Obstacles, Docks, Intersections)
        for (int x = 0; x < map.getWidth(); x++) {
            for (int y = 0; y < map.getHeight(); y++) {
                CellType type = map.getCellType(x, y);
                double px = x * CELL_SIZE;
                double py = y * CELL_SIZE;

                switch (type) {
                    case OBSTACLE -> {
                        gc.setFill(Color.web("#1E293B"));
                        gc.fillRoundRect(px + 2, py + 2, CELL_SIZE - 4, CELL_SIZE - 4, 4, 4);
                        gc.setStroke(Color.web("#334155"));
                        gc.setLineWidth(1.5);
                        gc.strokeRoundRect(px + 2, py + 2, CELL_SIZE - 4, CELL_SIZE - 4, 4, 4);

                        gc.setStroke(Color.web("#293548"));
                        gc.setLineWidth(1.0);
                        gc.strokeLine(px + 3, py + CELL_SIZE / 2, px + CELL_SIZE - 3, py + CELL_SIZE / 2);
                    }
                    case CHARGING_STATION -> {
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
                            // HELD: Warm Orange fill with label
                            gc.setFill(Color.web("#EA580C", 0.65));
                            gc.fillRect(px + 1, py + 1, CELL_SIZE - 2, CELL_SIZE - 2);
                            gc.setStroke(Color.web("#F97316"));
                            gc.setLineWidth(2.5);
                            gc.strokeRect(px + 1, py + 1, CELL_SIZE - 2, CELL_SIZE - 2);

                            gc.setFill(Color.WHITE);
                            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8.5));
                            gc.fillText("🔒A" + holdingRobot.get().getNodeId(), px + 2, py + 18);

                            // Draw dashed arrow from any waiting / deferred robot to holding robot
                            List<Long> deferredIds = holdingRobot.get().getNode().getDeferredNodes(cellId);
                            if (deferredIds != null && !deferredIds.isEmpty()) {
                                double hx = holdingRobot.get().getRenderX() * CELL_SIZE + CELL_SIZE / 2;
                                double hy = holdingRobot.get().getRenderY() * CELL_SIZE + CELL_SIZE / 2;

                                gc.setStroke(Color.web("#F59E0B", 0.85));
                                gc.setLineWidth(1.8);
                                gc.setLineDashes(4.0, 4.0);

                                for (Long dId : deferredIds) {
                                    robots.stream().filter(r -> r.getNodeId() == dId).findFirst().ifPresent(dRobot -> {
                                        double dx = dRobot.getRenderX() * CELL_SIZE + CELL_SIZE / 2;
                                        double dy = dRobot.getRenderY() * CELL_SIZE + CELL_SIZE / 2;
                                        gc.strokeLine(dx, dy, hx, hy);
                                    });
                                }
                                gc.setLineDashes(null);
                            }
                        } else if (waitingRobot.isPresent()) {
                            // WANTED / Requesting: Amber fill
                            gc.setFill(Color.web("#CA8A04", 0.35));
                            gc.fillRect(px + 1, py + 1, CELL_SIZE - 2, CELL_SIZE - 2);
                            gc.setStroke(Color.web("#EAB308"));
                            gc.setLineWidth(2.0);
                            gc.strokeRect(px + 1, py + 1, CELL_SIZE - 2, CELL_SIZE - 2);

                            gc.setFill(Color.web("#FDE047"));
                            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8.0));
                            gc.fillText("⏳REQ", px + 1, py + 18);
                        } else {
                            // RELEASED: Subtle border
                            gc.setStroke(Color.web("#CA8A04", 0.45));
                            gc.setLineWidth(1.0);
                            gc.strokeRect(px + 2, py + 2, CELL_SIZE - 4, CELL_SIZE - 4);
                        }
                    }
                    case EMPTY -> {}
                }
            }
        }

        // 2. Draw P2P Mesh Heartbeat Links between Peer AGVs
        for (int i = 0; i < robots.size(); i++) {
            AgvRobot r1 = robots.get(i);
            if (r1.isCrashed()) continue;
            double x1 = r1.getRenderX() * CELL_SIZE + CELL_SIZE / 2;
            double y1 = r1.getRenderY() * CELL_SIZE + CELL_SIZE / 2;

            for (PeerSession session : r1.getNode().getActivePeers()) {
                if (session.getPeerId() > r1.getNodeId()) {
                    robots.stream().filter(r -> r.getNodeId() == session.getPeerId()).findFirst().ifPresent(r2 -> {
                        double x2 = r2.getRenderX() * CELL_SIZE + CELL_SIZE / 2;
                        double y2 = r2.getRenderY() * CELL_SIZE + CELL_SIZE / 2;

                        long age = System.currentTimeMillis() - session.getLastHeartbeatTimestamp();
                        if (r2.isCrashed() || age > 1500) {
                            gc.setStroke(Color.web("#EF4444", 0.8));
                            gc.setLineWidth(1.5);
                            gc.setLineDashes(4.0, 4.0);
                        } else if (age > 600) {
                            gc.setStroke(Color.web("#F59E0B", 0.65));
                            gc.setLineWidth(1.2);
                            gc.setLineDashes(null);
                        } else {
                            gc.setStroke(Color.web("#10B981", 0.4));
                            gc.setLineWidth(1.0);
                            gc.setLineDashes(null);
                        }
                        gc.strokeLine(x1, y1, x2, y2);
                        gc.setLineDashes(null);
                    });
                }
            }
        }

        // 3. Draw Active CNP Task Auction Visualization
        if (cnpVisualAnnouncerId != null && System.currentTimeMillis() - cnpVisualTimestamp < 4500) {
            robots.stream().filter(r -> r.getNodeId() == cnpVisualAnnouncerId).findFirst().ifPresent(announcer -> {
                double ax = announcer.getRenderX() * CELL_SIZE + CELL_SIZE / 2;
                double ay = announcer.getRenderY() * CELL_SIZE + CELL_SIZE / 2;

                for (Map.Entry<Long, Double> entry : cnpVisualBids.entrySet()) {
                    long bidderId = entry.getKey();
                    double cost = entry.getValue();

                    robots.stream().filter(r -> r.getNodeId() == bidderId).findFirst().ifPresent(bidder -> {
                        double bx = bidder.getRenderX() * CELL_SIZE + CELL_SIZE / 2;
                        double by = bidder.getRenderY() * CELL_SIZE + CELL_SIZE / 2;

                        gc.setStroke(Color.web("#6366F1", 0.7));
                        gc.setLineWidth(1.8);
                        gc.setLineDashes(3.0, 3.0);
                        gc.strokeLine(ax, ay, bx, by);
                        gc.setLineDashes(null);

                        double mx = (ax + bx) / 2;
                        double my = (ay + by) / 2;
                        gc.setFill(Color.web("#1E1B4B", 0.85));
                        gc.fillRoundRect(mx - 22, my - 7, 44, 14, 4, 4);
                        gc.setStroke(Color.web("#818CF8"));
                        gc.setLineWidth(1.0);
                        gc.strokeRoundRect(mx - 22, my - 7, 44, 14, 4, 4);

                        gc.setFill(Color.WHITE);
                        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8.5));
                        gc.fillText(String.format(Locale.US, "C=%.1f", cost), mx - 16, my + 3.5);
                    });
                }

                if (cnpVisualWinnerId != null) {
                    robots.stream().filter(r -> r.getNodeId() == cnpVisualWinnerId).findFirst().ifPresent(winner -> {
                        double wx = winner.getRenderX() * CELL_SIZE + CELL_SIZE / 2;
                        double wy = winner.getRenderY() * CELL_SIZE + CELL_SIZE / 2;

                        gc.setStroke(Color.web("#10B981"));
                        gc.setLineWidth(2.8);
                        gc.strokeRoundRect(wx - 16, wy - 16, 32, 32, 6, 6);

                        gc.setFill(Color.web("#065F46"));
                        gc.fillRoundRect(wx - 26, wy - 26, 52, 12, 3, 3);
                        gc.setFill(Color.WHITE);
                        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8.5));
                        gc.fillText("🏆 WINNER", wx - 23, wy - 17);
                    });
                }
            });
        }

        // 4. Draw Waypoints Trails
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

        // 5. Draw Tasks (Pickup & Delivery Markers)
        for (Task task : allTasks) {
            if (task.getStatus() == Task.Status.AUCTIONING || task.getStatus() == Task.Status.ASSIGNED || task.getStatus() == Task.Status.IN_TRANSIT) {
                double px = task.getPickupX() * CELL_SIZE + CELL_SIZE / 2;
                double py = task.getPickupY() * CELL_SIZE + CELL_SIZE / 2;
                double dx = task.getDeliveryX() * CELL_SIZE + CELL_SIZE / 2;
                double dy = task.getDeliveryY() * CELL_SIZE + CELL_SIZE / 2;

                gc.setFill(Color.web("#10B981", 0.35));
                gc.fillOval(px - 10, py - 10, 20, 20);
                gc.setStroke(Color.web("#34D399"));
                gc.setLineWidth(1.5);
                gc.strokeOval(px - 7, py - 7, 14, 14);
                gc.setFill(Color.WHITE);
                gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8.5));
                gc.fillText("📦 " + task.getTaskId(), px - 15, py - 11);

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

        // 6. Draw AGV Robots
        for (AgvRobot robot : robots) {
            double cx = robot.getRenderX() * CELL_SIZE + CELL_SIZE / 2;
            double cy = robot.getRenderY() * CELL_SIZE + CELL_SIZE / 2;
            double radius = 10.5;

            Color statusColor = switch (robot.getState()) {
                case IDLE -> Color.web("#3B82F6");
                case BIDDING -> Color.web("#8B5CF6");
                case MOVING, BUSY -> Color.web("#06B6D4");
                case WAITING_MUTEX -> Color.web("#F59E0B");
                case LOW_BATTERY_CHARGING -> Color.web("#10B981");
                case CRASHED -> Color.web("#EF4444");
            };

            if (robot == selectedRobot) {
                gc.setStroke(Color.web("#F59E0B"));
                gc.setLineWidth(2.0);
                gc.strokeOval(cx - radius - 4, cy - radius - 4, (radius + 4) * 2, (radius + 4) * 2);
            }

            gc.setFill(statusColor.deriveColor(0, 1, 1, 0.25));
            gc.fillOval(cx - radius - 2, cy - radius - 2, (radius + 2) * 2, (radius + 2) * 2);

            gc.setFill(Color.web("#0F172A"));
            gc.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
            gc.setStroke(statusColor);
            gc.setLineWidth(2.2);
            gc.strokeOval(cx - radius, cy - radius, radius * 2, radius * 2);

            gc.setFill(Color.WHITE);
            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 8.5));
            gc.fillText("A" + robot.getNodeId(), cx - 5.5, cy + 3.5);

            if (robot.isCarryingCargo()) {
                gc.setFill(Color.web("#F59E0B"));
                gc.fillRect(cx + 5, cy - 6, 6, 6);
                gc.setStroke(Color.WHITE);
                gc.setLineWidth(1.0);
                gc.strokeRect(cx + 5, cy - 6, 6, 6);
            }

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
    // 11. FLEET TELEMETRY & ROBOT CREATION
    // =========================================================================
    private void initFleet() {
        createAndStartRobot(1L, new GridPoint(2, 2), 100.0, "Pallet AGV-Pro", 1.0);
        createAndStartRobot(2L, new GridPoint(12, 6), 90.0, "Forklift AGV-Heavy", 1.1);
        createAndStartRobot(3L, new GridPoint(22, 10), 85.0, "Tugger AGV-Fast", 0.9);
        createAndStartRobot(4L, new GridPoint(4, 12), 80.0, "Unit-Load AGV", 1.0);
        createAndStartRobot(5L, new GridPoint(18, 2), 75.0, "Compact AGV", 1.0);
        nextNodeId.set(6);
    }

    private void stopAndClearFleet() {
        for (AgvRobot robot : robots) {
            try {
                robot.getNode().stop();
            } catch (Exception ignored) {}
        }
        robots.clear();
        selectedRobot = null;
    }

    private void resetFleetToDefault() {
        scenarioBannerOverlay.hideBanner();
        scenarioBannerOverlay.hideVerdict();
        stopAndClearFleet();
        allTasks.clear();
        metricsCollector.reset();
        trafficLog.clear();
        initFleet();
    }

    private AgvRobot createAndStartRobot(long nodeId, GridPoint initialPos, double battery, String model, double maintenanceWeight) {
        AgvNode node = AgvNode.builder()
                .nodeId(nodeId)
                .host("127.0.0.1")
                .tcpPort(0)
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

        node.registerMessageListener((msg, ch) -> {
            logWireMessage(nodeId, msg);
            metricsCollector.recordMessageProcessed();
        });

        node.addMutexEventListener(new com.agv.fleet.distributed.mutex.RicartAgrawalaEngine.MutexEventListener() {
            @Override
            public void onMutexRequested(String cellId, long requestTimestamp) {
                mutexRequestTimestamps.put(nodeId + ":" + cellId, System.currentTimeMillis());
                if (perNodePanel != null) perNodePanel.recordEvent(nodeId, "Yêu cầu Mutex " + cellId + " (L=" + requestTimestamp + ")");
                if (timelinePanel != null) timelinePanel.recordEvent(nodeId, TimelinePanel.EventCategory.MUTEX_REQ, "Yêu cầu Mutex " + cellId);
            }

            @Override
            public void onMutexAcquired(String cellId) {
                Long startMs = mutexRequestTimestamps.remove(nodeId + ":" + cellId);
                if (startMs != null) {
                    long elapsed = System.currentTimeMillis() - startMs;
                    metricsCollector.recordMutexWait(elapsed);
                }
                if (perNodePanel != null) perNodePanel.recordEvent(nodeId, "Đã chiếm giữ Mutex " + cellId);
                if (timelinePanel != null) timelinePanel.recordEvent(nodeId, TimelinePanel.EventCategory.MUTEX_OK, "Chiếm Mutex " + cellId);
            }

            @Override
            public void onMutexReleased(String cellId) {
                if (perNodePanel != null) perNodePanel.recordEvent(nodeId, "Giải phóng Mutex " + cellId);
                if (timelinePanel != null) timelinePanel.recordEvent(nodeId, TimelinePanel.EventCategory.MUTEX_OK, "Nhả Mutex " + cellId);
            }
        });

        node.addCnpEventListener(new com.agv.fleet.distributed.auction.CnpEngine.CnpEventListener() {
            @Override
            public void onTaskAnnounced(Task task) {
                metricsCollector.recordAuctionAnnounced();
                if (perNodePanel != null) perNodePanel.recordEvent(nodeId, "Phát thầu nhiệm vụ " + task.getTaskId());
            }

            @Override
            public void onBidSubmitted(String taskId, double cost, double battery) {
                metricsCollector.recordBidSubmitted();
                cnpVisualBids.put(nodeId, cost);
                if (perNodePanel != null) perNodePanel.recordEvent(nodeId, String.format(Locale.US, "Nộp thầu %s (cost=%.2f, pin=%.0f%%)", taskId, cost, battery));
            }

            @Override
            public void onTaskWon(Task task) {
                cnpVisualWinnerId = nodeId;
                if (perNodePanel != null) perNodePanel.recordEvent(nodeId, "Trúng thầu nhiệm vụ " + task.getTaskId());
                if (timelinePanel != null) timelinePanel.recordEvent(nodeId, TimelinePanel.EventCategory.TASK_WON, "Trúng thầu " + task.getTaskId());
                if (scenarioController.isRunning() && scenarioController.getActiveScenario() == UiScenarioType.S2_ADAPTIVE_AUCTION) {
                    Platform.runLater(() -> {
                        boolean agv5Bid = cnpVisualBids.containsKey(5L);
                        double winnerCost = cnpVisualBids.getOrDefault(nodeId, 0.0);
                        UiScenarioResult res = scenarioController.finishS2Scenario(nodeId, winnerCost, agv5Bid);
                        scenarioBannerOverlay.showVerdict(res);
                    });
                }
            }

            @Override
            public void onTaskCompleted(String taskId, long contractorNodeId) {
                metricsCollector.recordTaskCompleted();
                if (perNodePanel != null) perNodePanel.recordEvent(nodeId, "Hoàn thành nhiệm vụ " + taskId);
                if (timelinePanel != null) timelinePanel.recordEvent(nodeId, TimelinePanel.EventCategory.TASK_DONE, "Xong " + taskId);
            }
        });

        try {
            node.start();
            robots.add(robot);
        } catch (Exception e) {
            log.error("Failed to start AGV node {}", nodeId, e);
        }
        return robot;
    }

    private void logWireMessage(long receiverId, AgvMessage msg) {
        Opcode op = msg.getOpcode();
        long senderId = msg.getSenderNodeId();
        long lamport = msg.getHeader().lamportTimestamp();

        String detail = "";
        try {
            switch (op) {
                case PEER_DISCOVERY -> detail = "Discovery Handshake";
                case HEARTBEAT -> detail = "Heartbeat Ping";
                case MUTEX_REQUEST -> {
                    MutexRequestPayload p = MutexRequestPayload.fromBytes(msg.getPayload());
                    detail = "Yêu cầu Mutex " + p.cellId() + " (L=" + p.requestTimestamp() + ")";
                }
                case MUTEX_REPLY -> {
                    MutexReplyPayload p = MutexReplyPayload.fromBytes(msg.getPayload());
                    detail = "Chấp thuận Mutex " + p.cellId();
                }
                case MUTEX_RELEASE -> {
                    MutexReleasePayload p = MutexReleasePayload.fromBytes(msg.getPayload());
                    detail = "Giải phóng Mutex " + p.cellId();
                }
                case TASK_ANNOUNCE -> {
                    TaskAnnouncePayload p = TaskAnnouncePayload.fromBytes(msg.getPayload());
                    detail = "Phát thầu: " + p.taskId() + " (" + (int) p.pickupX() + "," + (int) p.pickupY() + ") -> (" + (int) p.deliveryX() + "," + (int) p.deliveryY() + ")";
                }
                case TASK_BID -> {
                    TaskBidPayload p = TaskBidPayload.fromBytes(msg.getPayload());
                    detail = String.format(Locale.US, "Nộp thầu: %s (Chi phí=%.2f, Pin=%.0f%%)", p.taskId(), p.cost(), p.batteryLevel());
                }
                case TASK_AWARD -> {
                    TaskAwardPayload p = TaskAwardPayload.fromBytes(msg.getPayload());
                    detail = "Trao thầu: " + p.taskId() + " -> AGV #" + p.winnerNodeId();
                }
                case TASK_COMPLETE -> {
                    TaskCompletePayload p = TaskCompletePayload.fromBytes(msg.getPayload());
                    detail = "Hoàn thành: " + p.taskId();
                }
                case PING -> detail = "Ping RTT check";
                case PONG -> detail = "Pong Echo";
                case POSITION_UPDATE -> {
                    PositionUpdatePayload p = PositionUpdatePayload.fromBytes(msg.getPayload());
                    detail = String.format(Locale.US, "Tọa độ: (%.1f, %.1f) [%s]", p.x(), p.y(), p.state());
                }
            }
        } catch (Exception ignored) {}

        int peerCount = (robots != null) ? (int) robots.stream().filter(r -> !r.isCrashed() && r.getNodeId() != senderId).count() : 4;
        WireMessageEntry entry;
        if (receiverId < 0 || (msg.getHeader() != null && msg.getHeader().senderNodeId() > 0 && receiverId == -1)) {
            entry = WireMessageEntry.ofBroadcast(lamport, op, senderId, peerCount, detail, msg);
        } else {
            entry = WireMessageEntry.of(lamport, op, senderId, receiverId, detail, msg);
        }
        trafficLog.addEntry(entry);

        if (perNodePanel != null) {
            perNodePanel.recordPacketIn(receiverId, op);
            perNodePanel.recordPacketOut(senderId, op);
        }
    }

    private void updateDashboardTelemetry() {
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

        telemetryList.getChildren().clear();
        for (AgvRobot robot : robots) {
            telemetryList.getChildren().add(createAgvTelemetryCard(robot));
        }

        tasksListVBox.getChildren().clear();
        if (allTasks.isEmpty()) {
            Label emptyLbl = new Label("Chưa có đơn hàng nào. Bấm 'Tạo nhiệm vụ' bên dưới.");
            emptyLbl.setStyle("-fx-text-fill: #64748B; -fx-font-size: 11px;");
            tasksListVBox.getChildren().add(emptyLbl);
        } else {
            for (Task task : allTasks) {
                tasksListVBox.getChildren().add(createTaskCard(task));
            }
        }
    }

    private Node createAgvTelemetryCard(AgvRobot robot) {
        VBox card = new VBox(5);
        card.setPadding(new Insets(8, 10, 8, 10));
        boolean isSelected = robot == selectedRobot;
        card.setStyle("-fx-background-color: " + (isSelected ? "#1E293B" : "#131C2E") + "; -fx-border-color: " + (isSelected ? "#3B82F6" : "#334155") + "; -fx-border-width: 1.5px; -fx-border-radius: 6px; -fx-background-radius: 6px;");

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

        HBox details = new HBox(12);
        Label posLbl = new Label(String.format("Pos: (%d, %d)", robot.getCurrentGridPos().x(), robot.getCurrentGridPos().y()));
        posLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");

        Label batLbl = new Label(String.format("Bat: %.1f%%", robot.getBatteryLevel()));
        batLbl.setStyle("-fx-text-fill: " + (robot.getBatteryLevel() > 20 ? "#34D399" : "#F87171") + "; -fx-font-size: 10px; -fx-font-weight: bold;");

        Label clockLbl = new Label("L: " + robot.getNode().getLamportClock().getTime());
        clockLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");

        details.getChildren().addAll(posLbl, batLbl, clockLbl);

        if (robot.getWaitingIntersection() != null) {
            Label m = new Label("⏳ Đang đợi Mutex: " + robot.getWaitingIntersection());
            m.setStyle("-fx-text-fill: #FBBF24; -fx-font-size: 10px; -fx-font-weight: bold;");
            card.getChildren().addAll(top, details, m);
        } else if (robot.getHeldIntersection() != null) {
            Label m = new Label("🔒 Đang giữ Mutex: " + robot.getHeldIntersection());
            m.setStyle("-fx-text-fill: #F97316; -fx-font-size: 10px; -fx-font-weight: bold;");
            card.getChildren().addAll(top, details, m);
        } else {
            card.getChildren().addAll(top, details);
        }

        HBox actions = new HBox(6);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new Insets(2, 0, 0, 0));

        Button selectBtn = new Button(isSelected ? "Selected" : "Select");
        selectBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 9.5px; -fx-padding: 2 6 2 6;");
        selectBtn.setOnAction(e -> {
            selectedRobot = robot;
            updateDashboardTelemetry();
        });

        Button crashRebootBtn = new Button(robot.isCrashed() ? "🔄 Reboot" : "💥 Crash");
        crashRebootBtn.setStyle("-fx-background-color: " + (robot.isCrashed() ? "#2563EB" : "#7F1D1D") + "; -fx-text-fill: white; -fx-font-size: 9.5px; -fx-padding: 2 6 2 6;");
        crashRebootBtn.setOnAction(e -> {
            if (robot.isCrashed()) {
                robot.recover();
            } else {
                robot.crash();
            }
        });

        actions.getChildren().addAll(selectBtn, crashRebootBtn);
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

        String statusColor = switch (task.getStatus()) {
            case COMPLETED -> "#10B981";
            case ASSIGNED, IN_TRANSIT -> "#06B6D4";
            case AUCTIONING -> "#8B5CF6";
            case UNASSIGNED -> "#64748B";
            case CANCELLED, FAILED -> "#EF4444";
        };
        Label stBadge = new Label(task.getStatus().name());
        stBadge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9));
        stBadge.setTextFill(Color.WHITE);
        stBadge.setPadding(new Insets(1, 5, 1, 5));
        stBadge.setStyle("-fx-background-color: " + statusColor + "; -fx-background-radius: 3px;");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        top.getChildren().addAll(idLbl, sp, stBadge);

        HBox details = new HBox(12);
        Label routeLbl = new Label(String.format("Route: (%d,%d) -> (%d,%d)",
                (int) task.getPickupX(), (int) task.getPickupY(),
                (int) task.getDeliveryX(), (int) task.getDeliveryY()));
        routeLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px;");

        String assignedTo = task.getAssignedNodeId() != null ? "AGV #" + task.getAssignedNodeId() : "Chưa gán";
        Label agvLbl = new Label("Carrier: " + assignedTo);
        agvLbl.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 10px; -fx-font-weight: bold;");

        details.getChildren().addAll(routeLbl, agvLbl);
        card.getChildren().addAll(top, details);
        return card;
    }

    // =========================================================================
    // 12. DIALOGS & REPORT EXPORT
    // =========================================================================
    private void showTaskConfigDialog() {
        Dialog<Task> dialog = new Dialog<>();
        dialog.initOwner(primaryStage);
        dialog.setTitle("Tạo nhiệm vụ vận chuyển mới");
        dialog.setHeaderText("Cấu hình đơn hàng và chế độ đấu thầu phân tán CNP");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(20, 20, 10, 10));

        TextField taskIdField = new TextField("ORD-" + taskCounter.getAndIncrement());
        Spinner<Integer> pxSpinner = new Spinner<>(0, map.getWidth() - 1, 2);
        Spinner<Integer> pySpinner = new Spinner<>(0, map.getHeight() - 1, 2);
        Spinner<Integer> dxSpinner = new Spinner<>(0, map.getWidth() - 1, 20);
        Spinner<Integer> dySpinner = new Spinner<>(0, map.getHeight() - 1, 6);

        grid.add(new Label("Mã đơn hàng:"), 0, 0);
        grid.add(taskIdField, 1, 0);
        grid.add(new Label("Điểm lấy hàng:"), 0, 1);
        grid.add(new HBox(6, new Label("X:"), pxSpinner, new Label("Y:"), pySpinner), 1, 1);
        grid.add(new Label("Điểm giao hàng:"), 0, 2);
        grid.add(new HBox(6, new Label("X:"), dxSpinner, new Label("Y:"), dySpinner), 1, 2);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                Task task = new Task(taskIdField.getText().trim(), pxSpinner.getValue(), pySpinner.getValue(), dxSpinner.getValue(), dySpinner.getValue());
                allTasks.add(task);
                Optional<AgvRobot> announcer = robots.stream().filter(r -> !r.isCrashed()).findFirst();
                if (announcer.isPresent()) {
                    cnpVisualAnnouncerId = announcer.get().getNodeId();
                    cnpVisualBids.clear();
                    cnpVisualWinnerId = null;
                    cnpVisualTimestamp = System.currentTimeMillis();
                    announcer.get().getNode().announceTask(task);
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
        dialog.setTitle("Triển khai AGV Node vào Mạng Mesh");
        dialog.setHeaderText("Cấu hình thông số xe tự hành");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(20, 20, 10, 10));

        TextField idField = new TextField(String.valueOf(nextNodeId.getAndIncrement()));
        Slider batterySlider = new Slider(10, 100, 100);
        batterySlider.setShowTickLabels(true);

        grid.add(new Label("AGV Node ID:"), 0, 0);
        grid.add(idField, 1, 0);
        grid.add(new Label("Mức Pin ban đầu:"), 0, 1);
        grid.add(batterySlider, 1, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                long nodeId = Long.parseLong(idField.getText().trim());
                double battery = batterySlider.getValue();
                GridPoint spawnPos = map.getNearestChargingStation(new GridPoint(0, 0));
                if (spawnPos == null) spawnPos = new GridPoint(0, 1);
                return createAndStartRobot(nodeId, spawnPos, battery, "Pallet AGV-Custom", 1.0);
            }
            return null;
        });

        dialog.showAndWait();
    }

    private void toggleAutoDemo() {
        boolean active = autoDemoBtn.isSelected();
        autoDemoActive.set(active);
        if (active) {
            autoDemoBtn.setText("⏹ Dừng Auto Demo");
            autoDemoBtn.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 4 10 4 10; -fx-cursor: hand; -fx-background-radius: 4px;");
            scheduleNextAutoDemoTask();
        } else {
            autoDemoBtn.setText("▶ Bắt đầu demo (Auto)");
            autoDemoBtn.setStyle("-fx-background-color: #059669; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 4 10 4 10; -fx-cursor: hand; -fx-background-radius: 4px;");
            if (autoDemoFuture != null) {
                autoDemoFuture.cancel(false);
                autoDemoFuture = null;
            }
        }
    }

    private void scheduleNextAutoDemoTask() {
        if (!autoDemoActive.get()) return;
        long delayMs = 3000 + seededRandom.nextInt(2001); // 3-5 seconds
        autoDemoFuture = backgroundScheduler.schedule(() -> {
            Platform.runLater(() -> {
                if (autoDemoActive.get()) {
                    spawnRandomTask();
                    scheduleNextAutoDemoTask();
                }
            });
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void spawnRandomTask() {
        List<GridPoint> emptyPoints = map.getWalkableEmptyPoints();
        if (emptyPoints.size() < 2) return;

        Collections.shuffle(emptyPoints, seededRandom);
        GridPoint p = emptyPoints.get(0);
        GridPoint d = emptyPoints.get(1);

        String taskId = "ORD-" + taskCounter.getAndIncrement();
        Task task = new Task(taskId, p.x(), p.y(), d.x(), d.y());
        allTasks.add(task);

        List<AgvRobot> aliveRobots = robots.stream()
                .filter(r -> !r.isCrashed() && !r.isPaused())
                .toList();

        if (!aliveRobots.isEmpty()) {
            AgvRobot announcer = aliveRobots.get(seededRandom.nextInt(aliveRobots.size()));
            cnpVisualAnnouncerId = announcer.getNodeId();
            cnpVisualBids.clear();
            cnpVisualWinnerId = null;
            cnpVisualTimestamp = System.currentTimeMillis();
            log.info("Auto Demo: AGV {} chosen to announce task {}", announcer.getNodeId(), taskId);
            announcer.getNode().announceTask(task);
        }
    }

    private void recallAllToCharging() {
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

    private void exportFullReport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Lưu báo cáo hiệu năng FMS");
        chooser.setInitialFileName("fms_metrics_report.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files (*.csv)", "*.csv"));
        File file = chooser.showSaveDialog(primaryStage);
        if (file != null) {
            try {
                metricsCollector.exportCsv(file);
                File trafficFile = new File(file.getParentFile(), "wire_traffic_" + file.getName());
                trafficLog.exportCsv(trafficFile);

                Alert alert = new Alert(Alert.AlertType.INFORMATION,
                        "Đã lưu báo cáo hiệu năng thành công!\n- Metrics: " + file.getAbsolutePath() + "\n- Wire Traffic: " + trafficFile.getAbsolutePath());
                alert.setTitle("Xuất báo cáo thành công");
                alert.show();
            } catch (IOException e) {
                Alert alert = new Alert(Alert.AlertType.ERROR, "Lỗi khi lưu file: " + e.getMessage());
                alert.show();
            }
        }
    }

    private void shutdown() {
        log.info("Shutting down AGV Fleet Simulator...");
        autoDemoActive.set(false);
        if (autoDemoFuture != null) autoDemoFuture.cancel(false);
        backgroundScheduler.shutdownNow();
        if (renderLoop != null) renderLoop.stop();
        if (uiRefreshTimeline != null) uiRefreshTimeline.stop();
        if (physicsController != null) physicsController.stop();
        stopAndClearFleet();
        log.info("AGV Fleet Simulator shutdown cleanly.");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
