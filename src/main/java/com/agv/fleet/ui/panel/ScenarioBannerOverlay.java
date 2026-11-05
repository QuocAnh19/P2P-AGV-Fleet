package com.agv.fleet.ui.panel;

import com.agv.fleet.ui.scenario.UiScenarioResult;
import com.agv.fleet.ui.scenario.UiScenarioType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.function.Consumer;

/**
 * UI Component displaying:
 * 1. Step-by-step description banner at top of canvas during scenario execution.
 * 2. PASS / FAIL Verdict Card overlay with metrics upon scenario completion.
 * 3. Quick action buttons for S1 (toggle 2/4/8 AGVs) and S3 (Hard crash / Freeze / Mutex-held crash).
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

    public ScenarioBannerOverlay() {
        setAlignment(Pos.TOP_CENTER);
        setPickOnBounds(false); // Let clicks pass through empty spaces to canvas

        // 1. Top Banner Box
        topBannerBox = new VBox(4);
        topBannerBox.setAlignment(Pos.CENTER);
        topBannerBox.setPadding(new Insets(8, 16, 8, 16));
        topBannerBox.setMaxWidth(720);
        topBannerBox.setStyle("-fx-background-color: #1E293BEE; -fx-border-color: #3B82F6; -fx-border-width: 1.5px; -fx-border-radius: 8px; -fx-background-radius: 8px; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.6), 10, 0, 0, 4);");
        topBannerBox.setVisible(false);

        scenarioTitleLabel = new Label("SCENARIO BANNER");
        scenarioTitleLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
        scenarioTitleLabel.setTextFill(Color.web("#38BDF8"));

        stepDescriptionLabel = new Label("Mô tả bước kịch bản...");
        stepDescriptionLabel.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 11));
        stepDescriptionLabel.setTextFill(Color.WHITE);
        stepDescriptionLabel.setWrapText(true);

        scenarioControlsBox = new HBox(8);
        scenarioControlsBox.setAlignment(Pos.CENTER);

        topBannerBox.getChildren().addAll(scenarioTitleLabel, stepDescriptionLabel, scenarioControlsBox);

        // 2. Verdict Overlay Card
        verdictOverlayCard = new VBox(10);
        verdictOverlayCard.setAlignment(Pos.CENTER);
        verdictOverlayCard.setPadding(new Insets(16, 24, 16, 24));
        verdictOverlayCard.setMaxWidth(520);
        verdictOverlayCard.setMaxHeight(260);
        verdictOverlayCard.setStyle("-fx-background-color: #0F172AF8; -fx-border-color: #10B981; -fx-border-width: 2.5px; -fx-border-radius: 12px; -fx-background-radius: 12px; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.8), 20, 0, 0, 8);");
        verdictOverlayCard.setVisible(false);

        verdictBadge = new Label("PASS");
        verdictBadge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 22));
        verdictBadge.setPadding(new Insets(4, 16, 4, 16));
        verdictBadge.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-background-radius: 6px;");

        verdictSummary = new Label("Summary text");
        verdictSummary.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        verdictSummary.setTextFill(Color.WHITE);
        verdictSummary.setWrapText(true);

        verdictMetrics = new Label("Metrics breakdown");
        verdictMetrics.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 11));
        verdictMetrics.setTextFill(Color.web("#94A3B8"));

        closeVerdictBtn = new Button("Đóng / Tiếp tục");
        closeVerdictBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 6 16 6 16; -fx-cursor: hand; -fx-background-radius: 5px;");
        closeVerdictBtn.setOnAction(e -> hideVerdict());

        verdictOverlayCard.getChildren().addAll(verdictBadge, verdictSummary, verdictMetrics, closeVerdictBtn);

        StackPane.setAlignment(topBannerBox, Pos.TOP_CENTER);
        StackPane.setMargin(topBannerBox, new Insets(10, 0, 0, 0));

        StackPane.setAlignment(verdictOverlayCard, Pos.CENTER);

        getChildren().addAll(topBannerBox, verdictOverlayCard);
    }

    public void showBanner(UiScenarioType type, String bannerText) {
        scenarioTitleLabel.setText("🎬 " + type.getTitle().toUpperCase());
        stepDescriptionLabel.setText(bannerText);
        topBannerBox.setVisible(true);
        verdictOverlayCard.setVisible(false);
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
            verdictBadge.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-background-radius: 6px;");
            verdictOverlayCard.setStyle("-fx-background-color: #0F172AF8; -fx-border-color: #10B981; -fx-border-width: 2.5px; -fx-border-radius: 12px; -fx-background-radius: 12px; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.8), 20, 0, 0, 8);");
        } else {
            verdictBadge.setText("❌ FAIL");
            verdictBadge.setStyle("-fx-background-color: #EF4444; -fx-text-fill: white; -fx-background-radius: 6px;");
            verdictOverlayCard.setStyle("-fx-background-color: #0F172AF8; -fx-border-color: #EF4444; -fx-border-width: 2.5px; -fx-border-radius: 12px; -fx-background-radius: 12px; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.8), 20, 0, 0, 8);");
        }

        verdictSummary.setText(result.summary());
        verdictMetrics.setText(String.format("Va chạm ground-truth: %d | Chờ mutex TB: %.1f ms | Nhiệm vụ thành công: %d",
                result.collisionCount(), result.averageWaitTimeMs(), result.tasksSucceeded()));

        verdictOverlayCard.setVisible(true);
    }

    public void hideVerdict() {
        verdictOverlayCard.setVisible(false);
    }
}
