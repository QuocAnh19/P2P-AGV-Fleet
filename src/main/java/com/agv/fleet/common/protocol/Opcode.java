package com.agv.fleet.common.protocol;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Enumeration of operation codes (Opcode) used in the P2P AGV Fleet binary protocol.
 * <p>
 * Opcodes define the semantic intent of each frame transmitted over the P2P event bus:
 * <ul>
 *   <li>Node Discovery &amp; Membership: {@link #PEER_DISCOVERY}, {@link #HEARTBEAT}</li>
 *   <li>Distributed Mutual Exclusion (Ricart-Agrawala): {@link #MUTEX_REQUEST}, {@link #MUTEX_REPLY}, {@link #MUTEX_RELEASE}</li>
 *   <li>Decentralized Task Allocation (Contract Net Protocol): {@link #TASK_ANNOUNCE}, {@link #TASK_BID}, {@link #TASK_AWARD}</li>
 * </ul>
 */
public enum Opcode {

    /**
     * Peer discovery announcement or query among AGV nodes.
     */
    PEER_DISCOVERY((byte) 0x01, "Peer Discovery"),

    /**
     * Heartbeat message to maintain node liveness in P2P mesh network.
     */
    HEARTBEAT((byte) 0x02, "Node Heartbeat"),

    /**
     * Distributed Mutual Exclusion Request (Ricart-Agrawala algorithm for intersection/path claim).
     */
    MUTEX_REQUEST((byte) 0x10, "Mutex Request"),

    /**
     * Distributed Mutual Exclusion Reply/Grant.
     */
    MUTEX_REPLY((byte) 0x11, "Mutex Reply"),

    /**
     * Distributed Mutual Exclusion Release notification.
     */
    MUTEX_RELEASE((byte) 0x12, "Mutex Release"),

    /**
     * Task announcement broadcast by initiator AGV (Contract Net Protocol).
     */
    TASK_ANNOUNCE((byte) 0x20, "Task Announce"),

    /**
     * Task bid response submitted by bidder AGV.
     */
    TASK_BID((byte) 0x21, "Task Bid"),

    /**
     * Task award notification assigned to winning bidder AGV.
     */
    TASK_AWARD((byte) 0x22, "Task Award"),

    /**
     * Task completion notification sent by contractor AGV to task announcer.
     */
    TASK_COMPLETE((byte) 0x23, "Task Complete");

    private final byte code;
    private final String description;

    private static final Map<Byte, Opcode> CODE_MAP;

    static {
        Map<Byte, Opcode> map = new HashMap<>();
        for (Opcode op : values()) {
            map.put(op.code, op);
        }
        CODE_MAP = Collections.unmodifiableMap(map);
    }

    Opcode(byte code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Retrieves the 1-byte raw code of this opcode.
     *
     * @return the byte representation
     */
    public byte getCode() {
        return code;
    }

    /**
     * Retrieves the human-readable description of this opcode.
     *
     * @return operation description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Resolves an {@link Opcode} from its single-byte representation.
     *
     * @param code raw byte value
     * @return matched {@link Opcode}
     * @throws IllegalArgumentException if the byte value is not a recognized opcode
     */
    public static Opcode fromByte(byte code) {
        Opcode opcode = CODE_MAP.get(code);
        if (opcode == null) {
            throw new IllegalArgumentException(
                    String.format("Unknown Opcode byte: 0x%02X (%d)", code & 0xFF, code)
            );
        }
        return opcode;
    }

    /**
     * Checks whether a byte corresponds to a registered opcode.
     *
     * @param code raw byte value
     * @return {@code true} if valid, {@code false} otherwise
     */
    public static boolean isValid(byte code) {
        return CODE_MAP.containsKey(code);
    }

    @Override
    public String toString() {
        return String.format("%s(0x%02X)", name(), code & 0xFF);
    }
}
