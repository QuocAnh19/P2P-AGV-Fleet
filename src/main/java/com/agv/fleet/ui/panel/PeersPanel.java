package com.agv.fleet.ui.panel;

import com.agv.fleet.core.agent.AgvRobot;
import com.agv.fleet.network.mesh.PeerManager;
import com.agv.fleet.network.mesh.PeerSession;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Enterprise P2P Peers Mesh Diagnostics Panel.
 * <p>
 * Features:
 * <ul>
 *   <li>NxN Round-Trip Time (RTT) diagnostic ping matrix refreshed every 2 seconds.</li>
 *   <li>Per-peer heartbeat age tracking and link health status (OK / Trễ / Chết).</li>
 *   <li>Global mesh latency percentile telemetry (p50, p95, average RTT).</li>
 * </ul>
 */
public class PeersPanel extends VBox {

    private final Supplier<List<AgvRobot>> robotsSupplier;
    private final Map<String, Double> rttMatrixMs = new ConcurrentHashMap<>();

    private final Label p50Label = new Label("p50: -- ms");
    private final Label p95Label = new Label("p95: -- ms");
    private final Label avgRttLabel = new Label("Trung bình: -- ms");
    private final Label activeLinksLabel = new Label("Liên kết: 0");

    private final GridPane matrixGrid = new GridPane();
    private final VBox linksList = new VBox(4);

    private long lastPingTime = 0;

    public PeersPanel(Supplier<List<AgvRobot>> robotsSupplier) {
        super(8);
        this.robotsSupplier = robotsSupplier;
        setPadding(new Insets(10));
        setStyle("-fx-background-color: #0F172A; -fx-border-color: #1E293B; -fx-border-width: 1px; -fx-border-radius: 8px; -fx-background-radius: 8px;");

        // Top Summary Header
        VBox headerBox = new VBox(6);
        headerBox.setStyle("-fx-background-color: #1E293B; -fx-padding: 8; -fx-background-radius: 6px;");

        Label title = new Label("🌐 MA TRẬN P2P MESH & GIÁM SÁT LIÊN KẾT");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        title.setTextFill(Color.web("#38BDF8"));

        HBox statsRow = new HBox(12);
        statsRow.setAlignment(Pos.CENTER_LEFT);

        p50Label.setStyle("-fx-text-fill: #34D399; -fx-font-weight: bold; -fx-font-size: 11px;");
        p95Label.setStyle("-fx-text-fill: #F59E0B; -fx-font-weight: bold; -fx-font-size: 11px;");
        avgRttLabel.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 11px;");
        activeLinksLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");

        statsRow.getChildren().addAll(p50Label, p95Label, avgRttLabel, activeLinksLabel);
        headerBox.getChildren().addAll(title, statsRow);

        // Center Matrix ScrollPane
        VBox scrollContent = new VBox(10);
        scrollContent.setPadding(new Insets(4));

        Label matrixTitle = new Label("MA TRẬN RTT PING (NxN) [Đo chu kỳ 2s]:");
        matrixTitle.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px; -fx-font-weight: bold;");

        matrixGrid.setHgap(4);
        matrixGrid.setVgap(4);
        matrixGrid.setStyle("-fx-background-color: #111827; -fx-padding: 8; -fx-background-radius: 4px;");

        Label linksTitle = new Label("TRẠNG THÁI LIÊN KẾT & TUỔI HEARTBEAT:");
        linksTitle.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 10px; -fx-font-weight: bold;");

        linksList.setStyle("-fx-background-color: #111827; -fx-padding: 8; -fx-background-radius: 4px;");

        scrollContent.getChildren().addAll(matrixTitle, matrixGrid, linksTitle, linksList);

        ScrollPane scrollPane = new ScrollPane(scrollContent);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getChildren().addAll(headerBox, scrollPane);
    }

