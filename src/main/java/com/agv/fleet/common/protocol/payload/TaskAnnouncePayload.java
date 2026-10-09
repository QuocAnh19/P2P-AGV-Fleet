package com.agv.fleet.common.protocol.payload;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Binary payload for TASK_ANNOUNCE (Opcode 0x20) in the Contract Net Protocol (CNP).
 *
 * @param taskId          unique identifier of the transport task
 * @param pickupX         X-coordinate of pickup location
 * @param pickupY         Y-coordinate of pickup location
 * @param deliveryX       X-coordinate of delivery destination
 * @param deliveryY       Y-coordinate of delivery destination
 * @param announcerNodeId Node ID of the agent announcing the task
 */
public record TaskAnnouncePayload(
        String taskId,
        double pickupX,
        double pickupY,
        double deliveryX,
        double deliveryY,
        long announcerNodeId
) {

    public TaskAnnouncePayload {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId cannot be empty");
        }
    }

    /**
     * Serializes this payload into a compact binary byte array.
     * <pre>
     * [8B: announcerNodeId] + [8B: pickupX] + [8B: pickupY] + [8B: deliveryX] + [8B: deliveryY]
     * + [2B: taskId length] + [N bytes: taskId UTF-8]
     * </pre>
     *
     * @return binary byte array
     */
    public byte[] toBytes() {
        byte[] taskIdBytes = taskId.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer(8 + 8 + 8 + 8 + 8 + 2 + taskIdBytes.length);
        buf.writeLong(announcerNodeId);
        buf.writeDouble(pickupX);
        buf.writeDouble(pickupY);
        buf.writeDouble(deliveryX);
        buf.writeDouble(deliveryY);
        buf.writeShort(taskIdBytes.length);
        buf.writeBytes(taskIdBytes);
        byte[] result = new byte[buf.readableBytes()];
        buf.readBytes(result);
        buf.release();
        return result;
    }

    /**
     * Deserializes a {@link TaskAnnouncePayload} from a Netty {@link ByteBuf}.
     *
     * @param buf ByteBuf containing payload bytes
     * @return deserialized payload
     */
    public static TaskAnnouncePayload fromByteBuf(ByteBuf buf) {
        if (buf.readableBytes() < 42) { // 8*5 + 2 = 42 bytes minimum
            throw new IllegalArgumentException("ByteBuf too short for TaskAnnouncePayload: " + buf.readableBytes());
        }
        long announcerNodeId = buf.readLong();
        double pickupX = buf.readDouble();
        double pickupY = buf.readDouble();
        double deliveryX = buf.readDouble();
        double deliveryY = buf.readDouble();
        int taskIdLength = buf.readUnsignedShort();
        if (buf.readableBytes() < taskIdLength) {
            throw new IllegalArgumentException("Truncated taskId in TaskAnnouncePayload");
        }
        byte[] taskIdBytes = new byte[taskIdLength];
        buf.readBytes(taskIdBytes);
        String taskId = new String(taskIdBytes, StandardCharsets.UTF_8);
        return new TaskAnnouncePayload(taskId, pickupX, pickupY, deliveryX, deliveryY, announcerNodeId);
    }

    /**
     * Deserializes a {@link TaskAnnouncePayload} from a byte array.
     *
     * @param bytes binary data
     * @return deserialized payload
     */
    public static TaskAnnouncePayload fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Bytes cannot be null or empty for TaskAnnouncePayload");
        }
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            return fromByteBuf(buf);
        } finally {
            buf.release();
        }
    }
}
