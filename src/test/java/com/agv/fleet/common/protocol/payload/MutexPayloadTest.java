package com.agv.fleet.common.protocol.payload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Mutex Binary Payload Serialization Tests")
class MutexPayloadTest {

    @Test
    @DisplayName("MutexRequestPayload binary round-trip")
    void testMutexRequestPayloadRoundTrip() {
        MutexRequestPayload original = new MutexRequestPayload("intersection_X", 1042L, 7L);
        byte[] bytes = original.toBytes();

        MutexRequestPayload deserialized = MutexRequestPayload.fromBytes(bytes);
        assertThat(deserialized.cellId()).isEqualTo("intersection_X");
        assertThat(deserialized.requestTimestamp()).isEqualTo(1042L);
        assertThat(deserialized.requesterNodeId()).isEqualTo(7L);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    @DisplayName("MutexReplyPayload binary round-trip")
    void testMutexReplyPayloadRoundTrip() {
        MutexReplyPayload original = new MutexReplyPayload("intersection_Y", 12L);
        byte[] bytes = original.toBytes();

        MutexReplyPayload deserialized = MutexReplyPayload.fromBytes(bytes);
        assertThat(deserialized.cellId()).isEqualTo("intersection_Y");
        assertThat(deserialized.responderNodeId()).isEqualTo(12L);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    @DisplayName("MutexReleasePayload binary round-trip")
    void testMutexReleasePayloadRoundTrip() {
        MutexReleasePayload original = new MutexReleasePayload("charging_dock_3", 99L);
        byte[] bytes = original.toBytes();

        MutexReleasePayload deserialized = MutexReleasePayload.fromBytes(bytes);
        assertThat(deserialized.cellId()).isEqualTo("charging_dock_3");
        assertThat(deserialized.releaserNodeId()).isEqualTo(99L);
        assertThat(deserialized).isEqualTo(original);
    }
}
