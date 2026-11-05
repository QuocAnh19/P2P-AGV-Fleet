package com.agv.fleet.ui.panel;

import com.agv.fleet.ui.metrics.FleetMetricsCollector;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * Enterprise Metrics Panel.
 * <p>
 * Displays ground-truth collisions, task delivery statistics, Ricart-Agrawala
 * average wait times, CNP bidding stats, wire message rates, and p50/p95 latency.
 * Refreshes once per second.
 */
public class MetricsPanel extends VBox {

    private final Label collisionCard;
    private final Label taskStatsCard;
    private final Label mutexWaitCard;
    private final Label auctionStatsCard;
    private final Label messageRateCard;
    private final Label latencyCard;
    private final Label peersAliveCard;

    public MetricsPanel() {
        super(8);
        setPadding(new Insets(10));
        setStyle("-fx-background-color: #0F172A; -fx-border-color: #1E293B; -fx-border-width: 1px; -fx-border-radius: 8px; -fx-background-radius: 8px;");

        Label header = new Label("📊 SYSTEM METRICS & PERFORMANCE");
        header.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        header.setTextFill(Color.web("#94A3B8"));

        collisionCard = createCard("VA CHẠM GROUND-TRUTH", "0 VA CHẠM", "#10B981", true);
        Tooltip.install(collisionCard, new Tooltip("Số lần 2 xe AGV cùng nằm trong 1 ô lưới hoặc va chạm vật lý"));

        taskStatsCard = createCard("NHIỆM VỤ VẬN CHUYỂN", "Hoàn thành: 0 | Thất bại: 0", "#06B6D4", false);
        Tooltip.install(taskStatsCard, new Tooltip("Tổng số đơn hàng vận chuyển hoàn tất và số đơn lỗi"));

        mutexWaitCard = createCard("CHỜ NGÃ TƯ (MUTEX)", "TB: 0.0 ms (0 lượt)", "#F59E0B", false);
        Tooltip.install(mutexWaitCard, new Tooltip("Thời gian chờ cấp quyền vào ngã tư trung bình theo thuật toán Ricart-Agrawala"));

        auctionStatsCard = createCard("ĐẤU THẦU PHÂN TÁN (CNP)", "Phát thầu: 0 | Nộp thầu: 0", "#8B5CF6", false);
        Tooltip.install(auctionStatsCard, new Tooltip("Số phiên phát thầu Contract Net Protocol và tổng số gói thầu đã nộp"));

        messageRateCard = createCard("THÔNG LƯỢNG MẠNG WIRE", "0.0 msgs/s (Tổng: 0)", "#3B82F6", false);
        Tooltip.install(messageRateCard, new Tooltip("Tốc độ truyền tin toàn mạng mesh (gói tin trên giây)"));

        latencyCard = createCard("ĐỘ TRỄ MẠNG (PING/PONG)", "p50: 0.00 ms | p95: 0.00 ms", "#EC4899", false);
        Tooltip.install(latencyCard, new Tooltip("Phân vị độ trễ Round-Trip Time đo bằng opcode PING/PONG"));

        peersAliveCard = createCard("PEERS ĐANG KẾT NỐI", "0 Peers Hoạt Động", "#10B981", false);
        Tooltip.install(peersAliveCard, new Tooltip("Số lượng xe AGV đang duy trì kết nối P2P Full-Mesh"));

        getChildren().addAll(
                header,
                collisionCard,
                taskStatsCard,
                mutexWaitCard,
                auctionStatsCard,
                messageRateCard,
                latencyCard,
                peersAliveCard
        );
    }

    private Label createCard(String title, String initialValue, String accentColor, boolean isBig) {
        Label card = new Label(title + "\n" + initialValue);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setFont(Font.font("Segoe UI", FontWeight.BOLD, isBig ? 12 : 10));
        card.setTextFill(Color.WHITE);
        card.setPadding(new Insets(6, 10, 6, 10));
        card.setStyle(String.format(
                "-fx-background-color: %s18; -fx-border-color: %s88; -fx-border-width: 1.5px; -fx-border-radius: 6px; -fx-background-radius: 6px;",
                accentColor, accentColor
        ));
        return card;
    }

    public void update(FleetMetricsCollector collector) {
        if (collector == null) return;

        int collisions = collector.getGroundTruthCollisions();
        if (collisions == 0) {
            collisionCard.setText("VA CHẠM GROUND-TRUTH\n0 VA CHẠM (AN TOÀN)");
            collisionCard.setStyle("-fx-background-color: #10B98122; -fx-border-color: #10B981; -fx-border-width: 2px; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-text-fill: #34D399; -fx-font-size: 12px; -fx-font-weight: bold;");
        } else {
            collisionCard.setText(String.format("VA CHẠM GROUND-TRUTH\n⚠️ %d VA CHẠM PHÁT HIỆN!", collisions));
            collisionCard.setStyle("-fx-background-color: #EF444433; -fx-border-color: #EF4444; -fx-border-width: 2px; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-text-fill: #FCA5A5; -fx-font-size: 12px; -fx-font-weight: bold;");
        }

        taskStatsCard.setText(String.format("NHIỆM VỤ VẬN CHUYỂN\nHoàn thành: %d | Thất bại: %d",
                collector.getTasksCompleted(), collector.getTasksFailed()));

        mutexWaitCard.setText(String.format("CHỜ NGÃ TƯ (MUTEX)\nTB: %.1f ms (%d lượt)",
                collector.getAverageMutexWaitTimeMs(), collector.getTotalMutexAcquisitions()));

        auctionStatsCard.setText(String.format("ĐẤU THẦU PHÂN TÁN (CNP)\nPhát thầu: %d | Nộp thầu: %d",
                collector.getTotalAuctionsAnnounced(), collector.getTotalBidsSubmitted()));

        messageRateCard.setText(String.format("THÔNG LƯỢNG MẠNG WIRE\n%.1f msgs/s (Tổng: %d)",
                collector.getMessagesPerSecond(), collector.getTotalMessagesProcessed()));

        latencyCard.setText(String.format("ĐỘ TRỄ MẠNG (PING/PONG)\np50: %.2f ms | p95: %.2f ms",
                collector.getP50LatencyMs(), collector.getP95LatencyMs()));

        peersAliveCard.setText(String.format("PEERS ĐANG KẾT NỐI\n%d Peers Hoạt Động",
                collector.getAlivePeersCount()));
    }
}
