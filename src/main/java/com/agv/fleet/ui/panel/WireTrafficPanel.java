package com.agv.fleet.ui.panel;

import com.agv.fleet.common.protocol.Opcode;
import com.agv.fleet.ui.traffic.WireMessageEntry;
import com.agv.fleet.ui.traffic.WireTrafficLog;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
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
 * Enterprise Wire Traffic Log Panel with Two-Tier Filtering and Packet Inspector.
 * <p>
 * Key Capabilities:
 * <ul>
 *   <li><b>Two-tier buffer:</b> Business messages by default, system rate counters, toggle checkbox.</li>
 *   <li><b>Optimized ListView:</b> Reusable CellFactory, append-only, auto-scroll at bottom, true pause freeze.</li>
 *   <li><b>Opcode Badges:</b> Standard color hierarchy (MUTEX_REQUEST amber, MUTEX_REPLY green, etc.).</li>
 *   <li><b>Click-to-Inspect Detail:</b> 36-byte binary header hex dump + field breakdown + decoded JSON payload.</li>
 * </ul>
 */
public class WireTrafficPanel extends VBox {

    private final WireTrafficLog trafficLog;
    private final ListView<WireMessageEntry> listView;
    private final ObservableList<WireMessageEntry> listData = FXCollections.observableArrayList();

    private final ComboBox<String> opcodeFilterCombo;
    private final TextField nodeFilterField;
    private final TextField searchTextField;
    private final CheckBox showSystemCheckBox;
    private final Button pauseResumeBtn;
    private final Button exportCsvBtn;
    private final Label rowCountLabel;
    private final Label systemRatesLabel;

    // Detail Panel Controls
    private final Label detailTitleLabel;
    private final TextArea hexDumpArea;
    private final TextArea headerBreakdownArea;
    private final TextArea payloadJsonArea;

    private int lastLoadedSize = 0;

