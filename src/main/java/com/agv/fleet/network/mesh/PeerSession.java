package com.agv.fleet.network.mesh;

import com.agv.fleet.common.protocol.AgvMessage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import java.util.Objects;

/**
 * Encapsulates an active peer session representing a direct TCP connection with another AGV node.
 */
public class PeerSession {

    private final long peerId;
    private final Channel channel;
    private volatile long lastHeartbeatTimestamp;

    public PeerSession(long peerId, Channel channel) {
        this.peerId = peerId;
        this.channel = Objects.requireNonNull(channel, "Channel cannot be null");
        this.lastHeartbeatTimestamp = System.currentTimeMillis();
    }

    public long getPeerId() {
        return peerId;
    }

    public Channel getChannel() {
        return channel;
    }

    public long getLastHeartbeatTimestamp() {
        return lastHeartbeatTimestamp;
    }

    /**
     * Refreshes the last heartbeat timestamp to the current system time.
     */
    public void updateHeartbeat() {
        this.lastHeartbeatTimestamp = System.currentTimeMillis();
    }

    /**
     * Asynchronously sends an {@link AgvMessage} across this peer's TCP channel.
     *
     * @param message AGV protocol message
     * @return ChannelFuture representing the write/flush completion
     */
    public ChannelFuture send(AgvMessage message) {
        if (!isActive()) {
            throw new IllegalStateException("Cannot send message: Peer " + peerId + " channel is inactive");
        }
        if (!channel.isWritable()) {
            return channel.newFailedFuture(new java.io.IOException("Channel socket buffer full"));
        }
        return channel.writeAndFlush(message);
    }

    /**
     * Checks if the underlying Netty channel is open and active.
     *
     * @return {@code true} if active, {@code false} otherwise
     */
    public boolean isActive() {
        return channel.isActive();
    }

    /**
     * Closes the underlying channel cleanly.
     */
    public void close() {
        if (channel.isOpen()) {
            channel.close();
        }
    }

    public String getRemoteAddress() {
        return (channel.remoteAddress() != null) ? channel.remoteAddress().toString() : "unknown";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PeerSession that = (PeerSession) o;
        return peerId == that.peerId && Objects.equals(channel, that.channel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(peerId, channel);
    }

    @Override
    public String toString() {
        return String.format("PeerSession[PeerId=%d, Remote=%s, LastHeartbeat=%d ms ago]",
                peerId, getRemoteAddress(), System.currentTimeMillis() - lastHeartbeatTimestamp);
    }
}
