package com.agv.fleet.common.protocol;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Immutable domain model representing a complete message in the P2P AGV Fleet network.
 * <p>
 * Combines the fixed 36-byte {@link AgvHeader} with the variable-length binary payload.
 * Provides static factory methods with automated CRC32 checksum computation.
 */
public final class AgvMessage {

    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    private final AgvHeader header;
    private final byte[] payload;

    /**
     * Primary constructor with defensive copying of payload.
     *
     * @param header  message header
     * @param payload message payload bytes
     */
    public AgvMessage(AgvHeader header, byte[] payload) {
        this.header = Objects.requireNonNull(header, "Header cannot be null");
        byte[] safePayload = (payload == null) ? EMPTY_PAYLOAD : payload.clone();
        if (header.payloadLength() != safePayload.length) {
            throw new IllegalArgumentException(String.format(
                    "Header payloadLength (%d) does not match actual payload length (%d)",
                    header.payloadLength(), safePayload.length
            ));
        }
        this.payload = safePayload;
    }

    /**
     * Factory method creating an {@link AgvMessage} with automatic CRC32 calculation.
     *
     * @param opcode           message operational code
     * @param sequenceId       monotonically increasing sequence ID
     * @param lamportTimestamp logical Lamport clock
     * @param senderNodeId     AGV identifier
     * @param payload          byte array payload (can be null or empty)
     * @return newly created {@link AgvMessage}
     */
    public static AgvMessage create(Opcode opcode,
                                    long sequenceId,
                                    long lamportTimestamp,
                                    long senderNodeId,
                                    byte[] payload) {
        byte[] safePayload = (payload == null) ? EMPTY_PAYLOAD : payload.clone();
        long checksum = calculateCrc32(safePayload);

        AgvHeader header = AgvHeader.builder()
                .magic(AgvHeader.MAGIC_NUMBER)
                .opcode(opcode)
                .version(AgvHeader.CURRENT_VERSION)
                .sequenceId(sequenceId)
                .lamportTimestamp(lamportTimestamp)
                .senderNodeId(senderNodeId)
                .payloadLength(safePayload.length)
                .crc32Checksum(checksum)
                .build();

        return new AgvMessage(header, safePayload);
    }

    /**
     * Factory method creating a message with empty payload (e.g. Heartbeat, Mutex Release).
     *
     * @param opcode           message operational code
     * @param sequenceId       sequence ID
     * @param lamportTimestamp Lamport clock
     * @param senderNodeId     AGV identifier
     * @return message with empty payload
     */
    public static AgvMessage create(Opcode opcode,
                                    long sequenceId,
                                    long lamportTimestamp,
                                    long senderNodeId) {
        return create(opcode, sequenceId, lamportTimestamp, senderNodeId, EMPTY_PAYLOAD);
    }

    /**
     * Factory method creating a message with a UTF-8 string payload (e.g. JSON task announcement).
     *
     * @param opcode           message operational code
     * @param sequenceId       sequence ID
     * @param lamportTimestamp Lamport clock
     * @param senderNodeId     AGV identifier
     * @param stringPayload    payload string encoded in UTF-8
     * @return message with UTF-8 encoded string payload
     */
    public static AgvMessage create(Opcode opcode,
                                    long sequenceId,
                                    long lamportTimestamp,
                                    long senderNodeId,
                                    String stringPayload) {
        byte[] bytes = (stringPayload == null) ? EMPTY_PAYLOAD : stringPayload.getBytes(StandardCharsets.UTF_8);
        return create(opcode, sequenceId, lamportTimestamp, senderNodeId, bytes);
    }

    /**
     * Factory method wrapping an existing header and payload.
     *
     * @param header  pre-constructed header
     * @param payload byte array payload
     * @return message
     */
    public static AgvMessage of(AgvHeader header, byte[] payload) {
        return new AgvMessage(header, payload);
    }

    /**
     * Calculates the CRC32 checksum for a given byte array.
     *
     * @param data byte array
     * @return unsigned 32-bit CRC32 value represented as long
     */
    public static long calculateCrc32(byte[] data) {
        if (data == null || data.length == 0) {
            return 0L;
        }
        CRC32 crc32 = new CRC32();
        crc32.update(data);
        return crc32.getValue();
    }

    /**
     * Calculates the CRC32 checksum directly from a Netty {@link ByteBuf} without heap copy.
     *
     * @param buffer Netty ByteBuf
     * @param offset starting index in the buffer
     * @param length number of bytes to read
     * @return unsigned 32-bit CRC32 value represented as long
     */
    public static long calculateCrc32(ByteBuf buffer, int offset, int length) {
        if (buffer == null || length <= 0) {
            return 0L;
        }
        CRC32 crc32 = new CRC32();
        if (buffer.hasArray()) {
            crc32.update(buffer.array(), buffer.arrayOffset() + offset, length);
        } else {
            byte[] bytes = new byte[length];
            buffer.getBytes(offset, bytes);
            crc32.update(bytes);
        }
        return crc32.getValue();
    }

    /**
     * Verifies if the payload's CRC32 matches the checksum declared in the header.
     *
     * @return {@code true} if valid, {@code false} otherwise
     */
    public boolean isChecksumValid() {
        return calculateCrc32(this.payload) == header.crc32Checksum();
    }

    public AgvHeader getHeader() {
        return header;
    }

    /**
     * Returns a copy of the payload byte array.
     *
     * @return cloned payload array
     */
    public byte[] getPayload() {
        return payload.clone();
    }

    public int getPayloadLength() {
        return payload.length;
    }

    public Opcode getOpcode() {
        return header.opcode();
    }

    public long getSequenceId() {
        return header.sequenceId();
    }

    public long getLamportTimestamp() {
        return header.lamportTimestamp();
    }

    public long getSenderNodeId() {
        return header.senderNodeId();
    }

    /**
     * Interprets the payload as a UTF-8 string.
     *
     * @return UTF-8 string representation of payload
     */
    public String getPayloadAsString() {
        if (payload.length == 0) {
            return "";
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AgvMessage that = (AgvMessage) o;
        return Objects.equals(header, that.header) && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(header);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }

    @Override
    public String toString() {
        return String.format("AgvMessage[Op=%s, Seq=%d, Lamport=%d, Node=%d, Len=%d, CRC=0x%08X]",
                header.opcode(),
                header.sequenceId(),
                header.lamportTimestamp(),
                header.senderNodeId(),
                header.payloadLength(),
                header.crc32Checksum()
        );
    }
}
