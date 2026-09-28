package com.agv.fleet.network.mesh;

import io.netty.channel.Channel;

/**
 * Listener interface for peer connectivity lifecycle events in the P2P Mesh.
 */
public interface PeerLifecycleListener {

    /**
     * Triggered when a new peer connection has completed the two-way handshake.
     *
     * @param peerId  nodeId of connected peer
     * @param channel underlying Netty TCP channel
     */
    void onPeerConnected(long peerId, Channel channel);

    /**
     * Triggered when an existing peer disconnects or fails heartbeat health checks.
     *
     * @param peerId nodeId of disconnected peer
     */
    void onPeerDropped(long peerId);
}
