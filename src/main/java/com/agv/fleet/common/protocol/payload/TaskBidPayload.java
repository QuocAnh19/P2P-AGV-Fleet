package com.agv.fleet.common.protocol.payload;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Binary payload for TASK_BID (Opcode 0x21) in the Contract Net Protocol (CNP).
 *
 * @param taskId       identifier of the auctioned task
 * @param bidderNodeId Node ID of the bidder AGV
 * @param cost         calculated cost score (lower is better)
 * @param batteryLevel current battery percentage (0.0 to 100.0)
 */
public record TaskBidPayload(
        String taskId,
        long bidderNodeId,
        double cost,
        double batteryLevel
) {

    public TaskBidPayload {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId cannot be empty");
        }
    }

    /**
     * Serializes this payload into a compact binary byte array.
     * <pre>
     * [8B: bidderNodeId] + [8B: cost] + [8B: batteryLevel] + [2B: taskId length] + [N bytes: taskId UTF-8]
     * </pre>
     *
     * @return binary byte array
     */
    public byte[] toBytes() {
        byte[] taskIdBytes = taskId.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer(8 + 8 + 8 + 2 + taskIdBytes.length);
        buf.writeLong(bidderNodeId);
        buf.writeDouble(cost);
        buf.writeDouble(batteryLevel);
        buf.writeShort(taskIdBytes.length);
        buf.writeBytes(taskIdBytes);
        byte[] result = new byte[buf.readableBytes()];
        buf.readBytes(result);
        buf.release();
        return result;
    }

    /**
     * Deserializes a {@link TaskBidPayload} from a Netty {@link ByteBuf}.
     *
     * @param buf ByteBuf containing payload bytes
     * @return deserialized payload
     */
    public static TaskBidPayload fromByteBuf(ByteBuf buf) {
        if (buf.readableBytes() < 26) { // 8*3 + 2 = 26 bytes minimum
            throw new IllegalArgumentException("ByteBuf too short for TaskBidPayload: " + buf.readableBytes());
        }
        long bidderNodeId = buf.readLong();
        double cost = buf.readDouble();
        double batteryLevel = buf.readDouble();
        int taskIdLength = buf.readUnsignedShort();
        if (buf.readableBytes() < taskIdLength) {
            throw new IllegalArgumentException("Truncated taskId in TaskBidPayload");
        }
        byte[] taskIdBytes = new byte[taskIdLength];
        buf.readBytes(taskIdBytes);
        String taskId = new String(taskIdBytes, StandardCharsets.UTF_8);
        return new TaskBidPayload(taskId, bidderNodeId, cost, batteryLevel);
    }

    /**
     * Deserializes a {@link TaskBidPayload} from a byte array.
     *
     * @param bytes binary data
     * @return deserialized payload
     */
    public static TaskBidPayload fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Bytes cannot be null or empty for TaskBidPayload");
        }
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            return fromByteBuf(buf);
        } finally {
            buf.release();
        }
    }
}
