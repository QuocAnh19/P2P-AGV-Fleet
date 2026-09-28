package com.agv.fleet.network.discovery;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Metadata record broadcasted via UDP Multicast for peer discovery in the P2P AGV Fleet network.
 *
 * @param nodeId  unique 64-bit identifier of the AGV node
 * @param host    hostname or IP address of the AGV's TCP server
 * @param tcpPort TCP listening port of the AGV's TCP server
 */
public record PeerDiscoveryInfo(
        long nodeId,
        String host,
        int tcpPort
) {

    public PeerDiscoveryInfo {
        Objects.requireNonNull(host, "Host cannot be null");
        if (tcpPort <= 0 || tcpPort > 65535) {
            throw new IllegalArgumentException("Invalid TCP port: " + tcpPort);
        }
    }

    /**
     * Serializes this discovery info into a compact binary byte array.
     * <pre>
     * [8 bytes: nodeId] + [4 bytes: tcpPort] + [2 bytes: host length] + [N bytes: host UTF-8]
     * </pre>
     *
     * @return serialized binary representation
     */
    public byte[] toBytes() {
        byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer(8 + 4 + 2 + hostBytes.length);
        buf.writeLong(nodeId);
        buf.writeInt(tcpPort);
        buf.writeShort(hostBytes.length);
        buf.writeBytes(hostBytes);
        byte[] result = new byte[buf.readableBytes()];
        buf.readBytes(result);
        buf.release();
        return result;
    }

    /**
     * Deserializes a {@link PeerDiscoveryInfo} from a Netty {@link ByteBuf}.
     *
     * @param buf Netty ByteBuf containing discovery payload
     * @return deserialized info
     */
    public static PeerDiscoveryInfo fromByteBuf(ByteBuf buf) {
        if (buf.readableBytes() < 14) { // 8 + 4 + 2 = 14 bytes minimum
            throw new IllegalArgumentException("ByteBuf too short for PeerDiscoveryInfo: " + buf.readableBytes());
        }
        long nodeId = buf.readLong();
        int tcpPort = buf.readInt();
        int hostLen = buf.readUnsignedShort();
        if (buf.readableBytes() < hostLen) {
            throw new IllegalArgumentException("Truncated host string in PeerDiscoveryInfo");
        }
        byte[] hostBytes = new byte[hostLen];
        buf.readBytes(hostBytes);
        String host = new String(hostBytes, StandardCharsets.UTF_8);
        return new PeerDiscoveryInfo(nodeId, host, tcpPort);
    }

    /**
     * Deserializes a {@link PeerDiscoveryInfo} from a byte array.
     *
     * @param bytes binary data
     * @return deserialized info
     */
    public static PeerDiscoveryInfo fromBytes(byte[] bytes) {
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            return fromByteBuf(buf);
        } finally {
            buf.release();
        }
    }
}
