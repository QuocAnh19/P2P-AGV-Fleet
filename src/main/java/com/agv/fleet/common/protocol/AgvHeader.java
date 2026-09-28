package com.agv.fleet.common.protocol;

import java.util.Objects;

/**
 * Fixed 36-byte immutable Header for the AGV Fleet binary protocol.
 * <p>
 * Binary Layout (Big-Endian):
 * <pre>
 * +-----------------------+---------------------+
 * | Field                 | Size &amp; Type         |
 * +-----------------------+---------------------+
 * | Magic Number          | 2 Bytes (Short)     |
 * | Opcode                | 1 Byte              |
 * | Version               | 1 Byte              |
 * | Sequence ID           | 8 Bytes (Long)      |
 * | Lamport Timestamp     | 8 Bytes (Long)      |
 * | Sender Node ID        | 8 Bytes (Long)      |
 * | Payload Length (N)    | 4 Bytes (Int)       |
 * | CRC32 Checksum        | 4 Bytes (Unsigned)  |
 * +-----------------------+---------------------+
 * | Total Fixed Header    | 36 Bytes            |
 * +-----------------------+---------------------+
 * </pre>
 *
 * @param magic            Protocol magic number (0xCAFE)
 * @param opcode           Operational code representing message intent
 * @param version          Protocol version (default 0x01)
 * @param sequenceId       Monotonically increasing sequence ID per sender
 * @param lamportTimestamp Logical Lamport clock value for event ordering
 * @param senderNodeId     Unique 64-bit ID of the sending AGV node
 * @param payloadLength    Size of the variable payload in bytes (0 &lt;= N &lt;= 10MB)
 * @param crc32Checksum    CRC32 checksum of the payload (represented as unsigned 32-bit in long)
 */
public record AgvHeader(
        short magic,
        Opcode opcode,
        byte version,
        long sequenceId,
        long lamportTimestamp,
        long senderNodeId,
        int payloadLength,
        long crc32Checksum
) {

    /**
     * Standard Magic Number to distinguish valid AGV protocol frames: 0xCAFE.
     */
    public static final short MAGIC_NUMBER = (short) 0xCAFE;

    /**
     * Current Protocol Version: 1.
     */
    public static final byte CURRENT_VERSION = 0x01;

    /**
     * Total fixed header length in bytes.
     */
    public static final int HEADER_LENGTH = 36;

    /**
     * Compact constructor validating constraints.
     */
    public AgvHeader {
        Objects.requireNonNull(opcode, "Opcode cannot be null");
        if (payloadLength < 0) {
            throw new IllegalArgumentException("Payload length cannot be negative: " + payloadLength);
        }
    }

    /**
     * Alias getter for opcode for compatibility with JavaBeans convention.
     */
    public Opcode getOpcode() {
        return opcode;
    }

    /**
     * Alias getter for magic number.
     */
    public short getMagic() {
        return magic;
    }

    /**
     * Alias getter for version.
     */
    public byte getVersion() {
        return version;
    }

    /**
     * Alias getter for sequence ID.
     */
    public long getSequenceId() {
        return sequenceId;
    }

    /**
     * Alias getter for Lamport timestamp.
     */
    public long getLamportTimestamp() {
        return lamportTimestamp;
    }

    /**
     * Alias getter for sender node ID.
     */
    public long getSenderNodeId() {
        return senderNodeId;
    }

    /**
     * Alias getter for payload length.
     */
    public int getPayloadLength() {
        return payloadLength;
    }

    /**
     * Alias getter for CRC32 checksum.
     */
    public long getCrc32Checksum() {
        return crc32Checksum;
    }

    /**
     * Creates a new Builder instance for {@link AgvHeader}.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for fluent and safe {@link AgvHeader} construction.
     */
    public static final class Builder {
        private short magic = MAGIC_NUMBER;
        private Opcode opcode;
        private byte version = CURRENT_VERSION;
        private long sequenceId;
        private long lamportTimestamp;
        private long senderNodeId;
        private int payloadLength;
        private long crc32Checksum;

        public Builder magic(short magic) {
            this.magic = magic;
            return this;
        }

        public Builder opcode(Opcode opcode) {
            this.opcode = opcode;
            return this;
        }

        public Builder version(byte version) {
            this.version = version;
            return this;
        }

        public Builder sequenceId(long sequenceId) {
            this.sequenceId = sequenceId;
            return this;
        }

        public Builder lamportTimestamp(long lamportTimestamp) {
            this.lamportTimestamp = lamportTimestamp;
            return this;
        }

        public Builder senderNodeId(long senderNodeId) {
            this.senderNodeId = senderNodeId;
            return this;
        }

        public Builder payloadLength(int payloadLength) {
            this.payloadLength = payloadLength;
            return this;
        }

        public Builder crc32Checksum(long crc32Checksum) {
            this.crc32Checksum = crc32Checksum;
            return this;
        }

        public AgvHeader build() {
            return new AgvHeader(
                    magic,
                    opcode,
                    version,
                    sequenceId,
                    lamportTimestamp,
                    senderNodeId,
                    payloadLength,
                    crc32Checksum
            );
        }
    }
}
