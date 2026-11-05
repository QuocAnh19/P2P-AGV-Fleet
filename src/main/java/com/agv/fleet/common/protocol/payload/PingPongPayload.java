package com.agv.fleet.common.protocol.payload;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Binary payload for PING (Opcode 0x04) and PONG (Opcode 0x05).
 * Encapsulates an 8-byte send timestamp (System.nanoTime()) to measure RTT latency.
 */
public record PingPongPayload(long sendNanoTime) {

    public static final int PAYLOAD_SIZE = 8;

    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(PAYLOAD_SIZE);
        buf.putLong(sendNanoTime);
        return buf.array();
    }

    public static PingPongPayload fromBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes cannot be null");
        if (bytes.length < PAYLOAD_SIZE) {
            throw new IllegalArgumentException("Payload too short for PingPong: " + bytes.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        return new PingPongPayload(buf.getLong());
    }
}
