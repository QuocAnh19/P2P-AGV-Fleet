package com.agv.fleet.network.mesh;

import com.agv.fleet.common.protocol.Opcode;
import com.agv.fleet.common.protocol.payload.PingPongPayload;
import com.agv.fleet.core.AgvNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class PingPongTest {

    private AgvNode node1;
    private AgvNode node2;

    @AfterEach
    void tearDown() {
        if (node1 != null) {
            try { node1.stop(); } catch (Exception ignored) {}
        }
        if (node2 != null) {
            try { node2.stop(); } catch (Exception ignored) {}
        }
    }

    @Test
    @DisplayName("Task 1: Opcode PING = 0x04 and PONG = 0x05 mappings")
    void testOpcodeValues() {
        assertThat(Opcode.PING.getCode()).isEqualTo((byte) 0x04);
        assertThat(Opcode.PONG.getCode()).isEqualTo((byte) 0x05);
        assertThat(Opcode.fromByte((byte) 0x04)).isEqualTo(Opcode.PING);
        assertThat(Opcode.fromByte((byte) 0x05)).isEqualTo(Opcode.PONG);
    }

    @Test
    @DisplayName("Task 1: PingPongPayload 8-byte serialization and deserialization")
    void testPayloadSerialization() {
        long nano = 123456789012345L;
        PingPongPayload payload = new PingPongPayload(nano);
        byte[] bytes = payload.toBytes();

        assertThat(bytes).hasSize(8);
        PingPongPayload decoded = PingPongPayload.fromBytes(bytes);
        assertThat(decoded.sendNanoTime()).isEqualTo(nano);

        assertThatThrownBy(() -> PingPongPayload.fromBytes(new byte[]{1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Task 1: End-to-end PING and PONG RTT latency measurement between two nodes")
    void testEndToEndPingPong() throws Exception {
        int multicastPort = ThreadLocalRandom.current().nextInt(20000, 30000);

        node1 = AgvNode.builder()
                .nodeId(1L)
                .tcpPort(19701)
                .multicastPort(multicastPort)
                .heartbeatIntervalMs(100)
                .heartbeatTimeoutMs(1000)
                .build();

        node2 = AgvNode.builder()
                .nodeId(2L)
                .tcpPort(19702)
                .multicastPort(multicastPort)
                .heartbeatIntervalMs(100)
                .heartbeatTimeoutMs(1000)
                .build();

        node1.start();
        node2.start();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(node1.getActivePeerCount()).isEqualTo(1);
            assertThat(node2.getActivePeerCount()).isEqualTo(1);
        });

        // Node 1 pings Node 2
        CompletableFuture<Long> pingFuture = node1.ping(2L);

        await().atMost(3, TimeUnit.SECONDS).until(pingFuture::isDone);
        long rttNanos = pingFuture.get();

        assertThat(rttNanos).isPositive();
        // RTT on loopback should typically be under 50ms (50,000,000 ns)
        assertThat(rttNanos).isLessThan(TimeUnit.MILLISECONDS.toNanos(100));
    }
}
