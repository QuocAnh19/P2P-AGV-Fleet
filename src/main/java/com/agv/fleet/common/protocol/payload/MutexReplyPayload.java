package com.agv.fleet.common.protocol.payload;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Binary payload for MUTEX_REPLY (Opcode 0x11) in the Ricart-Agrawala algorithm.
 *
 * @param cellId          the identifier of the critical section/intersection cell
 * @param responderNodeId the node ID of the AGV granting permission
 */
public record MutexReplyPayload(
        String cellId,
        long responderNodeId
) {

    public MutexReplyPayload {
        Objects.requireNonNull(cellId, "cellId cannot be null");
        if (cellId.isBlank()) {
            throw new IllegalArgumentException("cellId cannot be empty");
        }
    }

    /**
     * Serializes this payload into a compact binary byte array.
     * <pre>
     * [8 bytes: responderNodeId] + [2 bytes: cellId length] + [N bytes: cellId UTF-8]
     * </pre>
     *
     * @return binary byte array
     */
    public byte[] toBytes() {
        byte[] cellIdBytes = cellId.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer(8 + 2 + cellIdBytes.length);
        buf.writeLong(responderNodeId);
        buf.writeShort(cellIdBytes.length);
        buf.writeBytes(cellIdBytes);
        byte[] result = new byte[buf.readableBytes()];
        buf.readBytes(result);
        buf.release();
        return result;
    }

    /**
     * Deserializes a {@link MutexReplyPayload} from a Netty {@link ByteBuf}.
     *
     * @param buf ByteBuf containing payload bytes
     * @return deserialized payload
     */
    public static MutexReplyPayload fromByteBuf(ByteBuf buf) {
        if (buf.readableBytes() < 10) { // 8 + 2 = 10 bytes minimum
            throw new IllegalArgumentException("ByteBuf too short for MutexReplyPayload: " + buf.readableBytes());
        }
        long responderNodeId = buf.readLong();
        int cellIdLength = buf.readUnsignedShort();
        if (buf.readableBytes() < cellIdLength) {
            throw new IllegalArgumentException("Truncated cellId in MutexReplyPayload");
        }
        byte[] cellIdBytes = new byte[cellIdLength];
        buf.readBytes(cellIdBytes);
        String cellId = new String(cellIdBytes, StandardCharsets.UTF_8);
        return new MutexReplyPayload(cellId, responderNodeId);
    }

    /**
     * Deserializes a {@link MutexReplyPayload} from a byte array.
     *
     * @param bytes binary data
     * @return deserialized payload
     */
    public static MutexReplyPayload fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Bytes cannot be null or empty for MutexReplyPayload");
        }
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            return fromByteBuf(buf);
        } finally {
            buf.release();
        }
    }
}
