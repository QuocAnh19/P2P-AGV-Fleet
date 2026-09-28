package com.agv.fleet.network.codec;

import com.agv.fleet.common.protocol.AgvHeader;
import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty frame decoder for the AGV Fleet binary protocol.
 * <p>
 * Inherits from {@link LengthFieldBasedFrameDecoder} to handle TCP framing issues (sticky packets, packet fragmentation)
 * based on the following frame configuration:
 * <ul>
 *   <li><b>maxFrameLength:</b> 10MB (10 * 1024 * 1024 bytes)</li>
 *   <li><b>lengthFieldOffset:</b> 28 (after 2 + 1 + 1 + 8 + 8 + 8 = 28 bytes)</li>
 *   <li><b>lengthFieldLength:</b> 4 (payload length field is 4 bytes)</li>
 *   <li><b>lengthAdjustment:</b> 4 (CRC32 checksum of 4 bytes follows the length field before payload data)</li>
 *   <li><b>initialBytesToStrip:</b> 0 (preserves the full 36-byte header and payload for verification)</li>
 * </ul>
 * <p>
 * Once a complete frame is sliced, this decoder:
 * <ol>
 *   <li>Validates the 2-byte Magic Number (0xCAFE)</li>
 *   <li>Resolves and validates the 1-byte Opcode</li>
 *   <li>Extracts protocol fields (version, sequenceId, lamportTimestamp, senderNodeId, payloadLength, crc32Checksum)</li>
 *   <li>Calculates CRC32 over the extracted payload bytes and verifies integrity</li>
 *   <li>Throws {@link CorruptedFrameException} if magic number, opcode, or CRC32 fails</li>
 *   <li>Produces a valid, immutable {@link AgvMessage}</li>
 * </ol>
 */
public class AgvPacketDecoder extends LengthFieldBasedFrameDecoder {

    private static final Logger log = LoggerFactory.getLogger(AgvPacketDecoder.class);

    /**
     * Maximum frame size allowed (10 MB).
     */
    public static final int DEFAULT_MAX_FRAME_LENGTH = 10 * 1024 * 1024;

    /**
     * Offset of the length field:
     * Magic (2) + Opcode (1) + Version (1) + SeqId (8) + Lamport (8) + NodeId (8) = 28 bytes.
     */
    public static final int LENGTH_FIELD_OFFSET = 28;

    /**
     * Length field is a 32-bit signed integer (4 bytes).
     */
    public static final int LENGTH_FIELD_LENGTH = 4;

    /**
     * Adjustment because the 4-byte CRC32 checksum resides after Payload Length before Payload Data:
     * Frame length = lengthFieldOffset + lengthFieldLength + lengthAdjustment + lengthFieldValue
     *              = 28 + 4 + 4 + N = 36 + N.
     */
    public static final int LENGTH_ADJUSTMENT = 4;

    /**
     * Do not strip any header bytes so the entire frame (Header + Payload) is available for decoding.
     */
    public static final int INITIAL_BYTES_TO_STRIP = 0;

    /**
     * Creates a decoder with default maximum frame length of 10MB.
     */
    public AgvPacketDecoder() {
        this(DEFAULT_MAX_FRAME_LENGTH);
    }

    /**
     * Creates a decoder with specified maximum frame length.
     *
     * @param maxFrameLength maximum allowed frame size in bytes
     */
    public AgvPacketDecoder(int maxFrameLength) {
        super(
                maxFrameLength,
                LENGTH_FIELD_OFFSET,
                LENGTH_FIELD_LENGTH,
                LENGTH_ADJUSTMENT,
                INITIAL_BYTES_TO_STRIP
        );
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        Object decoded = super.decode(ctx, in);
        if (decoded == null) {
            // Frame is not yet complete in the stream
            return null;
        }

        if (!(decoded instanceof ByteBuf frame)) {
            return decoded;
        }

        try {
            return decodeFrame(frame);
        } finally {
            // Crucial: Release the sliced ByteBuf allocated by LengthFieldBasedFrameDecoder to prevent buffer leaks
            frame.release();
        }
    }

    /**
     * Parses and strictly validates a complete protocol frame.
     *
     * @param frame ByteBuf containing the exact single frame (36 + N bytes)
     * @return deserialized {@link AgvMessage}
     * @throws CorruptedFrameException if magic, opcode, or CRC32 checksum is invalid
     */
    private AgvMessage decodeFrame(ByteBuf frame) {
        int readable = frame.readableBytes();
        if (readable < AgvHeader.HEADER_LENGTH) {
            throw new CorruptedFrameException(String.format(
                    "Frame size (%d bytes) is smaller than fixed header length (%d bytes)",
                    readable, AgvHeader.HEADER_LENGTH
            ));
        }

        // 1. Magic Number (2 Bytes, Short)
        short magic = frame.readShort();
        if (magic != AgvHeader.MAGIC_NUMBER) {
            throw new CorruptedFrameException(String.format(
                    "Invalid Magic Number: 0x%04X, expected: 0x%04X",
                    magic & 0xFFFF, AgvHeader.MAGIC_NUMBER & 0xFFFF
            ));
        }

        // 2. Opcode (1 Byte)
        byte opcodeByte = frame.readByte();
        Opcode opcode;
        try {
            opcode = Opcode.fromByte(opcodeByte);
        } catch (IllegalArgumentException e) {
            throw new CorruptedFrameException(String.format(
                    "Unrecognized or unsupported Opcode: 0x%02X (%d)",
                    opcodeByte & 0xFF, opcodeByte
            ), e);
        }

        // 3. Version (1 Byte)
        byte version = frame.readByte();

        // 4. Sequence ID (8 Bytes, Long)
        long sequenceId = frame.readLong();

        // 5. Lamport Timestamp (8 Bytes, Long)
        long lamportTimestamp = frame.readLong();

        // 6. Sender Node ID (8 Bytes, Long)
        long senderNodeId = frame.readLong();

        // 7. Payload Length (4 Bytes, Int)
        int payloadLength = frame.readInt();
        if (payloadLength < 0) {
            throw new CorruptedFrameException("Negative payload length specified in header: " + payloadLength);
        }

        int remainingBytes = frame.readableBytes();
        if (remainingBytes < 4 + payloadLength) {
            throw new CorruptedFrameException(String.format(
                    "Truncated frame: expected 4 (CRC32) + %d (payload) = %d bytes, but only %d bytes available",
                    payloadLength, 4 + payloadLength, remainingBytes
            ));
        }

        // 8. CRC32 Checksum (4 Bytes, Unsigned Int)
        long expectedChecksum = frame.readUnsignedInt();

        // 9. Payload Data (N Bytes)
        byte[] payload = new byte[payloadLength];
        if (payloadLength > 0) {
            frame.readBytes(payload);
        }

        // Verify CRC32 Integrity
        long computedChecksum = AgvMessage.calculateCrc32(payload);
        if (computedChecksum != expectedChecksum) {
            throw new CorruptedFrameException(String.format(
                    "CRC32 checksum mismatch: expected 0x%08X (%d), computed 0x%08X (%d)",
                    expectedChecksum, expectedChecksum, computedChecksum, computedChecksum
            ));
        }

        AgvHeader header = new AgvHeader(
                magic,
                opcode,
                version,
                sequenceId,
                lamportTimestamp,
                senderNodeId,
                payloadLength,
                expectedChecksum
        );

        AgvMessage message = new AgvMessage(header, payload);

        if (log.isTraceEnabled()) {
            log.trace("Successfully decoded AGV message: {}", message);
        }

        return message;
    }
}
