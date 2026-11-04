package com.agv.fleet.common.protocol.payload;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Binary payload for TASK_COMPLETE (Opcode 0x23) in the Contract Net Protocol (CNP).
 * Sent by the contractor AGV to notify the task announcer that transport is finished.
 *
 * @param taskId           identifier of the completed task
 * @param contractorNodeId Node ID of the contractor AGV that executed the task
 */
public record TaskCompletePayload(
        String taskId,
        long contractorNodeId
) {

    public TaskCompletePayload {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId cannot be empty");
        }
    }

    /**
     * Serializes this payload into a compact binary byte array.
     * <pre>
     * [8B: contractorNodeId] + [2B: taskId length] + [N bytes: taskId UTF-8]
     * </pre>
     *
     * @return binary byte array
     */
    public byte[] toBytes() {
        byte[] taskIdBytes = taskId.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer(8 + 2 + taskIdBytes.length);
        buf.writeLong(contractorNodeId);
        buf.writeShort(taskIdBytes.length);
        buf.writeBytes(taskIdBytes);
        byte[] result = new byte[buf.readableBytes()];
        buf.readBytes(result);
        buf.release();
        return result;
    }

    /**
     * Deserializes a {@link TaskCompletePayload} from a Netty {@link ByteBuf}.
     *
     * @param buf ByteBuf containing payload bytes
     * @return deserialized payload
     */
    public static TaskCompletePayload fromByteBuf(ByteBuf buf) {
        if (buf.readableBytes() < 10) { // 8 + 2 = 10 bytes minimum
            throw new IllegalArgumentException("ByteBuf too short for TaskCompletePayload: " + buf.readableBytes());
        }
        long contractorNodeId = buf.readLong();
        int taskIdLength = buf.readUnsignedShort();
        if (buf.readableBytes() < taskIdLength) {
            throw new IllegalArgumentException("Truncated taskId in TaskCompletePayload");
        }
        byte[] taskIdBytes = new byte[taskIdLength];
        buf.readBytes(taskIdBytes);
        String taskId = new String(taskIdBytes, StandardCharsets.UTF_8);
        return new TaskCompletePayload(taskId, contractorNodeId);
    }

    /**
     * Deserializes a {@link TaskCompletePayload} from a byte array.
     *
     * @param bytes byte array containing serialized payload
     * @return deserialized payload
     */
    public static TaskCompletePayload fromBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes cannot be null");
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            return fromByteBuf(buf);
        } finally {
            buf.release();
        }
    }
}
