package com.agv.fleet.ui.panel;

import com.agv.fleet.core.agent.AgvRobot;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Supplier;

/**
 * Enterprise 30-Second Distributed Swimlane Timeline Chart.
 * <p>
 * Displays a multi-agent time-series sequence where each AGV has a dedicated horizontal lane.
 * Chronological protocol and lifecycle events are rendered as distinct colored markers:
 * <ul>
 *   <li><b>Amber dot:</b> Mutex Request / Wait</li>
 *   <li><b>Green dot:</b> Mutex Acquired / Released</li>
 *   <li><b>Red dot:</b> Task Won / Awarded</li>
 *   <li><b>Purple dot:</b> Task Completed</li>
 *   <li><b>Cyan dot:</b> Physical Cell Step</li>
 * </ul>
 */
public class TimelinePanel extends VBox {

    public enum EventCategory {
        MUTEX_REQ(Color.web("#F59E0B"), "Yêu cầu Mutex"),
        MUTEX_OK(Color.web("#10B981"), "Giữ/Nhả Mutex"),
        TASK_WON(Color.web("#EF4444"), "Trúng thầu"),
        TASK_DONE(Color.web("#8B5CF6"), "Xong nhiệm vụ"),
        MOVE_STEP(Color.web("#06B6D4"), "Đổi ô");

        public final Color color;
        public final String label;

        EventCategory(Color color, String label) {
            this.color = color;
            this.label = label;
        }
    }

    public record TimelineEvent(long timestampMs, EventCategory category, String description) {}

    private final Supplier<List<AgvRobot>> robotsSupplier;
    private final Map<Long, Deque<TimelineEvent>> nodeEvents = new ConcurrentHashMap<>();

    private final Canvas canvas = new Canvas(410, 260);
    private final Label hoverDetailLabel = new Label("Di chuột hoặc quan sát các sự kiện trên biểu đồ chuỗi");

    public TimelinePanel(Supplier<List<AgvRobot>> robotsSupplier) {
        super(8);
        this.robotsSupplier = robotsSupplier;
        setPadding(new Insets(10));
        setStyle("-fx-background-color: #0F172A; -fx-border-color: #1E293B; -fx-border-width: 1px; -fx-border-radius: 8px; -fx-background-radius: 8px;");

        // Header
        HBox topBox = new HBox(8);
        topBox.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("⏱ BIỂU ĐỒ CHUỖI THỜI GIAN (SWIMLANE 30 GIÂY)");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        title.setTextFill(Color.web("#38BDF8"));
        topBox.getChildren().add(title);

        hoverDetailLabel.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 9.5px; -fx-font-family: monospace;");

        // Legend bar
        HBox legendBox = new HBox(10);
        legendBox.setAlignment(Pos.CENTER_LEFT);
        legendBox.setPadding(new Insets(4, 8, 4, 8));
        legendBox.setStyle("-fx-background-color: #111827; -fx-background-radius: 4px;");

        for (EventCategory cat : EventCategory.values()) {
            HBox item = new HBox(4);
            item.setAlignment(Pos.CENTER_LEFT);
            Label dot = new Label("●");
            dot.setTextFill(cat.color);
            dot.setStyle("-fx-font-size: 11px;");
            Label lbl = new Label(cat.label);
            lbl.setStyle("-fx-text-fill: #CBD5E1; -fx-font-size: 9px;");
            item.getChildren().addAll(dot, lbl);
            legendBox.getChildren().add(item);
        }

        // Canvas container
        StackPane canvasHolder = new StackPane(canvas);
        canvasHolder.setStyle("-fx-background-color: #111827; -fx-border-color: #1E293B; -fx-border-radius: 4px;");

        ScrollPane scrollPane = new ScrollPane(canvasHolder);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        getChildren().addAll(topBox, hoverDetailLabel, legendBox, scrollPane);

        // Track hover to display event details
        canvas.setOnMouseMoved(e -> handleHover(e.getX(), e.getY()));
    }

