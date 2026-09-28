package com.agv.fleet.common.protocol.payload;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Binary payload for MUTEX_RELEASE (Opcode 0x12) in the Ricart-Agrawala algorithm.
 *
 * @param cellId         the identifier of the critical section/intersection cell
 * @param releaserNodeId the node ID of the AGV releasing the critical section
 */
public record MutexReleasePayload(
        String cellId,
        long releaserNodeId
) {

    public MutexReleasePayload {
        Objects.requireNonNull(cellId, "cellId cannot be null");
        if (cellId.isBlank()) {
            throw new IllegalArgumentException("cellId cannot be empty");
        }
    }

    /**
     * Serializes this payload into a compact binary byte array.
     * <pre>
     * [8 bytes: releaserNodeId] + [2 bytes: cellId length] + [N bytes: cellId UTF-8]
     * </pre>
     *
     * @return binary byte array
     */
    public byte[] toBytes() {
        byte[] cellIdBytes = cellId.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer(8 + 2 + cellIdBytes.length);
        buf.writeLong(releaserNodeId);
        buf.writeShort(cellIdBytes.length);
        buf.writeBytes(cellIdBytes);
        byte[] result = new byte[buf.readableBytes()];
        buf.readBytes(result);
        buf.release();
        return result;
    }

    /**
     * Deserializes a {@link MutexReleasePayload} from a Netty {@link ByteBuf}.
     *
     * @param buf ByteBuf containing payload bytes
     * @return deserialized payload
     */
    public static MutexReleasePayload fromByteBuf(ByteBuf buf) {
        if (buf.readableBytes() < 10) { // 8 + 2 = 10 bytes minimum
            throw new IllegalArgumentException("ByteBuf too short for MutexReleasePayload: " + buf.readableBytes());
        }
        long releaserNodeId = buf.readLong();
        int cellIdLength = buf.readUnsignedShort();
        if (buf.readableBytes() < cellIdLength) {
            throw new IllegalArgumentException("Truncated cellId in MutexReleasePayload");
        }
        byte[] cellIdBytes = new byte[cellIdLength];
        buf.readBytes(cellIdBytes);
        String cellId = new String(cellIdBytes, StandardCharsets.UTF_8);
        return new MutexReleasePayload(cellId, releaserNodeId);
    }

    /**
     * Deserializes a {@link MutexReleasePayload} from a byte array.
     *
     * @param bytes binary data
     * @return deserialized payload
     */
    public static MutexReleasePayload fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Bytes cannot be null or empty for MutexReleasePayload");
        }
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            return fromByteBuf(buf);
        } finally {
            buf.release();
        }
    }
}
