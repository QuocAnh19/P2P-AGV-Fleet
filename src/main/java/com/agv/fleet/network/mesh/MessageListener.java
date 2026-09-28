package com.agv.fleet.network.mesh;

import com.agv.fleet.common.protocol.AgvMessage;
import io.netty.channel.Channel;

/**
 * Functional callback interface for receiving dispatchable messages from peer nodes.
 */
@FunctionalInterface
public interface MessageListener {

    /**
     * Invoked when an application-level AGV message (Mutex, Task, Event) is received from a peer.
     *
     * @param message received protocol message
     * @param channel peer's channel
     */
    void onMessageReceived(AgvMessage message, Channel channel);
}
