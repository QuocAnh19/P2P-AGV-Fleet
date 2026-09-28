package com.agv.fleet.network.codec;

import com.agv.fleet.common.protocol.AgvHeader;
import com.agv.fleet.common.protocol.AgvMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Netty encoder that serializes an {@link AgvMessage} into binary format according to the specification.
 * <p>
 * Frame Structure (Big-Endian):
 * <pre>
 * +----------------+-------------+---------------------------------------------+
 * | Field          | Size (Byte) | Content                                     |
 * +----------------+-------------+---------------------------------------------+
 * | Magic Number   | 2           | 0xCAFE                                      |
 * | Opcode         | 1           | Operational code identifier                 |
 * | Version        | 1           | Protocol version (0x01)                     |
 * | Sequence ID    | 8           | Sequence number (long)                      |
 * | Lamport Clock  | 8           | Lamport timestamp (long)                    |
 * | Sender Node ID | 8           | Sending AGV identifier (long)               |
 * | Payload Length | 4           | Payload length in bytes (int)               |
 * | CRC32 Checksum | 4           | Unsigned 32-bit CRC32 checksum (int)        |
 * | Payload Data   | N           | Variable binary payload (N = Payload Length)|
 * +----------------+-------------+---------------------------------------------+
 * Total Header: 36 Bytes + N Bytes Payload
 * </pre>
 */
public class AgvPacketEncoder extends MessageToByteEncoder<AgvMessage> {

    private static final Logger log = LoggerFactory.getLogger(AgvPacketEncoder.class);

    @Override
    protected void encode(ChannelHandlerContext ctx, AgvMessage msg, ByteBuf out) throws Exception {
        Objects.requireNonNull(msg, "AgvMessage to encode cannot be null");
        AgvHeader header = msg.getHeader();
        Objects.requireNonNull(header, "AgvHeader cannot be null");

        byte[] payload = msg.getPayload();
        int payloadLength = (payload != null) ? payload.length : 0;

        if (header.payloadLength() != payloadLength) {
            throw new IllegalArgumentException(String.format(
                    "Payload length mismatch in header (%d) vs actual message (%d)",
                    header.payloadLength(), payloadLength
            ));
        }

        if (log.isTraceEnabled()) {
            log.trace("Encoding AGV packet: Opcode={}, SeqId={}, NodeId={}, PayloadLength={}",
                    header.opcode(), header.sequenceId(), header.senderNodeId(), payloadLength);
        }

        // 1. Magic Number (2 Bytes, Short)
        out.writeShort(header.magic());

        // 2. Opcode (1 Byte)
        out.writeByte(header.opcode().getCode());

        // 3. Version (1 Byte)
        out.writeByte(header.version());

        // 4. Sequence ID (8 Bytes, Long)
        out.writeLong(header.sequenceId());

        // 5. Lamport Timestamp (8 Bytes, Long)
        out.writeLong(header.lamportTimestamp());

        // 6. Sender Node ID (8 Bytes, Long)
        out.writeLong(header.senderNodeId());

        // 7. Payload Length (4 Bytes, Int)
        out.writeInt(payloadLength);

        // 8. CRC32 Checksum (4 Bytes, Int / Unsigned int)
        out.writeInt((int) (header.crc32Checksum() & 0xFFFFFFFFL));

        // 9. Payload Data (N Bytes)
        if (payloadLength > 0) {
            out.writeBytes(payload);
        }
    }
}
