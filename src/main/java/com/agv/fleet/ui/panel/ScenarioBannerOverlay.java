package com.agv.fleet.ui.panel;

import com.agv.fleet.ui.scenario.UiScenarioResult;
import com.agv.fleet.ui.scenario.UiScenarioType;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

/**
 * UI Component displaying:
 * 1. Step-by-step description banner at top of canvas during scenario execution (compact, non-blocking).
 * 2. PASS / FAIL Verdict Card overlay placed at bottom-right corner, auto-hiding after 12 seconds.
 * 3. Quick action buttons for S1 and S3 scenarios.
 */
public class ScenarioBannerOverlay extends StackPane {

    private final VBox topBannerBox;
    private final Label scenarioTitleLabel;
    private final Label stepDescriptionLabel;
    private final HBox scenarioControlsBox;

    private final VBox verdictOverlayCard;
    private final Label verdictBadge;
    private final Label verdictSummary;
    private final Label verdictMetrics;
    private final Button closeVerdictBtn;
    private final PauseTransition autoHideTimer;

    public ScenarioBannerOverlay() {
        setAlignment(Pos.TOP_CENTER);
        setPickOnBounds(false); // Let mouse clicks pass through empty spaces to canvas

        // 1. Top Banner Box: compact, max 560px width, height fits content, semi-transparent
        topBannerBox = new VBox(4);
        topBannerBox.setAlignment(Pos.CENTER);
        topBannerBox.setPadding(new Insets(6, 14, 6, 14));
        topBannerBox.setMaxWidth(560);
        topBannerBox.setMaxHeight(Region.USE_PREF_SIZE);
        topBannerBox.setPickOnBounds(false);
        topBannerBox.setStyle("-fx-background-color: #0F172ACC; -fx-border-color: #38BDF8; -fx-border-width: 1.5px; -fx-border-radius: 8px; -fx-background-radius: 8px; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 10, 0, 0, 3);");
        topBannerBox.setVisible(false);

        scenarioTitleLabel = new Label("SCENARIO BANNER");
        scenarioTitleLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        scenarioTitleLabel.setTextFill(Color.web("#38BDF8"));

        stepDescriptionLabel = new Label("Mô tả bước kịch bản...");
        stepDescriptionLabel.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 11));
        stepDescriptionLabel.setTextFill(Color.WHITE);
        stepDescriptionLabel.setWrapText(true);

        scenarioControlsBox = new HBox(8);
        scenarioControlsBox.setAlignment(Pos.CENTER);
        scenarioControlsBox.setPickOnBounds(false);

        topBannerBox.getChildren().addAll(scenarioTitleLabel, stepDescriptionLabel, scenarioControlsBox);

        // 2. Verdict Overlay Card: placed at bottom-right, auto-hides after 12s, has Close button
        verdictOverlayCard = new VBox(8);
        verdictOverlayCard.setAlignment(Pos.CENTER_LEFT);
        verdictOverlayCard.setPadding(new Insets(12, 16, 12, 16));
        verdictOverlayCard.setMaxWidth(440);
        verdictOverlayCard.setMaxHeight(Region.USE_PREF_SIZE);
        verdictOverlayCard.setPickOnBounds(true);
        verdictOverlayCard.setStyle("-fx-background-color: rgba(15, 23, 42, 0.92); -fx-border-color: #10B981; -fx-border-width: 2px; -fx-border-radius: 10px; -fx-background-radius: 10px; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.7), 16, 0, 0, 6);");
        verdictOverlayCard.setVisible(false);

        HBox verdictHeader = new HBox(8);
        verdictHeader.setAlignment(Pos.CENTER_LEFT);

        verdictBadge = new Label("PASS");
        verdictBadge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        verdictBadge.setPadding(new Insets(3, 10, 3, 10));
        verdictBadge.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-background-radius: 5px;");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        closeVerdictBtn = new Button("✕ Đóng");
        closeVerdictBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: #E2E8F0; -fx-font-size: 10px; -fx-padding: 3 8 3 8; -fx-cursor: hand; -fx-background-radius: 4px;");
        closeVerdictBtn.setOnAction(e -> hideVerdict());
        Tooltip.install(closeVerdictBtn, new Tooltip("Đóng thẻ kết quả"));

        verdictHeader.getChildren().addAll(verdictBadge, headerSpacer, closeVerdictBtn);

        verdictSummary = new Label("Summary text");
        verdictSummary.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        verdictSummary.setTextFill(Color.WHITE);
        verdictSummary.setWrapText(true);

        verdictMetrics = new Label("Metrics breakdown");
        verdictMetrics.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 10));
        verdictMetrics.setTextFill(Color.web("#94A3B8"));
        verdictMetrics.setWrapText(true);

        verdictOverlayCard.getChildren().addAll(verdictHeader, verdictSummary, verdictMetrics);

        // Auto-hide after 12 seconds timer
        autoHideTimer = new PauseTransition(Duration.seconds(12));
        autoHideTimer.setOnFinished(e -> hideVerdict());

        StackPane.setAlignment(topBannerBox, Pos.TOP_CENTER);
        StackPane.setMargin(topBannerBox, new Insets(8, 0, 0, 0));

        // Place at Bottom-Right with 12px margin
        StackPane.setAlignment(verdictOverlayCard, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(verdictOverlayCard, new Insets(0, 12, 12, 0));

        getChildren().addAll(topBannerBox, verdictOverlayCard);
    }

    public void showBanner(UiScenarioType type, String bannerText) {
        scenarioTitleLabel.setText("🎬 " + type.getTitle().toUpperCase());
        stepDescriptionLabel.setText(bannerText);
        topBannerBox.setVisible(true);
        hideVerdict();
    }

    public void updateStepBanner(String bannerText) {
        stepDescriptionLabel.setText(bannerText);
        topBannerBox.setVisible(true);
    }

    public void hideBanner() {
        topBannerBox.setVisible(false);
        scenarioControlsBox.getChildren().clear();
    }

    public void setScenarioControls(Button... buttons) {
        scenarioControlsBox.getChildren().setAll(buttons);
    }

    public void showVerdict(UiScenarioResult result) {
        topBannerBox.setVisible(false);

        boolean passed = result.passed();
        if (passed) {
            verdictBadge.setText("✅ PASS");
            verdictBadge.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-background-radius: 5px;");
            verdictOverlayCard.setStyle("-fx-background-color: rgba(15, 23, 42, 0.92); -fx-border-color: #10B981; -fx-border-width: 2px; -fx-border-radius: 10px; -fx-background-radius: 10px; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.7), 16, 0, 0, 6);");
        } else {
            verdictBadge.setText("❌ FAIL");
            verdictBadge.setStyle("-fx-background-color: #EF4444; -fx-text-fill: white; -fx-background-radius: 5px;");
            verdictOverlayCard.setStyle("-fx-background-color: rgba(15, 23, 42, 0.92); -fx-border-color: #EF4444; -fx-border-width: 2px; -fx-border-radius: 10px; -fx-background-radius: 10px; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.7), 16, 0, 0, 6);");
        }

        verdictSummary.setText(result.summary());
        verdictMetrics.setText(String.format("Va chạm ground-truth: %d | Chờ mutex TB: %.1f ms | Nhiệm vụ hoàn thành: %d",
                result.collisionCount(), result.averageWaitTimeMs(), result.tasksSucceeded()));

        verdictOverlayCard.setVisible(true);

        // Start 12-second auto-hide countdown
        autoHideTimer.stop();
        autoHideTimer.playFromStart();
    }

    public void hideVerdict() {
        autoHideTimer.stop();
        verdictOverlayCard.setVisible(false);
    }
}