    /**
     * Executes ping checks and refreshes UI.
     * Pings are dispatched every 2000 ms.
     */
    public void refresh() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refresh);
            return;
        }

        List<AgvRobot> robots = robotsSupplier.get();
        if (robots == null || robots.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastPingTime >= 2000) {
            lastPingTime = now;
            dispatchMeshPings(robots);
        }

        renderMatrix(robots, now);
    }

    private void dispatchMeshPings(List<AgvRobot> robots) {
        for (AgvRobot sender : robots) {
            if (sender.isCrashed()) continue;
            long senderId = sender.getNodeId();
            for (AgvRobot receiver : robots) {
                if (receiver.isCrashed() || receiver.getNodeId() == senderId) continue;
                long receiverId = receiver.getNodeId();

                try {
                    sender.getNode().ping(receiverId).thenAccept(rttNano -> {
                        double rttMs = rttNano / 1_000_000.0;
                        rttMatrixMs.put(senderId + "->" + receiverId, rttMs);
                    }).exceptionally(ex -> {
                        rttMatrixMs.remove(senderId + "->" + receiverId);
                        return null;
                    });
                } catch (Exception ignored) {}
            }
        }
    }

    private void renderMatrix(List<AgvRobot> robots, long now) {
        matrixGrid.getChildren().clear();
        linksList.getChildren().clear();

        int n = robots.size();
        List<Double> allRtts = new ArrayList<>();
        int activeLinkCount = 0;

        // Header Top-Left empty cell
        Label originLbl = new Label("Node");
        originLbl.setStyle("-fx-text-fill: #64748B; -fx-font-size: 9.5px; -fx-font-weight: bold;");
        matrixGrid.add(originLbl, 0, 0);

        // Header row
        for (int j = 0; j < n; j++) {
            Label colHeader = new Label("#" + robots.get(j).getNodeId());
            colHeader.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 9.5px; -fx-font-weight: bold; -fx-alignment: center;");
            colHeader.setPrefWidth(42);
            matrixGrid.add(colHeader, j + 1, 0);
        }

        // Grid Rows
        for (int i = 0; i < n; i++) {
            AgvRobot rowRobot = robots.get(i);
            Label rowHeader = new Label("#" + rowRobot.getNodeId());
            rowHeader.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 9.5px; -fx-font-weight: bold;");
            matrixGrid.add(rowHeader, 0, i + 1);

            for (int j = 0; j < n; j++) {
                AgvRobot colRobot = robots.get(j);
                Label cell = new Label();
                cell.setPrefWidth(42);
                cell.setPrefHeight(22);
                cell.setAlignment(Pos.CENTER);
                cell.setStyle("-fx-font-size: 9px; -fx-font-family: monospace; -fx-background-radius: 3px;");

                if (i == j) {
                    cell.setText("—");
                    cell.setStyle(cell.getStyle() + "-fx-text-fill: #475569; -fx-background-color: #1E293B;");
                } else if (rowRobot.isCrashed() || colRobot.isCrashed()) {
                    cell.setText("DOWN");
                    cell.setStyle(cell.getStyle() + "-fx-text-fill: #EF4444; -fx-background-color: #450A0A;");
                } else {
                    String key = rowRobot.getNodeId() + "->" + colRobot.getNodeId();
                    Double rtt = rttMatrixMs.get(key);
                    if (rtt != null) {
                        allRtts.add(rtt);
                        activeLinkCount++;
                        cell.setText(String.format(Locale.US, "%.1f", rtt));
                        if (rtt < 5.0) {
                            cell.setStyle(cell.getStyle() + "-fx-text-fill: #34D399; -fx-background-color: #064E3B;");
                        } else if (rtt < 30.0) {
                            cell.setStyle(cell.getStyle() + "-fx-text-fill: #FDE047; -fx-background-color: #713F12;");
                        } else {
                            cell.setStyle(cell.getStyle() + "-fx-text-fill: #F87171; -fx-background-color: #7F1D1D;");
                        }
                    } else {
                        cell.setText("?");
                        cell.setStyle(cell.getStyle() + "-fx-text-fill: #94A3B8; -fx-background-color: #1E293B;");
                    }
                }
                matrixGrid.add(cell, j + 1, i + 1);
            }
        }

        // Render Links List & Heartbeat Age
        for (AgvRobot robot : robots) {
            if (robot.isCrashed()) continue;
            PeerManager pm = robot.getNode().getPeerManager();
            for (PeerSession session : pm.getActivePeers()) {
                long peerId = session.getPeerId();
                if (robot.getNodeId() >= peerId) continue; // Show each undirected link once

                long ageMs = now - session.getLastHeartbeatTimestamp();
                String statusText;
                String statusColor;
                String statusBg;

                if (!session.isActive() || ageMs > 3000) {
                    statusText = "CHẾT";
                    statusColor = "#EF4444";
                    statusBg = "#450A0A";
                } else if (ageMs > 1000) {
                    statusText = "TRỄ";
                    statusColor = "#F59E0B";
                    statusBg = "#78350F";
                } else {
                    statusText = "OK";
                    statusColor = "#10B981";
                    statusBg = "#064E3B";
                }

                Double rtt = rttMatrixMs.get(robot.getNodeId() + "->" + peerId);
                String rttStr = rtt != null ? String.format(Locale.US, "%.2f ms", rtt) : "-- ms";

                HBox linkRow = new HBox(8);
                linkRow.setAlignment(Pos.CENTER_LEFT);
                linkRow.setPadding(new Insets(3, 6, 3, 6));
                linkRow.setStyle("-fx-background-color: #1E293B; -fx-background-radius: 4px;");

                Label pairLbl = new Label(String.format("AGV #%d ⟷ AGV #%d", robot.getNodeId(), peerId));
                pairLbl.setStyle("-fx-text-fill: #E2E8F0; -fx-font-size: 10px; -fx-font-weight: bold;");
                pairLbl.setPrefWidth(95);

                Label rttLbl = new Label("RTT: " + rttStr);
                rttLbl.setStyle("-fx-text-fill: #38BDF8; -fx-font-size: 9.5px; -fx-font-family: monospace;");
                rttLbl.setPrefWidth(85);

                Label ageLbl = new Label("HB: " + ageMs + "ms trước");
                ageLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 9.5px;");
                HBox.setHgrow(ageLbl, Priority.ALWAYS);

                Label badge = new Label(statusText);
                badge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9.0));
                badge.setTextFill(Color.web(statusColor));
                badge.setStyle("-fx-background-color: " + statusBg + "; -fx-padding: 1 6 1 6; -fx-background-radius: 3px;");

                linkRow.getChildren().addAll(pairLbl, rttLbl, ageLbl, badge);
                linksList.getChildren().add(linkRow);
            }
        }

        // Update percentiles
        activeLinksLabel.setText("Liên kết hoạt động: " + activeLinkCount);
        if (!allRtts.isEmpty()) {
            Collections.sort(allRtts);
            double p50 = allRtts.get((int) Math.round(0.50 * (allRtts.size() - 1)));
            double p95 = allRtts.get((int) Math.round(0.95 * (allRtts.size() - 1)));
            double avg = allRtts.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

            p50Label.setText(String.format(Locale.US, "p50: %.2f ms", p50));
            p95Label.setText(String.format(Locale.US, "p95: %.2f ms", p95));
            avgRttLabel.setText(String.format(Locale.US, "Trung bình: %.2f ms", avg));
        } else {
            p50Label.setText("p50: -- ms");
            p95Label.setText("p95: -- ms");
            avgRttLabel.setText("Trung bình: -- ms");
        }
    }
}
