package com.agv.fleet.common.protocol.payload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Contract Net Protocol Binary Payload Serialization Tests")
class CnpPayloadTest {

    @Test
    @DisplayName("TaskAnnouncePayload binary round-trip")
    void testTaskAnnouncePayloadRoundTrip() {
        TaskAnnouncePayload original = new TaskAnnouncePayload("TASK-1001", 10.5, 20.25, 55.0, 80.75, 42L);
        byte[] bytes = original.toBytes();

        TaskAnnouncePayload deserialized = TaskAnnouncePayload.fromBytes(bytes);
        assertThat(deserialized.taskId()).isEqualTo("TASK-1001");
        assertThat(deserialized.pickupX()).isEqualTo(10.5);
        assertThat(deserialized.pickupY()).isEqualTo(20.25);
        assertThat(deserialized.deliveryX()).isEqualTo(55.0);
        assertThat(deserialized.deliveryY()).isEqualTo(80.75);
        assertThat(deserialized.announcerNodeId()).isEqualTo(42L);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    @DisplayName("TaskBidPayload binary round-trip")
    void testTaskBidPayloadRoundTrip() {
        TaskBidPayload original = new TaskBidPayload("TASK-1002", 7L, 14.85, 87.5);
        byte[] bytes = original.toBytes();

        TaskBidPayload deserialized = TaskBidPayload.fromBytes(bytes);
        assertThat(deserialized.taskId()).isEqualTo("TASK-1002");
        assertThat(deserialized.bidderNodeId()).isEqualTo(7L);
        assertThat(deserialized.cost()).isEqualTo(14.85);
        assertThat(deserialized.batteryLevel()).isEqualTo(87.5);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    @DisplayName("TaskAwardPayload binary round-trip")
    void testTaskAwardPayloadRoundTrip() {
        TaskAwardPayload original = new TaskAwardPayload("TASK-1003", 99L);
        byte[] bytes = original.toBytes();

        TaskAwardPayload deserialized = TaskAwardPayload.fromBytes(bytes);
        assertThat(deserialized.taskId()).isEqualTo("TASK-1003");
        assertThat(deserialized.winnerNodeId()).isEqualTo(99L);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    @DisplayName("TaskCompletePayload binary round-trip")
    void testTaskCompletePayloadRoundTrip() {
        TaskCompletePayload original = new TaskCompletePayload("TASK-1004", 88L);
        byte[] bytes = original.toBytes();

        TaskCompletePayload deserialized = TaskCompletePayload.fromBytes(bytes);
        assertThat(deserialized.taskId()).isEqualTo("TASK-1004");
        assertThat(deserialized.contractorNodeId()).isEqualTo(88L);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    @DisplayName("Payload validation rejects invalid input")
    void testPayloadValidation() {
        assertThatThrownBy(() -> new TaskAnnouncePayload("", 0, 0, 0, 0, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskBidPayload("   ", 1L, 10.0, 100.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskAwardPayload(null, 1L))
                .isInstanceOf(NullPointerException.class);
    }
}
