package com.agv.fleet.ui.panel;

import com.agv.fleet.common.protocol.Opcode;
import com.agv.fleet.core.agent.AgentState;
import com.agv.fleet.core.agent.AgvRobot;
import com.agv.fleet.core.agent.Task;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Enterprise Single Node Deep-Dive Inspector Panel.
 * <p>
 * Displays per-vehicle telemetry:
 * <ul>
 *   <li>Logical Lamport Clock, Agent State, Battery Level, Physical Coordinates.</li>
 *   <li>Current Task Assignment &amp; Held/Waiting Intersection Mutex Cells.</li>
 *   <li>Last 20 chronological lifecycle &amp; protocol events for the selected AGV.</li>
 *   <li>Inbound &amp; Outbound packet counters categorized by Opcode.</li>
 * </ul>
 */
public class PerNodePanel extends VBox {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Supplier<List<AgvRobot>> robotsSupplier;
    private final ComboBox<String> agvSelectorCombo;

    // Node state labels
    private final Label lamportLabel = new Label("L = 0");
    private final Label stateBadge = new Label("IDLE");
    private final ProgressBar batteryBar = new ProgressBar(1.0);
    private final Label batteryPercentLabel = new Label("100.0%");
    private final Label coordLabel = new Label("(0, 0)");
    private final Label currentTaskLabel = new Label("Không có");
    private final Label heldMutexLabel = new Label("Không giữ");
    private final Label waitingMutexLabel = new Label("Không chờ");

    // Events list
    private final ListView<String> eventsListView = new ListView<>();
    private final Map<Long, Deque<String>> nodeEventsMap = new ConcurrentHashMap<>();

    // Opcode In/Out counters: nodeId -> Opcode -> in/out
    private final Map<Long, Map<Opcode, LongAdder>> inboundCounters = new ConcurrentHashMap<>();
    private final Map<Long, Map<Opcode, LongAdder>> outboundCounters = new ConcurrentHashMap<>();
    private final GridPane opcodeGrid = new GridPane();

