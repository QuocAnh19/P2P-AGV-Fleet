package com.agv.fleet.ui.traffic;

import com.agv.fleet.common.protocol.Opcode;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Immutable data structure representing a single protocol message on the wire.
 */
public record WireMessageEntry(
        String timeStr,
        long lamportTimestamp,
        Opcode opcode,
        long senderNodeId,
        long targetNodeId, // -1 if broadcast
        String detail
) {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static WireMessageEntry of(long lamportTimestamp, Opcode opcode, long senderNodeId, long targetNodeId, String detail) {
        String timeStr = LocalTime.now().format(TIME_FORMATTER);
        return new WireMessageEntry(timeStr, lamportTimestamp, opcode, senderNodeId, targetNodeId, detail);
    }

    public boolean isBroadcast() {
        return targetNodeId < 0;
    }

    public String getRouteString() {
        if (targetNodeId < 0) {
            return "Node " + senderNodeId + " -> BROADCAST";
        } else {
            return "Node " + senderNodeId + " -> Node " + targetNodeId;
        }
    }

    public String toCsvLine() {
        return String.format("%s,%d,%s,%d,%s,\"%s\"",
                timeStr,
                lamportTimestamp,
                opcode != null ? opcode.name() : "UNKNOWN",
                senderNodeId,
                targetNodeId < 0 ? "BROADCAST" : String.valueOf(targetNodeId),
                detail != null ? detail.replace("\"", "\"\"") : ""
        );
    }
}
