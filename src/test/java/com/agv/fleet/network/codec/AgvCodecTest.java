package com.agv.fleet.network.codec;

import com.agv.fleet.common.protocol.AgvHeader;
import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Binary Protocol Codec Unit Tests (Netty EmbeddedChannel)")
class AgvCodecTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        // Initialize an EmbeddedChannel equipped with both decoder and encoder
        channel = new EmbeddedChannel(
                new AgvPacketDecoder(),
                new AgvPacketEncoder()
        );
    }

    @Test
    @DisplayName("1. Encode and Decode Single Message - 100% properties match")
    void testEncodeAndDecodeSingleMessage() {
        String payloadJson = "{\"taskId\":\"TASK-992\",\"priority\":10,\"source\":\"Dock-A\",\"destination\":\"Station-4\"}";
        byte[] payloadBytes = payloadJson.getBytes(StandardCharsets.UTF_8);

        AgvMessage originalMessage = AgvMessage.create(
                Opcode.TASK_ANNOUNCE,
                1001L,
                42L,
                8888L,
                payloadBytes
        );

        // 1. Send outbound through AgvPacketEncoder
        boolean outboundWritten = channel.writeOutbound(originalMessage);
        assertThat(outboundWritten).isTrue();

        ByteBuf encodedBuf = channel.readOutbound();
        assertThat(encodedBuf).isNotNull();
        assertThat(encodedBuf.readableBytes()).isEqualTo(AgvHeader.HEADER_LENGTH + payloadBytes.length);

        // 2. Feed inbound through AgvPacketDecoder
        boolean inboundWritten = channel.writeInbound(encodedBuf);
        assertThat(inboundWritten).isTrue();

        AgvMessage decodedMessage = channel.readInbound();
        assertThat(decodedMessage).isNotNull();

        // 3. Verify all 8 header fields + payload match identically
        AgvHeader h = decodedMessage.getHeader();
        assertThat(h.magic()).isEqualTo(AgvHeader.MAGIC_NUMBER);
        assertThat(h.opcode()).isEqualTo(Opcode.TASK_ANNOUNCE);
        assertThat(h.version()).isEqualTo(AgvHeader.CURRENT_VERSION);
        assertThat(h.sequenceId()).isEqualTo(1001L);
        assertThat(h.lamportTimestamp()).isEqualTo(42L);
        assertThat(h.senderNodeId()).isEqualTo(8888L);
        assertThat(h.payloadLength()).isEqualTo(payloadBytes.length);
        assertThat(h.crc32Checksum()).isEqualTo(originalMessage.getHeader().crc32Checksum());

        assertThat(decodedMessage.getPayload()).isEqualTo(payloadBytes);
        assertThat(decodedMessage.getPayloadAsString()).isEqualTo(payloadJson);
        assertThat(decodedMessage.isChecksumValid()).isTrue();
        assertThat(decodedMessage).isEqualTo(originalMessage);

        assertThat((Object) channel.readInbound()).isNull();
    }

    @Test
    @DisplayName("2. Sticky Packets Handling (Dính gói) - 3 consecutive messages in 1 ByteBuf")
    void testStickyPacketsHandling() {
        AgvMessage msg1 = AgvMessage.create(
                Opcode.MUTEX_REQUEST,
                101L,
                10L,
                1001L,
                "MUTEX-SECTION-INTERSECTION-ALPHA".getBytes(StandardCharsets.UTF_8)
        );

        AgvMessage msg2 = AgvMessage.create(
                Opcode.MUTEX_REPLY,
                102L,
                11L,
                1002L,
                "OK-GRANTED".getBytes(StandardCharsets.UTF_8)
        );

        AgvMessage msg3 = AgvMessage.create(
                Opcode.HEARTBEAT,
                103L,
                12L,
                1003L
                // empty payload
        );

        // Encode each message individually
        channel.writeOutbound(msg1);
        ByteBuf buf1 = channel.readOutbound();

        channel.writeOutbound(msg2);
        ByteBuf buf2 = channel.readOutbound();

        channel.writeOutbound(msg3);
        ByteBuf buf3 = channel.readOutbound();

        // Simulate sticky packets: Merge all 3 frames into a single ByteBuf stream
        ByteBuf stickyBuffer = Unpooled.buffer();
        stickyBuffer.writeBytes(buf1);
        stickyBuffer.writeBytes(buf2);
        stickyBuffer.writeBytes(buf3);

        buf1.release();
        buf2.release();
        buf3.release();

        // Feed the single combined stream into the channel
        boolean written = channel.writeInbound(stickyBuffer);
        assertThat(written).isTrue();

        // Read out decoded messages
        AgvMessage decoded1 = channel.readInbound();
        AgvMessage decoded2 = channel.readInbound();
        AgvMessage decoded3 = channel.readInbound();
        AgvMessage extra = channel.readInbound();

        assertThat(decoded1).isNotNull().isEqualTo(msg1);
        assertThat(decoded2).isNotNull().isEqualTo(msg2);
        assertThat(decoded3).isNotNull().isEqualTo(msg3);
        assertThat(extra).isNull();
    }

    @Test
    @DisplayName("3. Fragmented Packets Handling (Vỡ gói) - Split 1 message into 3 fragmented byte chunks")
    void testFragmentedPacketsHandling() {
        byte[] payloadData = "Autonomous Guided Vehicle - Pure P2P Fleet Coordination Bus".getBytes(StandardCharsets.UTF_8);
        AgvMessage originalMessage = AgvMessage.create(
                Opcode.TASK_BID,
                555L,
                88L,
                2001L,
                payloadData
        );

        // Encode to full frame
        channel.writeOutbound(originalMessage);
        ByteBuf fullFrame = channel.readOutbound();
        int totalBytes = fullFrame.readableBytes();
        assertThat(totalBytes).isGreaterThan(30);

        // Split frame into 3 unequal fragments: 10 bytes, 20 bytes, and the rest
        int chunk1Size = 10;
        int chunk2Size = 20;
        int chunk3Size = totalBytes - chunk1Size - chunk2Size;

        ByteBuf chunk1 = fullFrame.readRetainedSlice(chunk1Size);
        ByteBuf chunk2 = fullFrame.readRetainedSlice(chunk2Size);
        ByteBuf chunk3 = fullFrame.readRetainedSlice(chunk3Size);
        fullFrame.release();

        // 1. Send chunk 1 (incomplete header)
        boolean r1 = channel.writeInbound(chunk1);
        assertThat(r1).isFalse();
        assertThat((Object) channel.readInbound()).isNull();

        // 2. Send chunk 2 (still incomplete header/frame)
        boolean r2 = channel.writeInbound(chunk2);
        assertThat(r2).isFalse();
        assertThat((Object) channel.readInbound()).isNull();

        // 3. Send chunk 3 (completes the frame)
        boolean r3 = channel.writeInbound(chunk3);
        assertThat(r3).isTrue();

        AgvMessage decoded = channel.readInbound();
        assertThat(decoded).isNotNull().isEqualTo(originalMessage);
        assertThat(decoded.getPayloadAsString()).isEqualTo("Autonomous Guided Vehicle - Pure P2P Fleet Coordination Bus");
        assertThat((Object) channel.readInbound()).isNull();
    }

    @Test
    @DisplayName("4. Corrupted CRC32 Checksum - Tampering payload causes CorruptedFrameException")
    void testCorruptedCrc32InPayload() {
        byte[] payload = "Critical-Safety-Zone-Coordination".getBytes(StandardCharsets.UTF_8);
        AgvMessage msg = AgvMessage.create(
                Opcode.MUTEX_REQUEST,
                1L,
                1L,
                100L,
                payload
        );

        channel.writeOutbound(msg);
        ByteBuf frame = channel.readOutbound();

        // Corrupt 1 byte in the payload area (beyond header length of 36 bytes)
        int payloadByteIndex = AgvHeader.HEADER_LENGTH + 3;
        byte originalByte = frame.getByte(payloadByteIndex);
        frame.setByte(payloadByteIndex, originalByte ^ 0xFF);

        // Feed corrupted frame into channel - Expect CorruptedFrameException
        assertThatThrownBy(() -> channel.writeInbound(frame))
                .isInstanceOf(CorruptedFrameException.class)
                .hasMessageContaining("CRC32 checksum mismatch");
    }

    @Test
    @DisplayName("4b. Corrupted Checksum in Header - Tampering CRC32 field in header")
    void testCorruptedCrc32InHeader() {
        byte[] payload = "Heartbeat-With-State".getBytes(StandardCharsets.UTF_8);
        AgvMessage msg = AgvMessage.create(
                Opcode.HEARTBEAT,
                2L,
                2L,
                100L,
                payload
        );

        channel.writeOutbound(msg);
        ByteBuf frame = channel.readOutbound();

        // Tamper with the CRC32 field in header (offset 32..35)
        int crcOffset = 32;
        int originalCrc = frame.getInt(crcOffset);
        frame.setInt(crcOffset, originalCrc ^ 0xA5A5A5A5);

        assertThatThrownBy(() -> channel.writeInbound(frame))
                .isInstanceOf(CorruptedFrameException.class)
                .hasMessageContaining("CRC32 checksum mismatch");
    }

    @Test
    @DisplayName("5. Invalid Magic Number - Frame rejected")
    void testInvalidMagicNumber() {
        AgvMessage msg = AgvMessage.create(Opcode.PEER_DISCOVERY, 1L, 1L, 100L);
        channel.writeOutbound(msg);
        ByteBuf frame = channel.readOutbound();

        // Corrupt Magic Number at offset 0 (2 bytes)
        frame.setShort(0, (short) 0xDEAD);

        assertThatThrownBy(() -> channel.writeInbound(frame))
                .isInstanceOf(CorruptedFrameException.class)
                .hasMessageContaining("Invalid Magic Number: 0xDEAD");
    }

    @Test
    @DisplayName("6. Unrecognized Opcode - Frame rejected")
    void testInvalidOpcode() {
        AgvMessage msg = AgvMessage.create(Opcode.PEER_DISCOVERY, 1L, 1L, 100L);
        channel.writeOutbound(msg);
        ByteBuf frame = channel.readOutbound();

        // Corrupt Opcode at offset 2 (1 byte) to 0x7F (undefined)
        frame.setByte(2, (byte) 0x7F);

        assertThatThrownBy(() -> channel.writeInbound(frame))
                .isInstanceOf(CorruptedFrameException.class)
                .hasMessageContaining("Unrecognized or unsupported Opcode: 0x7F");
    }

    @Test
    @DisplayName("7. Zero-Length Payload (Heartbeat) - Exactly 36 bytes frame")
    void testEmptyPayloadMessage() {
        AgvMessage heartbeat = AgvMessage.create(Opcode.HEARTBEAT, 99L, 105L, 500L);

        channel.writeOutbound(heartbeat);
        ByteBuf frame = channel.readOutbound();

        assertThat(frame.readableBytes()).isEqualTo(AgvHeader.HEADER_LENGTH); // Exactly 36 bytes

        channel.writeInbound(frame);
        AgvMessage decoded = channel.readInbound();

        assertThat(decoded).isNotNull();
        assertThat(decoded.getOpcode()).isEqualTo(Opcode.HEARTBEAT);
        assertThat(decoded.getPayloadLength()).isZero();
        assertThat(decoded.getPayload()).isEmpty();
        assertThat(decoded.getHeader().crc32Checksum()).isZero();
        assertThat(decoded.isChecksumValid()).isTrue();
    }
}
