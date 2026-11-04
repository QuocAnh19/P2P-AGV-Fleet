package com.agv.fleet.common.protocol.payload;

import com.agv.fleet.core.agent.AgentState;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.Objects;

/**
 * Binary payload for POSITION_UPDATE (Opcode 0x03).
 * Broadcast by an AGV whenever it traverses into a new grid cell.
 *
 * @param x       current x coordinate
 * @param y       current y coordinate
 * @param targetX target destination x coordinate
 * @param targetY target destination y coordinate
 * @param state   current agent operational state
 */
public record PositionUpdatePayload(
        double x,
        double y,
        double targetX,
        double targetY,
        AgentState state
) {

    public PositionUpdatePayload {
        state = (state == null) ? AgentState.IDLE : state;
    }

    /**
     * Serializes this payload into a fixed 33-byte binary layout:
     * [8B: x] + [8B: y] + [8B: targetX] + [8B: targetY] + [1B: state ordinal]
     */
    public byte[] toBytes() {
        ByteBuf buf = Unpooled.buffer(33);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(targetX);
        buf.writeDouble(targetY);
        buf.writeByte((byte) state.ordinal());
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        return bytes;
    }

    public static PositionUpdatePayload fromByteBuf(ByteBuf buf) {
        if (buf.readableBytes() < 33) {
            throw new IllegalArgumentException("ByteBuf too short for PositionUpdatePayload: " + buf.readableBytes());
        }
        double x = buf.readDouble();
        double y = buf.readDouble();
        double targetX = buf.readDouble();
        double targetY = buf.readDouble();
        byte stateOrdinal = buf.readByte();
        AgentState[] states = AgentState.values();
        AgentState state = (stateOrdinal >= 0 && stateOrdinal < states.length) ? states[stateOrdinal] : AgentState.IDLE;
        return new PositionUpdatePayload(x, y, targetX, targetY, state);
    }

    public static PositionUpdatePayload fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 33) {
            throw new IllegalArgumentException("Bytes invalid for PositionUpdatePayload");
        }
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            return fromByteBuf(buf);
        } finally {
            buf.release();
        }
    }
}