    /**
     * Records an event on a node's swimlane.
     */
    public void recordEvent(long nodeId, EventCategory category, String description) {
        long now = System.currentTimeMillis();
        Deque<TimelineEvent> deque = nodeEvents.computeIfAbsent(nodeId, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(new TimelineEvent(now, category, description));

        // Purge events older than 40s
        long cutoff = now - 40_000;
        while (!deque.isEmpty() && deque.peekFirst().timestampMs() < cutoff) {
            deque.pollFirst();
        }
    }

    public void refresh() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refresh);
            return;
        }

        List<AgvRobot> robots = robotsSupplier.get();
        if (robots == null || robots.isEmpty()) return;

        double width = Math.max(400, getWidth() - 30);
        int laneCount = robots.size();
        double laneHeight = 36.0;
        double headerHeight = 25.0;
        double height = headerHeight + laneCount * laneHeight + 20.0;

        canvas.setWidth(width);
        canvas.setHeight(height);

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.web("#111827"));
        gc.fillRect(0, 0, width, height);

        long now = System.currentTimeMillis();
        long windowStart = now - 30_000; // 30-second window
        double leftMargin = 55.0;
        double plotWidth = width - leftMargin - 15.0;

        // Draw time grid lines (-30s, -20s, -10s, 0s)
        gc.setStroke(Color.web("#1E293B"));
        gc.setLineWidth(1.0);
        gc.setFill(Color.web("#64748B"));
        gc.setFont(Font.font("Segoe UI", 9.0));

        for (int sec = 0; sec <= 30; sec += 10) {
            double x = leftMargin + (sec / 30.0) * plotWidth;
            gc.strokeLine(x, headerHeight, x, height - 15.0);
            String label = (sec == 30) ? "Bây giờ" : String.format("-%ds", 30 - sec);
            gc.fillText(label, x - 10, headerHeight - 6);
        }

        // Draw Swimlanes
        for (int i = 0; i < laneCount; i++) {
            AgvRobot robot = robots.get(i);
            double y = headerHeight + i * laneHeight;

            // Lane alternating background
            if (i % 2 == 1) {
                gc.setFill(Color.web("#0F172A"));
                gc.fillRect(0, y, width, laneHeight);
            }

            // Lane separator line
            gc.setStroke(Color.web("#1E293B"));
            gc.strokeLine(0, y + laneHeight, width, y + laneHeight);

            // AGV Label
            gc.setFill(Color.web("#38BDF8"));
            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10.0));
            gc.fillText("AGV #" + robot.getNodeId(), 8, y + laneHeight / 2 + 4);

            // Center track line
            double trackY = y + laneHeight / 2;
            gc.setStroke(Color.web("#334155"));
            gc.setLineWidth(1.5);
            gc.strokeLine(leftMargin, trackY, leftMargin + plotWidth, trackY);

            // Plot events
            Deque<TimelineEvent> events = nodeEvents.get(robot.getNodeId());
            if (events != null) {
                for (TimelineEvent ev : events) {
                    if (ev.timestampMs() < windowStart || ev.timestampMs() > now) continue;
                    double relTime = (ev.timestampMs() - windowStart) / 30000.0;
                    double evX = leftMargin + relTime * plotWidth;

                    gc.setFill(ev.category().color);
                    gc.fillOval(evX - 4, trackY - 4, 8, 8);
                    gc.setStroke(Color.web("#0F172A"));
                    gc.setLineWidth(1.0);
                    gc.strokeOval(evX - 4, trackY - 4, 8, 8);
                }
            }
        }
    }

    private void handleHover(double mouseX, double mouseY) {
        List<AgvRobot> robots = robotsSupplier.get();
        if (robots == null || robots.isEmpty()) return;

        double leftMargin = 55.0;
        double plotWidth = canvas.getWidth() - leftMargin - 15.0;
        long now = System.currentTimeMillis();
        long windowStart = now - 30_000;

        double headerHeight = 25.0;
        double laneHeight = 36.0;

        for (int i = 0; i < robots.size(); i++) {
            AgvRobot robot = robots.get(i);
            double y = headerHeight + i * laneHeight;
            double trackY = y + laneHeight / 2;

            if (Math.abs(mouseY - trackY) < 12) {
                Deque<TimelineEvent> events = nodeEvents.get(robot.getNodeId());
                if (events != null) {
                    for (TimelineEvent ev : events) {
                        if (ev.timestampMs() < windowStart) continue;
                        double relTime = (ev.timestampMs() - windowStart) / 30000.0;
                        double evX = leftMargin + relTime * plotWidth;

                        if (Math.abs(mouseX - evX) < 6) {
                            hoverDetailLabel.setText(String.format("AGV #%d: [%s] %s (%d ms trước)",
                                    robot.getNodeId(), ev.category().label, ev.description(), (now - ev.timestampMs())));
                            return;
                        }
                    }
                }
            }
        }
    }
}
