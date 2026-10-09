package com.agv.fleet.common.protocol.payload;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Binary payload for TASK_AWARD (Opcode 0x22) in the Contract Net Protocol (CNP).
 *
 * @param taskId       identifier of the awarded task
 * @param winnerNodeId Node ID of the winning AGV awarded the task
 */
public record TaskAwardPayload(
        String taskId,
        long winnerNodeId
) {

    public TaskAwardPayload {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId cannot be empty");
        }
    }

    /**
     * Serializes this payload into a compact binary byte array.
     * <pre>
     * [8B: winnerNodeId] + [2B: taskId length] + [N bytes: taskId UTF-8]
     * </pre>
     *
     * @return binary byte array
     */
    public byte[] toBytes() {
        byte[] taskIdBytes = taskId.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer(8 + 2 + taskIdBytes.length);
        buf.writeLong(winnerNodeId);
        buf.writeShort(taskIdBytes.length);
        buf.writeBytes(taskIdBytes);
        byte[] result = new byte[buf.readableBytes()];
        buf.readBytes(result);
        buf.release();
        return result;
    }

    /**
     * Deserializes a {@link TaskAwardPayload} from a Netty {@link ByteBuf}.
     *
     * @param buf ByteBuf containing payload bytes
     * @return deserialized payload
     */
    public static TaskAwardPayload fromByteBuf(ByteBuf buf) {
        if (buf.readableBytes() < 10) { // 8 + 2 = 10 bytes minimum
            throw new IllegalArgumentException("ByteBuf too short for TaskAwardPayload: " + buf.readableBytes());
        }
        long winnerNodeId = buf.readLong();
        int taskIdLength = buf.readUnsignedShort();
        if (buf.readableBytes() < taskIdLength) {
            throw new IllegalArgumentException("Truncated taskId in TaskAwardPayload");
        }
        byte[] taskIdBytes = new byte[taskIdLength];
        buf.readBytes(taskIdBytes);
        String taskId = new String(taskIdBytes, StandardCharsets.UTF_8);
        return new TaskAwardPayload(taskId, winnerNodeId);
    }

    /**
     * Deserializes a {@link TaskAwardPayload} from a byte array.
     *
     * @param bytes binary data
     * @return deserialized payload
     */
    public static TaskAwardPayload fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Bytes cannot be null or empty for TaskAwardPayload");
        }
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            return fromByteBuf(buf);
        } finally {
            buf.release();
        }
    }
}
