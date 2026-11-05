package com.agv.fleet.ui.panel;

import com.agv.fleet.common.protocol.Opcode;
import com.agv.fleet.ui.traffic.WireMessageEntry;
import com.agv.fleet.ui.traffic.WireTrafficLog;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Enterprise Wire Traffic Log Panel.
 * <p>
 * Displays real-time binary mesh packet traffic with:
 * <ul>
 *   <li>Columns: Time, Lamport Clock, Opcode (color-badged), Route (From -> To), Detail</li>
 *   <li>Opcode filter & Node ID filter</li>
 *   <li>Pause / Resume control</li>
 *   <li>Export to CSV button</li>
 * </ul>
 */
public class WireTrafficPanel extends VBox {

    private final WireTrafficLog trafficLog;
    private final TableView<WireMessageEntry> tableView;
    private final ObservableList<WireMessageEntry> tableData = FXCollections.observableArrayList();

    private final ComboBox<String> opcodeFilterCombo;
    private final TextField nodeFilterField;
    private final Button pauseResumeBtn;
    private final Button exportCsvBtn;
    private final Label rowCountLabel;

    public WireTrafficPanel(WireTrafficLog trafficLog) {
        super(8);
        this.trafficLog = trafficLog;
        setPadding(new Insets(10));
        setStyle("-fx-background-color: #0F172A; -fx-border-color: #1E293B; -fx-border-width: 1px; -fx-border-radius: 8px; -fx-background-radius: 8px;");

        // Top Filter Bar
        HBox filterBar = new HBox(8);
        filterBar.setAlignment(Pos.CENTER_LEFT);

        Label filterLbl = new Label("Lọc Opcode:");
        filterLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");

        opcodeFilterCombo = new ComboBox<>();
        opcodeFilterCombo.getItems().add("TẤT CẢ (ALL)");
        for (Opcode op : Opcode.values()) {
            opcodeFilterCombo.getItems().add(op.name());
        }
        opcodeFilterCombo.setValue("TẤT CẢ (ALL)");
        opcodeFilterCombo.setStyle("-fx-background-color: #1E293B; -fx-text-fill: white; -fx-font-size: 10px;");
        Tooltip.install(opcodeFilterCombo, new Tooltip("Lọc thông điệp theo mã Opcode"));

        Label nodeLbl = new Label("Node:");
        nodeLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");

        nodeFilterField = new TextField();
        nodeFilterField.setPromptText("Node ID...");
        nodeFilterField.setPrefWidth(60);
        nodeFilterField.setStyle("-fx-background-color: #1E293B; -fx-text-fill: white; -fx-font-size: 10px;");
        Tooltip.install(nodeFilterField, new Tooltip("Lọc thông điệp liên quan đến Node ID cụ thể"));

        pauseResumeBtn = new Button("⏸ Tạm dừng");
        pauseResumeBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand; -fx-background-radius: 4px;");
        Tooltip.install(pauseResumeBtn, new Tooltip("Tạm dừng / Tiếp tục ghi nhận gói tin wire traffic"));
        pauseResumeBtn.setOnAction(e -> togglePause());

        exportCsvBtn = new Button("📥 Xuất CSV");
        exportCsvBtn.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand; -fx-background-radius: 4px;");
        Tooltip.install(exportCsvBtn, new Tooltip("Xuất 2000 dòng log wire traffic ra file CSV"));
        exportCsvBtn.setOnAction(e -> handleExportCsv());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        rowCountLabel = new Label("0 / 2000 dòng");
        rowCountLabel.setStyle("-fx-text-fill: #64748B; -fx-font-size: 10px;");

        filterBar.getChildren().addAll(filterLbl, opcodeFilterCombo, nodeLbl, nodeFilterField, pauseResumeBtn, exportCsvBtn, spacer, rowCountLabel);

        // Setup TableView
        tableView = new TableView<>(tableData);
        tableView.setStyle("-fx-background-color: #111827; -fx-border-color: #1E293B; -fx-border-radius: 4px;");
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(tableView, Priority.ALWAYS);

        TableColumn<WireMessageEntry, String> timeCol = new TableColumn<>("Thời gian");
        timeCol.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().timeStr()));
        timeCol.setPrefWidth(85);

        TableColumn<WireMessageEntry, Long> lamportCol = new TableColumn<>("Lamport");
        lamportCol.setCellValueFactory(f -> new SimpleLongProperty(f.getValue().lamportTimestamp()).asObject());
        lamportCol.setPrefWidth(65);

        TableColumn<WireMessageEntry, String> opcodeCol = new TableColumn<>("Opcode");
        opcodeCol.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().opcode() != null ? f.getValue().opcode().name() : ""));
        opcodeCol.setPrefWidth(120);
        opcodeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                } else {
                    setText(item);
                    setTextFill(Color.WHITE);
                    setFont(Font.font("Segoe UI", FontWeight.BOLD, 9.5));
                    String badgeBg = getOpcodeBadgeColor(item);
                    setStyle("-fx-background-color: " + badgeBg + "; -fx-background-radius: 3px; -fx-padding: 2 6 2 6; -fx-alignment: center;");
                }
            }
        });

        TableColumn<WireMessageEntry, String> routeCol = new TableColumn<>("From -> To");
        routeCol.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().getRouteString()));
        routeCol.setPrefWidth(125);

        TableColumn<WireMessageEntry, String> detailCol = new TableColumn<>("Chi tiết (Cell / Task)");
        detailCol.setCellValueFactory(f -> new SimpleStringProperty(f.getValue().detail()));
        detailCol.setPrefWidth(160);

        tableView.getColumns().addAll(timeCol, lamportCol, opcodeCol, routeCol, detailCol);

        // Filter event listeners
        opcodeFilterCombo.setOnAction(e -> refreshView());
        nodeFilterField.textProperty().addListener((obs, oldV, newV) -> refreshView());

        getChildren().addAll(filterBar, tableView);
    }

    private String getOpcodeBadgeColor(String opcodeName) {
        if (opcodeName.startsWith("MUTEX")) {
            return "#EA580C"; // Orange
        } else if (opcodeName.startsWith("TASK")) {
            return "#6366F1"; // Indigo
        } else if (opcodeName.equals("HEARTBEAT") || opcodeName.equals("PEER_DISCOVERY")) {
            return "#059669"; // Green
        } else if (opcodeName.equals("PING") || opcodeName.equals("PONG")) {
            return "#D946EF"; // Magenta
        } else if (opcodeName.equals("POSITION_UPDATE")) {
            return "#0891B2"; // Teal
        }
        return "#475569";
    }

    private void togglePause() {
        boolean nextState = !trafficLog.isPaused();
        trafficLog.setPaused(nextState);
        if (nextState) {
            pauseResumeBtn.setText("▶ Tiếp tục");
            pauseResumeBtn.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand; -fx-background-radius: 4px;");
        } else {
            pauseResumeBtn.setText("⏸ Tạm dừng");
            pauseResumeBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand; -fx-background-radius: 4px;");
        }
    }

    private void handleExportCsv() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Xuất Wire Traffic Log ra CSV");
        fileChooser.setInitialFileName("wire_traffic_log.csv");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files (*.csv)", "*.csv"));
        File file = fileChooser.showSaveDialog(getScene() != null ? getScene().getWindow() : null);
        if (file != null) {
            try {
                trafficLog.exportCsv(file);
                Alert alert = new Alert(Alert.AlertType.INFORMATION, "Đã xuất " + trafficLog.size() + " bản ghi vào: " + file.getAbsolutePath());
                alert.setTitle("Xuất CSV thành công");
                alert.setHeaderText(null);
                alert.show();
            } catch (IOException ex) {
                Alert alert = new Alert(Alert.AlertType.ERROR, "Lỗi khi ghi file: " + ex.getMessage());
                alert.show();
            }
        }
    }

    public void refreshView() {
        String selOp = opcodeFilterCombo.getValue();
        Opcode opFilter = null;
        if (selOp != null && !selOp.contains("TẤT CẢ")) {
            try {
                opFilter = Opcode.valueOf(selOp);
            } catch (Exception ignored) {}
        }

        Long nodeFilter = null;
        String nodeText = nodeFilterField.getText();
        if (nodeText != null && !nodeText.isBlank()) {
            try {
                nodeFilter = Long.parseLong(nodeText.trim());
            } catch (Exception ignored) {}
        }

        List<WireMessageEntry> filtered = trafficLog.getFilteredEntries(opFilter, nodeFilter);
        tableData.setAll(filtered);
        rowCountLabel.setText(filtered.size() + " / " + trafficLog.size() + " dòng");
        if (!tableData.isEmpty()) {
            tableView.scrollTo(tableData.size() - 1);
        }
    }
}
