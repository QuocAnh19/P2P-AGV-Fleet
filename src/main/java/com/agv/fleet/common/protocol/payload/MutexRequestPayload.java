package com.agv.fleet.common.protocol.payload;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Binary payload for MUTEX_REQUEST (Opcode 0x10) in the Ricart-Agrawala algorithm.
 *
 * @param cellId           the identifier of the critical section/intersection cell
 * @param requestTimestamp the Lamport timestamp at the moment the request was issued
 * @param requesterNodeId  the node ID of the AGV requesting the critical section
 */
public record MutexRequestPayload(
        String cellId,
        long requestTimestamp,
        long requesterNodeId
) {

    public MutexRequestPayload {
        Objects.requireNonNull(cellId, "cellId cannot be null");
        if (cellId.isBlank()) {
            throw new IllegalArgumentException("cellId cannot be empty");
        }
    }

    /**
     * Serializes this payload into a compact binary byte array.
     * <pre>
     * [8 bytes: requestTimestamp] + [8 bytes: requesterNodeId] + [2 bytes: cellId length] + [N bytes: cellId UTF-8]
     * </pre>
     *
     * @return binary byte array
     */
    public byte[] toBytes() {
        byte[] cellIdBytes = cellId.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer(8 + 8 + 2 + cellIdBytes.length);
        buf.writeLong(requestTimestamp);
        buf.writeLong(requesterNodeId);
        buf.writeShort(cellIdBytes.length);
        buf.writeBytes(cellIdBytes);
        byte[] result = new byte[buf.readableBytes()];
        buf.readBytes(result);
        buf.release();
        return result;
    }

    /**
     * Deserializes a {@link MutexRequestPayload} from a Netty {@link ByteBuf}.
     *
     * @param buf ByteBuf containing payload bytes
     * @return deserialized payload
     */
    public static MutexRequestPayload fromByteBuf(ByteBuf buf) {
        if (buf.readableBytes() < 18) { // 8 + 8 + 2 = 18 bytes minimum
            throw new IllegalArgumentException("ByteBuf too short for MutexRequestPayload: " + buf.readableBytes());
        }
        long requestTimestamp = buf.readLong();
        long requesterNodeId = buf.readLong();
        int cellIdLength = buf.readUnsignedShort();
        if (buf.readableBytes() < cellIdLength) {
            throw new IllegalArgumentException("Truncated cellId in MutexRequestPayload");
        }
        byte[] cellIdBytes = new byte[cellIdLength];
        buf.readBytes(cellIdBytes);
        String cellId = new String(cellIdBytes, StandardCharsets.UTF_8);
        return new MutexRequestPayload(cellId, requestTimestamp, requesterNodeId);
    }

    /**
     * Deserializes a {@link MutexRequestPayload} from a byte array.
     *
     * @param bytes binary data
     * @return deserialized payload
     */
    public static MutexRequestPayload fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Bytes cannot be null or empty for MutexRequestPayload");
        }
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            return fromByteBuf(buf);
        } finally {
            buf.release();
        }
    }
}
