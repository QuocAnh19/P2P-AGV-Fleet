package com.agv.fleet.ui.scenario;

/**
 * Types of interactive enterprise scenarios supported by the Simulator.
 */
public enum UiScenarioType {
    S1_HEAD_ON_INTERSECTION("S1: Head-On Intersection Mutex", "Kiểm thử tranh chấp ngã tư Ricart-Agrawala với 2, 4, hoặc 8 xe"),
    S2_ADAPTIVE_AUCTION("S2: Adaptive CNP Task Auction", "Đấu thầu nhiệm vụ phân tán với 5 xe có mức pin khác nhau (90%, 60%, 40%, 20%, 10%)"),
    S3_CRASH_RECOVERY("S3: Node Failure & Network Freeze", "Mô phỏng sập nguồn đột ngột, đóng băng mạng và sập khi đang giữ ngã tư");

    private final String title;
    private final String description;

    UiScenarioType(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }
}