    public WireTrafficPanel(WireTrafficLog trafficLog) {
        super(6);
        this.trafficLog = trafficLog;
        setPadding(new Insets(8));
        setStyle("-fx-background-color: #0F172A; -fx-border-color: #1E293B; -fx-border-width: 1px; -fx-border-radius: 8px; -fx-background-radius: 8px;");

        // --- ROW 1: Filter & Action Bar ---
        HBox filterBar = new HBox(6);
        filterBar.setAlignment(Pos.CENTER_LEFT);

        Label filterLbl = new Label("Opcode:");
        filterLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");

        opcodeFilterCombo = new ComboBox<>();
        opcodeFilterCombo.getItems().add("TẤT CẢ (ALL)");
        for (Opcode op : Opcode.values()) {
            opcodeFilterCombo.getItems().add(op.name());
        }
        opcodeFilterCombo.setValue("TẤT CẢ (ALL)");
        opcodeFilterCombo.setStyle("-fx-background-color: #1E293B; -fx-text-fill: white; -fx-font-size: 10px;");
        Tooltip.install(opcodeFilterCombo, new Tooltip("Lọc theo mã Opcode"));

        Label nodeLbl = new Label("Node:");
        nodeLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");

        nodeFilterField = new TextField();
        nodeFilterField.setPromptText("Node ID");
        nodeFilterField.setPrefWidth(55);
        nodeFilterField.setStyle("-fx-background-color: #1E293B; -fx-text-fill: white; -fx-font-size: 10px;");

        searchTextField = new TextField();
        searchTextField.setPromptText("Tìm kiếm...");
        searchTextField.setPrefWidth(90);
        searchTextField.setStyle("-fx-background-color: #1E293B; -fx-text-fill: white; -fx-font-size: 10px;");

        showSystemCheckBox = new CheckBox("Hiện tin hệ thống");
        showSystemCheckBox.setSelected(false);
        showSystemCheckBox.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 10px;");
        Tooltip.install(showSystemCheckBox, new Tooltip("Bật để hiển thị gói Heartbeat, Position Update, Ping/Pong"));

        pauseResumeBtn = new Button("⏸ Tạm dừng");
        pauseResumeBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand; -fx-background-radius: 4px;");
        pauseResumeBtn.setOnAction(e -> togglePause());

        exportCsvBtn = new Button("📥 CSV");
        exportCsvBtn.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand; -fx-background-radius: 4px;");
        exportCsvBtn.setOnAction(e -> handleExportCsv());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        rowCountLabel = new Label("0 dòng");
        rowCountLabel.setStyle("-fx-text-fill: #64748B; -fx-font-size: 10px; -fx-font-weight: bold;");

        filterBar.getChildren().addAll(filterLbl, opcodeFilterCombo, nodeLbl, nodeFilterField, searchTextField,
                showSystemCheckBox, pauseResumeBtn, exportCsvBtn, spacer, rowCountLabel);

        // --- ROW 2: System Message Rate Strip ---
        HBox systemRateBar = new HBox(6);
        systemRateBar.setAlignment(Pos.CENTER_LEFT);
        systemRateBar.setStyle("-fx-background-color: #1E293B; -fx-padding: 3 8 3 8; -fx-background-radius: 4px;");

        Label rateIcon = new Label("⚡ Tốc độ hệ thống:");
        rateIcon.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 10px; -fx-font-weight: bold;");

        systemRatesLabel = new Label("HEARTBEAT: 0 msgs/s | POSITION: 0 msgs/s | PING/PONG: 0 msgs/s");
        systemRatesLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px; -fx-font-family: monospace;");

        systemRateBar.getChildren().addAll(rateIcon, systemRatesLabel);

        // --- ROW 3: Master-Detail SplitPane ---
        SplitPane splitPane = new SplitPane();
        splitPane.setOrientation(Orientation.VERTICAL);
        splitPane.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        // --- Master ListView ---
        listView = new ListView<>(listData);
        listView.setStyle("-fx-background-color: #111827; -fx-border-color: #1E293B; -fx-border-radius: 4px;");
        listView.setCellFactory(lv -> new WireTrafficCell());

        // --- Detail Panel ---
        VBox detailBox = new VBox(4);
        detailBox.setPadding(new Insets(6));
        detailBox.setStyle("-fx-background-color: #090D16; -fx-border-color: #1E293B; -fx-border-radius: 4px;");

        detailTitleLabel = new Label("🔍 CHI TIẾT GÓI TIN (Bấm vào 1 dòng ở trên để inspect)");
        detailTitleLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
        detailTitleLabel.setTextFill(Color.web("#38BDF8"));

        Label hexTitle = new Label("Raw Header (36 Bytes Hex):");
        hexTitle.setStyle("-fx-text-fill: #64748B; -fx-font-size: 9px; -fx-font-weight: bold;");

        hexDumpArea = new TextArea();
        hexDumpArea.setEditable(false);
        hexDumpArea.setPrefRowCount(2);
        hexDumpArea.setStyle("-fx-control-inner-background: #0B1120; -fx-text-fill: #F59E0B; -fx-font-family: monospace; -fx-font-size: 10px;");

        Label breakdownTitle = new Label("Header Fields & Payload JSON:");
        breakdownTitle.setStyle("-fx-text-fill: #64748B; -fx-font-size: 9px; -fx-font-weight: bold;");

        HBox detailColumns = new HBox(6);
        VBox.setVgrow(detailColumns, Priority.ALWAYS);

        headerBreakdownArea = new TextArea();
        headerBreakdownArea.setEditable(false);
        headerBreakdownArea.setPrefRowCount(6);
        headerBreakdownArea.setPromptText("Header breakdown...");
        headerBreakdownArea.setStyle("-fx-control-inner-background: #0B1120; -fx-text-fill: #94A3B8; -fx-font-family: monospace; -fx-font-size: 9.5px;");
        HBox.setHgrow(headerBreakdownArea, Priority.ALWAYS);

        payloadJsonArea = new TextArea();
        payloadJsonArea.setEditable(false);
        payloadJsonArea.setPrefRowCount(6);
        payloadJsonArea.setPromptText("Decoded Payload JSON...");
        payloadJsonArea.setStyle("-fx-control-inner-background: #0B1120; -fx-text-fill: #34D399; -fx-font-family: monospace; -fx-font-size: 9.5px;");
        HBox.setHgrow(payloadJsonArea, Priority.ALWAYS);

        detailColumns.getChildren().addAll(headerBreakdownArea, payloadJsonArea);
        detailBox.getChildren().addAll(detailTitleLabel, hexTitle, hexDumpArea, breakdownTitle, detailColumns);

        splitPane.getItems().addAll(listView, detailBox);
        splitPane.setDividerPositions(0.65);

        // Listen for item selection to populate details
        listView.getSelectionModel().selectedItemProperty().addListener((obs, oldV, entry) -> {
            if (entry != null) {
                detailTitleLabel.setText(String.format("🔍 PACKET INSPECTION: %s [%s] (Lamport=%d)",
                        entry.opcode() != null ? entry.opcode().name() : "UNKNOWN",
                        entry.getRouteString(),
                        entry.lamportTimestamp()));
                hexDumpArea.setText(entry.toHexDump());
                headerBreakdownArea.setText(entry.getHeaderExplanation());
                payloadJsonArea.setText(entry.getDecodedPayloadJson());
            }
        });

        // Event listeners for filtering
        opcodeFilterCombo.setOnAction(e -> forceFullReload());
        nodeFilterField.textProperty().addListener((obs, oldV, newV) -> forceFullReload());
        searchTextField.textProperty().addListener((obs, oldV, newV) -> forceFullReload());
        showSystemCheckBox.setOnAction(e -> forceFullReload());

        getChildren().addAll(filterBar, systemRateBar, splitPane);
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

    private boolean isScrollAtBottom() {
        ScrollBar scrollBar = (ScrollBar) listView.lookup(".scroll-bar:vertical");
        if (scrollBar == null) return true;
        return scrollBar.getValue() >= (scrollBar.getMax() - 0.08);
    }

    public void forceFullReload() {
        lastLoadedSize = 0;
        refreshView();
    }

    /**
     * Refreshes the view efficiently. Appends new items without clearing entire list
     * unless filters changed. Updates rate counters.
     */
    public void refreshView() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refreshView);
            return;
        }

        // Always update system rate text
        systemRatesLabel.setText(trafficLog.getSystemRateString());

        if (trafficLog.isPaused()) {
            return; // View frozen while paused
        }

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

        boolean showSystem = showSystemCheckBox.isSelected();
        String searchText = searchTextField.getText();

        List<WireMessageEntry> filtered = trafficLog.getFilteredEntries(opFilter, nodeFilter, showSystem, searchText);
        int currentSize = filtered.size();

        boolean atBottom = isScrollAtBottom();

        if (lastLoadedSize == 0 || currentSize < lastLoadedSize || listData.size() != lastLoadedSize) {
            // Full reload
            listData.setAll(filtered);
        } else if (currentSize > lastLoadedSize) {
            // Append-only
            List<WireMessageEntry> newItems = filtered.subList(lastLoadedSize, currentSize);
            listData.addAll(newItems);
        }
        lastLoadedSize = currentSize;

        rowCountLabel.setText(currentSize + " dòng");

        if (atBottom && !listData.isEmpty()) {
            listView.scrollTo(listData.size() - 1);
        }
    }

    /**
     * Custom cell with opcode badges and clean monospace layout.
     */
    private static class WireTrafficCell extends ListCell<WireMessageEntry> {
        private final HBox root = new HBox(6);
        private final Label timeLabel = new Label();
        private final Label lamportLabel = new Label();
        private final Label opcodeBadge = new Label();
        private final Label routeLabel = new Label();
        private final Label detailLabel = new Label();

        public WireTrafficCell() {
            root.setAlignment(Pos.CENTER_LEFT);
            root.setPadding(new Insets(2, 4, 2, 4));

            timeLabel.setStyle("-fx-text-fill: #64748B; -fx-font-size: 9.5px; -fx-font-family: monospace;");
            timeLabel.setPrefWidth(70);

            lamportLabel.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 9.5px; -fx-font-family: monospace;");
            lamportLabel.setPrefWidth(45);

            opcodeBadge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9.0));
            opcodeBadge.setTextFill(Color.WHITE);
            opcodeBadge.setPrefWidth(105);
            opcodeBadge.setAlignment(Pos.CENTER);

            routeLabel.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 9.5px; -fx-font-weight: bold;");
            routeLabel.setPrefWidth(125);

            detailLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 9.5px;");
            HBox.setHgrow(detailLabel, Priority.ALWAYS);

            root.getChildren().addAll(timeLabel, lamportLabel, opcodeBadge, routeLabel, detailLabel);
        }

        @Override
        protected void updateItem(WireMessageEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setText(null);
                setGraphic(null);
                setStyle("-fx-background-color: transparent;");
            } else {
                timeLabel.setText(entry.timeStr());
                lamportLabel.setText("L=" + entry.lamportTimestamp());

                String opName = entry.opcode() != null ? entry.opcode().name() : "UNKNOWN";
                opcodeBadge.setText(opName);
                String badgeBg = getBadgeColor(entry.opcode());
                opcodeBadge.setStyle("-fx-background-color: " + badgeBg + "; -fx-background-radius: 3px; -fx-padding: 1 4 1 4;");

                routeLabel.setText(entry.getRouteString());
                detailLabel.setText(entry.detail());

                setText(null);
                setGraphic(root);
                setStyle("-fx-background-color: transparent; -fx-border-color: #1E293B; -fx-border-width: 0 0 1px 0;");
            }
        }

        private String getBadgeColor(Opcode opcode) {
            if (opcode == null) return "#475569";
            return switch (opcode) {
                case MUTEX_REQUEST -> "#D97706"; // Amber / vàng cam
                case MUTEX_REPLY -> "#16A34A";   // Green / xanh lá
                case MUTEX_RELEASE -> "#64748B"; // Slate gray / xám
                case TASK_ANNOUNCE -> "#2563EB"; // Blue / lam
                case TASK_BID -> "#9333EA";      // Purple / tím
                case TASK_AWARD -> "#DC2626";    // Brick Red / đỏ gạch
                case TASK_COMPLETE -> "#0D9488"; // Teal / ngọc bích
                case HEARTBEAT -> "#059669";     // Dark green
                case POSITION_UPDATE -> "#0891B2";// Cyan
                case PING, PONG -> "#D946EF";     // Magenta
                case PEER_DISCOVERY -> "#4F46E5";// Indigo
            };
        }
    }
}