    public PerNodePanel(Supplier<List<AgvRobot>> robotsSupplier) {
        super(8);
        this.robotsSupplier = robotsSupplier;
        setPadding(new Insets(10));
        setStyle("-fx-background-color: #0F172A; -fx-border-color: #1E293B; -fx-border-width: 1px; -fx-border-radius: 8px; -fx-background-radius: 8px;");

        // Top selector bar
        HBox topBar = new HBox(8);
        topBar.setAlignment(Pos.CENTER_LEFT);

        Label selectLbl = new Label("Chọn xe AGV:");
        selectLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px; -fx-font-weight: bold;");

        agvSelectorCombo = new ComboBox<>();
        agvSelectorCombo.setStyle("-fx-background-color: #1E293B; -fx-text-fill: white; -fx-font-size: 11px;");
        agvSelectorCombo.setOnAction(e -> refresh());

        topBar.getChildren().addAll(selectLbl, agvSelectorCombo);

        // Telemetry Summary Card
        GridPane infoGrid = new GridPane();
        infoGrid.setHgap(8);
        infoGrid.setVgap(6);
        infoGrid.setStyle("-fx-background-color: #111827; -fx-padding: 8; -fx-background-radius: 6px; -fx-border-color: #1E293B; -fx-border-radius: 6px;");

        lamportLabel.setStyle("-fx-text-fill: #38BDF8; -fx-font-weight: bold; -fx-font-family: monospace;");
        stateBadge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
        stateBadge.setTextFill(Color.WHITE);
        stateBadge.setStyle("-fx-background-color: #059669; -fx-padding: 2 6 2 6; -fx-background-radius: 4px;");

        batteryBar.setPrefWidth(90);
        batteryBar.setStyle("-fx-accent: #10B981;");
        batteryPercentLabel.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 10px; -fx-font-weight: bold;");
        HBox batBox = new HBox(4, batteryBar, batteryPercentLabel);
        batBox.setAlignment(Pos.CENTER_LEFT);

        coordLabel.setStyle("-fx-text-fill: #FDE047; -fx-font-weight: bold; -fx-font-family: monospace;");
        currentTaskLabel.setStyle("-fx-text-fill: #A78BFA; -fx-font-weight: bold;");
        heldMutexLabel.setStyle("-fx-text-fill: #F87171; -fx-font-weight: bold;");
        waitingMutexLabel.setStyle("-fx-text-fill: #FBBF24; -fx-font-weight: bold;");

        infoGrid.add(createFieldLabel("Lamport Clock:"), 0, 0);
        infoGrid.add(lamportLabel, 1, 0);
        infoGrid.add(createFieldLabel("Trạng thái:"), 2, 0);
        infoGrid.add(stateBadge, 3, 0);

        infoGrid.add(createFieldLabel("Pin hiện tại:"), 0, 1);
        infoGrid.add(batBox, 1, 1);
        infoGrid.add(createFieldLabel("Tọa độ ô:"), 2, 1);
        infoGrid.add(coordLabel, 3, 1);

        infoGrid.add(createFieldLabel("Nhiệm vụ:"), 0, 2);
        infoGrid.add(currentTaskLabel, 1, 2);
        infoGrid.add(createFieldLabel("Giữ Mutex:"), 2, 2);
        infoGrid.add(heldMutexLabel, 3, 2);

        infoGrid.add(createFieldLabel("Đang chờ Mutex:"), 0, 3);
        infoGrid.add(waitingMutexLabel, 1, 3);

        // Section: 20 Recent Events
        Label eventsTitle = new Label("20 SỰ KIỆN GẦN NHẤT CỦA XE:");
        eventsTitle.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px; -fx-font-weight: bold;");

        eventsListView.setPrefHeight(130);
        eventsListView.setStyle("-fx-background-color: #111827; -fx-border-color: #1E293B; -fx-border-radius: 4px;");
        eventsListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("-fx-background-color: transparent;");
                } else {
                    setText(item);
                    setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 9.5px; -fx-font-family: monospace; -fx-background-color: transparent;");
                }
            }
        });

        // Section: Opcode In/Out Counters
        Label opcodeTitle = new Label("BỘ ĐẾM GÓI TIN VÀO / RA THEO OPCODE:");
        opcodeTitle.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px; -fx-font-weight: bold;");

        opcodeGrid.setHgap(10);
        opcodeGrid.setVgap(3);
        opcodeGrid.setStyle("-fx-background-color: #111827; -fx-padding: 8; -fx-background-radius: 4px;");

        VBox contentBox = new VBox(8, infoGrid, eventsTitle, eventsListView, opcodeTitle, opcodeGrid);
        ScrollPane scrollPane = new ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getChildren().addAll(topBar, scrollPane);
    }

    private Label createFieldLabel(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill: #64748B; -fx-font-size: 10px;");
        return lbl;
    }

    /**
     * Records a lifecycle or protocol event for a specific AGV node.
     */
    public void recordEvent(long nodeId, String description) {
        String eventStr = LocalTime.now().format(TIME_FMT) + " - " + description;
        Deque<String> deque = nodeEventsMap.computeIfAbsent(nodeId, k -> new ConcurrentLinkedDeque<>());
        deque.addFirst(eventStr);
        while (deque.size() > 20) {
            deque.pollLast();
        }
    }

    public void recordPacketIn(long nodeId, Opcode opcode) {
        if (opcode == null) return;
        inboundCounters.computeIfAbsent(nodeId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(opcode, k -> new LongAdder()).increment();
    }

    public void recordPacketOut(long nodeId, Opcode opcode) {
        if (opcode == null) return;
        outboundCounters.computeIfAbsent(nodeId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(opcode, k -> new LongAdder()).increment();
    }

    public void refresh() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refresh);
            return;
        }

        List<AgvRobot> robots = robotsSupplier.get();
        if (robots == null || robots.isEmpty()) return;

        // Populate combo if items count changed
        if (agvSelectorCombo.getItems().size() != robots.size()) {
            String currentSelection = agvSelectorCombo.getValue();
            agvSelectorCombo.getItems().clear();
            for (AgvRobot r : robots) {
                agvSelectorCombo.getItems().add("AGV #" + r.getNodeId() + " (" + r.getModelName() + ")");
            }
            if (currentSelection != null && agvSelectorCombo.getItems().contains(currentSelection)) {
                agvSelectorCombo.setValue(currentSelection);
            } else if (!agvSelectorCombo.getItems().isEmpty()) {
                agvSelectorCombo.setValue(agvSelectorCombo.getItems().get(0));
            }
        }

        String sel = agvSelectorCombo.getValue();
        if (sel == null) return;

        long selectedId = 1L;
        try {
            selectedId = Long.parseLong(sel.split(" ")[0].replace("AGV", "").replace("#", "").trim());
        } catch (Exception ignored) {}

        long finalSelectedId = selectedId;
        AgvRobot robot = robots.stream().filter(r -> r.getNodeId() == finalSelectedId).findFirst().orElse(null);
        if (robot == null) return;

        // Update basic info
        lamportLabel.setText("L = " + robot.getNode().getLamportClock().getTime());

        AgentState state = robot.getState();
        stateBadge.setText(state.name());
        stateBadge.setStyle("-fx-background-color: " + getStateColor(state) + "; -fx-padding: 2 6 2 6; -fx-background-radius: 4px;");

        double bat = robot.getBatteryLevel();
        batteryBar.setProgress(bat / 100.0);
        batteryPercentLabel.setText(String.format(Locale.US, "%.1f%%", bat));
        if (bat < 20.0) {
            batteryBar.setStyle("-fx-accent: #EF4444;");
        } else if (bat < 50.0) {
            batteryBar.setStyle("-fx-accent: #F59E0B;");
        } else {
            batteryBar.setStyle("-fx-accent: #10B981;");
        }

        coordLabel.setText(String.format("(%d, %d)", robot.getCurrentGridPos().x(), robot.getCurrentGridPos().y()));

        Task task = robot.getCurrentTask();
        if (task != null) {
            currentTaskLabel.setText(String.format("%s [%s]", task.getTaskId(), task.getStatus()));
        } else {
            currentTaskLabel.setText("Không có");
        }

        String held = robot.getHeldIntersection();
        heldMutexLabel.setText(held != null ? held : "Không giữ");

        String waiting = robot.getWaitingIntersection();
        waitingMutexLabel.setText(waiting != null ? waiting : "Không chờ");

        // Update events list
        Deque<String> events = nodeEventsMap.get(selectedId);
        if (events != null) {
            eventsListView.getItems().setAll(events);
        } else {
            eventsListView.getItems().clear();
        }

        // Render In/Out Opcode Counters
        opcodeGrid.getChildren().clear();
        Label opH = new Label("Opcode");
        opH.setStyle("-fx-text-fill: #64748B; -fx-font-size: 9.5px; -fx-font-weight: bold;");
        Label inH = new Label("Gói Vào (IN)");
        inH.setStyle("-fx-text-fill: #10B981; -fx-font-size: 9.5px; -fx-font-weight: bold;");
        Label outH = new Label("Gói Ra (OUT)");
        outH.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 9.5px; -fx-font-weight: bold;");

        opcodeGrid.add(opH, 0, 0);
        opcodeGrid.add(inH, 1, 0);
        opcodeGrid.add(outH, 2, 0);

        Map<Opcode, LongAdder> inMap = inboundCounters.getOrDefault(selectedId, Collections.emptyMap());
        Map<Opcode, LongAdder> outMap = outboundCounters.getOrDefault(selectedId, Collections.emptyMap());

        int row = 1;
        for (Opcode op : Opcode.values()) {
            long inCount = inMap.containsKey(op) ? inMap.get(op).sum() : 0L;
            long outCount = outMap.containsKey(op) ? outMap.get(op).sum() : 0L;

            if (inCount == 0 && outCount == 0) continue; // Skip zero-traffic opcodes for clarity

            Label opLbl = new Label(op.name());
            opLbl.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 9px; -fx-font-family: monospace;");

            Label inLbl = new Label(String.valueOf(inCount));
            inLbl.setStyle("-fx-text-fill: #34D399; -fx-font-size: 9px; -fx-font-family: monospace;");

            Label outLbl = new Label(String.valueOf(outCount));
            outLbl.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 9px; -fx-font-family: monospace;");

            opcodeGrid.add(opLbl, 0, row);
            opcodeGrid.add(inLbl, 1, row);
            opcodeGrid.add(outLbl, 2, row);
            row++;
        }
    }

    private String getStateColor(AgentState state) {
        if (state == null) return "#475569";
        return switch (state) {
            case IDLE -> "#059669";
            case MOVING -> "#0284C7";
            case WAITING_MUTEX -> "#D97706";
            case BUSY -> "#7C3AED";
            case LOW_BATTERY_CHARGING -> "#E11D48";
            case CRASHED -> "#DC2626";
            default -> "#475569";
        };
    }
}
