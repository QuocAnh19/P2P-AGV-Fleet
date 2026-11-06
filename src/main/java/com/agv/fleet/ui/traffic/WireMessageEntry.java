package com.agv.fleet.ui.traffic;

import com.agv.fleet.common.protocol.AgvHeader;
import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import com.agv.fleet.common.protocol.payload.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Immutable data structure representing a single protocol message on the wire.
 * Contains both wire routing metadata, raw message packet, hex representation,
 * and decoded payload inspection.
 */
public record WireMessageEntry(
        String timeStr,
        long lamportTimestamp,
        Opcode opcode,
        long senderNodeId,
        long targetNodeId, // -1 if broadcast
        String detail,
        int peerCount,     // Number of peers in broadcast, -1 if unicast
        AgvMessage rawMessage
) {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static WireMessageEntry of(long lamportTimestamp, Opcode opcode, long senderNodeId, long targetNodeId, String detail) {
        String timeStr = LocalTime.now().format(TIME_FORMATTER);
        return new WireMessageEntry(timeStr, lamportTimestamp, opcode, senderNodeId, targetNodeId, detail, -1, null);
    }

    public static WireMessageEntry of(long lamportTimestamp, Opcode opcode, long senderNodeId, long targetNodeId, String detail, AgvMessage rawMessage) {
        String timeStr = LocalTime.now().format(TIME_FORMATTER);
        return new WireMessageEntry(timeStr, lamportTimestamp, opcode, senderNodeId, targetNodeId, detail, -1, rawMessage);
    }

    public static WireMessageEntry ofBroadcast(long lamportTimestamp, Opcode opcode, long senderNodeId, int peerCount, String detail, AgvMessage rawMessage) {
        String timeStr = LocalTime.now().format(TIME_FORMATTER);
        return new WireMessageEntry(timeStr, lamportTimestamp, opcode, senderNodeId, -1, detail, peerCount, rawMessage);
    }

    public boolean isBroadcast() {
        return targetNodeId < 0;
    }

    public boolean isBusinessMessage() {
        return WireTrafficLog.isBusinessOpcode(opcode);
    }

    public String getRouteString() {
        if (targetNodeId < 0) {
            if (peerCount > 0) {
                return "Node " + senderNodeId + " -> ALL (" + peerCount + " peers)";
            }
            return "Node " + senderNodeId + " -> ALL";
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
                targetNodeId < 0 ? (peerCount > 0 ? "ALL (" + peerCount + " peers)" : "BROADCAST") : String.valueOf(targetNodeId),
                detail != null ? detail.replace("\"", "\"\"") : ""
        );
    }

    /**
     * Reconstructs or extracts the 36-byte header as raw binary bytes.
     */
    public byte[] getHeaderBytes() {
        if (rawMessage != null && rawMessage.getHeader() != null) {
            AgvHeader h = rawMessage.getHeader();
            ByteBuffer buf = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN);
            buf.putShort(h.magic());
            buf.put(h.opcode() != null ? h.opcode().getCode() : (byte) 0);
            buf.put(h.version());
            buf.putLong(h.sequenceId());
            buf.putLong(h.lamportTimestamp());
            buf.putLong(h.senderNodeId());
            buf.putInt(h.payloadLength());
            buf.putInt((int) (h.crc32Checksum() & 0xFFFFFFFFL));
            return buf.array();
        }

        // Synthesize standard 36-byte header if rawMessage was not provided
        ByteBuffer buf = ByteBuffer.allocate(36).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) 0xCAFE);
        buf.put(opcode != null ? opcode.getCode() : (byte) 0);
        buf.put((byte) 1);
        buf.putLong(1L);
        buf.putLong(lamportTimestamp);
        buf.putLong(senderNodeId);
        buf.putInt(0);
        buf.putInt(0);
        return buf.array();
    }

    /**
     * Formats the 36-byte header into standard hex dump string with byte offsets.
     */
    public String toHexDump() {
        byte[] bytes = getHeaderBytes();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0 && i % 8 == 0) {
                sb.append("  ");
            } else if (i > 0) {
                sb.append(" ");
            }
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }

    /**
     * Returns a structured explanation of every field inside the 36-byte header.
     */
    public String getHeaderExplanation() {
        AgvHeader h = (rawMessage != null) ? rawMessage.getHeader() : null;
        short magic = (h != null) ? h.magic() : (short) 0xCAFE;
        byte opCode = (opcode != null) ? opcode.getCode() : (byte) 0;
        byte ver = (h != null) ? h.version() : 1;
        long seq = (h != null) ? h.sequenceId() : 1L;
        long lmp = lamportTimestamp;
        long snd = senderNodeId;
        int len = (rawMessage != null && rawMessage.getPayload() != null) ? rawMessage.getPayload().length : 0;
        long crc = (h != null) ? h.crc32Checksum() : 0L;

        return String.format(Locale.US,
                "HEADER BREAKDOWN (36 Bytes, Big-Endian):\n" +
                "  [00..01] Magic Number     : 0x%04X %s\n" +
                "  [02]     Opcode           : 0x%02X (%s)\n" +
                "  [03]     Version          : 0x%02X (v%d)\n" +
                "  [04..11] Sequence ID      : %d\n" +
                "  [12..19] Lamport Clock    : %d\n" +
                "  [20..27] Sender Node ID   : Node %d\n" +
                "  [28..31] Payload Length   : %d bytes\n" +
                "  [32..35] CRC32 Checksum   : 0x%08X",
                magic, (magic == (short) 0xCAFE ? "(Valid 0xCAFE)" : "(Invalid)"),
                opCode, (opcode != null ? opcode.name() : "UNKNOWN"),
                ver, ver,
                seq,
                lmp,
                snd,
                len,
                crc
        );
    }

    /**
     * Decodes the payload into a clean, human-readable JSON-like string.
     */
    public String getDecodedPayloadJson() {
        if (rawMessage == null || rawMessage.getPayload() == null || rawMessage.getPayload().length == 0) {
            return "{\n  \"payload\": \"EMPTY (0 bytes)\"\n}";
        }

        byte[] payload = rawMessage.getPayload();
        try {
            switch (opcode) {
                case MUTEX_REQUEST -> {
                    MutexRequestPayload p = MutexRequestPayload.fromBytes(payload);
                    return String.format(Locale.US,
                            "{\n  \"type\": \"MUTEX_REQUEST\",\n  \"cellId\": \"%s\",\n  \"requestTimestamp\": %d\n}",
                            p.cellId(), p.requestTimestamp());
                }
                case MUTEX_REPLY -> {
                    MutexReplyPayload p = MutexReplyPayload.fromBytes(payload);
                    return String.format(Locale.US,
                            "{\n  \"type\": \"MUTEX_REPLY\",\n  \"cellId\": \"%s\"\n}",
                            p.cellId());
                }
                case MUTEX_RELEASE -> {
                    MutexReleasePayload p = MutexReleasePayload.fromBytes(payload);
                    return String.format(Locale.US,
                            "{\n  \"type\": \"MUTEX_RELEASE\",\n  \"cellId\": \"%s\"\n}",
                            p.cellId());
                }
                case TASK_ANNOUNCE -> {
                    TaskAnnouncePayload p = TaskAnnouncePayload.fromBytes(payload);
                    return String.format(Locale.US,
                            "{\n  \"type\": \"TASK_ANNOUNCE\",\n  \"taskId\": \"%s\",\n  \"pickup\": [%.1f, %.1f],\n  \"delivery\": [%.1f, %.1f]\n}",
                            p.taskId(), p.pickupX(), p.pickupY(), p.deliveryX(), p.deliveryY());
                }
                case TASK_BID -> {
                    TaskBidPayload p = TaskBidPayload.fromBytes(payload);
                    return String.format(Locale.US,
                            "{\n  \"type\": \"TASK_BID\",\n  \"taskId\": \"%s\",\n  \"bidderNodeId\": %d,\n  \"cost\": %.4f,\n  \"batteryLevel\": %.1f\n}",
                            p.taskId(), p.bidderNodeId(), p.cost(), p.batteryLevel());
                }
                case TASK_AWARD -> {
                    TaskAwardPayload p = TaskAwardPayload.fromBytes(payload);
                    return String.format(Locale.US,
                            "{\n  \"type\": \"TASK_AWARD\",\n  \"taskId\": \"%s\",\n  \"winnerNodeId\": %d\n}",
                            p.taskId(), p.winnerNodeId());
                }
                case TASK_COMPLETE -> {
                    TaskCompletePayload p = TaskCompletePayload.fromBytes(payload);
                    return String.format(Locale.US,
                            "{\n  \"type\": \"TASK_COMPLETE\",\n  \"taskId\": \"%s\",\n  \"contractorNodeId\": %d\n}",
                            p.taskId(), p.contractorNodeId());
                }
                case POSITION_UPDATE -> {
                    PositionUpdatePayload p = PositionUpdatePayload.fromBytes(payload);
                    return String.format(Locale.US,
                            "{\n  \"type\": \"POSITION_UPDATE\",\n  \"x\": %.2f,\n  \"y\": %.2f,\n  \"targetX\": %.2f,\n  \"targetY\": %.2f,\n  \"state\": \"%s\"\n}",
                            p.x(), p.y(), p.targetX(), p.targetY(), p.state());
                }
                case PING -> {
                    PingPongPayload p = PingPongPayload.fromBytes(payload);
                    return String.format(Locale.US,
                            "{\n  \"type\": \"PING\",\n  \"sendNanoTime\": %d\n}",
                            p.sendNanoTime());
                }
                case PONG -> {
                    PingPongPayload p = PingPongPayload.fromBytes(payload);
                    return String.format(Locale.US,
                            "{\n  \"type\": \"PONG\",\n  \"echoNanoTime\": %d\n}",
                            p.sendNanoTime());
                }
                case HEARTBEAT -> {
                    return "{\n  \"type\": \"HEARTBEAT\"\n}";
                }
                case PEER_DISCOVERY -> {
                    return "{\n  \"type\": \"PEER_DISCOVERY\"\n}";
                }
                default -> {
                    return String.format(Locale.US,
                            "{\n  \"opcode\": \"%s\",\n  \"rawBytesLength\": %d\n}",
                            opcode != null ? opcode.name() : "UNKNOWN", payload.length);
                }
            }
        } catch (Exception e) {
            return String.format(Locale.US,
                    "{\n  \"error\": \"Failed to decode payload: %s\",\n  \"length\": %d\n}",
                    e.getMessage(), payload.length);
        }
    }
}
