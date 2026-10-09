package com.agv.fleet.network.mesh;

import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Inbound Netty handler managing P2P mesh connectivity, mutual handshakes,
 * heartbeats, and message routing.
 */
public class AgvMeshHandler extends SimpleChannelInboundHandler<AgvMessage> {

    private static final Logger log = LoggerFactory.getLogger(AgvMeshHandler.class);

    public static final AttributeKey<Long> PEER_ID_KEY = AttributeKey.valueOf("PEER_ID");

    private final long thisNodeId;
    private final PeerManager peerManager;
    private final LongSupplier sequenceSupplier;
    private final LongSupplier lamportSupplier;

    public AgvMeshHandler(long thisNodeId,
                          PeerManager peerManager,
                          LongSupplier sequenceSupplier,
                          LongSupplier lamportSupplier) {
        this.thisNodeId = thisNodeId;
        this.peerManager = Objects.requireNonNull(peerManager, "peerManager cannot be null");
        this.sequenceSupplier = Objects.requireNonNull(sequenceSupplier, "sequenceSupplier cannot be null");
        this.lamportSupplier = Objects.requireNonNull(lamportSupplier, "lamportSupplier cannot be null");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        if (log.isDebugEnabled()) {
            log.debug("Node {}: TCP channel active with remote {}. Sending PEER_DISCOVERY handshake...",
                    thisNodeId, ctx.channel().remoteAddress());
        }

        // Two-way handshake: immediately identify self to peer
        AgvMessage handshake = AgvMessage.create(
                Opcode.PEER_DISCOVERY,
                sequenceSupplier.getAsLong(),
                lamportSupplier.getAsLong(),
                thisNodeId
        );

        ctx.writeAndFlush(handshake).addListener(future -> {
            if (!future.isSuccess() && log.isDebugEnabled()) {
                log.debug("Node {}: Failed to send PEER_DISCOVERY to {}: {}",
                        thisNodeId, ctx.channel().remoteAddress(), future.cause().getMessage());
            }
        });
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, AgvMessage msg) {
        long senderNodeId = msg.getSenderNodeId();
        ctx.channel().attr(PEER_ID_KEY).set(senderNodeId);

        Opcode opcode = msg.getOpcode();

        switch (opcode) {
            case PEER_DISCOVERY -> {
                if (log.isDebugEnabled()) {
                    log.debug("Node {}: Received PEER_DISCOVERY handshake from Node {}", thisNodeId, senderNodeId);
                }
                peerManager.registerSession(senderNodeId, ctx.channel());
            }
            case HEARTBEAT -> {
                if (log.isTraceEnabled()) {
                    log.trace("Node {}: Received HEARTBEAT from Node {}", thisNodeId, senderNodeId);
                }
                peerManager.onHeartbeatReceived(senderNodeId, ctx.channel());
            }
            case MUTEX_REQUEST -> {
                peerManager.onHeartbeatReceived(senderNodeId, ctx.channel());
                if (peerManager.getMutexEngine() != null) {
                    try {
                        peerManager.getMutexEngine().onMutexRequestReceived(msg);
                    } catch (Exception e) {
                        log.error("Node {}: Error processing MUTEX_REQUEST from Node {}", thisNodeId, senderNodeId, e);
                    }
                }
                peerManager.dispatchMessage(msg, ctx.channel());
            }
            case MUTEX_REPLY -> {
                peerManager.onHeartbeatReceived(senderNodeId, ctx.channel());
                if (peerManager.getMutexEngine() != null) {
                    try {
                        peerManager.getMutexEngine().onMutexReplyReceived(msg);
                    } catch (Exception e) {
                        log.error("Node {}: Error processing MUTEX_REPLY from Node {}", thisNodeId, senderNodeId, e);
                    }
                }
                peerManager.dispatchMessage(msg, ctx.channel());
            }
            case MUTEX_RELEASE -> {
                peerManager.onHeartbeatReceived(senderNodeId, ctx.channel());
                if (peerManager.getMutexEngine() != null) {
                    try {
                        peerManager.getMutexEngine().onMutexReleaseReceived(msg);
                    } catch (Exception e) {
                        log.error("Node {}: Error processing MUTEX_RELEASE from Node {}", thisNodeId, senderNodeId, e);
                    }
                }
                peerManager.dispatchMessage(msg, ctx.channel());
            }
            case TASK_ANNOUNCE -> {
                peerManager.onHeartbeatReceived(senderNodeId, ctx.channel());
                if (peerManager.getCnpEngine() != null) {
                    try {
                        peerManager.getCnpEngine().onTaskAnnounceReceived(msg);
                    } catch (Exception e) {
                        log.error("Node {}: Error processing TASK_ANNOUNCE from Node {}", thisNodeId, senderNodeId, e);
                    }
                }
                peerManager.dispatchMessage(msg, ctx.channel());
            }
            case TASK_BID -> {
                peerManager.onHeartbeatReceived(senderNodeId, ctx.channel());
                if (peerManager.getCnpEngine() != null) {
                    try {
                        peerManager.getCnpEngine().onTaskBidReceived(msg);
                    } catch (Exception e) {
                        log.error("Node {}: Error processing TASK_BID from Node {}", thisNodeId, senderNodeId, e);
                    }
                }
                peerManager.dispatchMessage(msg, ctx.channel());
            }
            case TASK_AWARD -> {
                peerManager.onHeartbeatReceived(senderNodeId, ctx.channel());
                if (peerManager.getCnpEngine() != null) {
                    try {
                        peerManager.getCnpEngine().onTaskAwardReceived(msg);
                    } catch (Exception e) {
                        log.error("Node {}: Error processing TASK_AWARD from Node {}", thisNodeId, senderNodeId, e);
                    }
                }
                peerManager.dispatchMessage(msg, ctx.channel());
            }
            default -> {
                // Any application-level message indicates peer liveness
                peerManager.onHeartbeatReceived(senderNodeId, ctx.channel());
                if (log.isTraceEnabled()) {
                    log.trace("Node {}: Received message {} from Node {}", thisNodeId, opcode, senderNodeId);
                }
                peerManager.dispatchMessage(msg, ctx.channel());
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Long peerId = ctx.channel().attr(PEER_ID_KEY).get();
        if (peerId != null) {
            log.warn("Node {}: TCP channel with Node {} ({}) became inactive",
                    thisNodeId, peerId, ctx.channel().remoteAddress());
            peerManager.onChannelInactive(peerId, ctx.channel());
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Node {}: Unidentified TCP channel ({}) closed", thisNodeId, ctx.channel().remoteAddress());
            }
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Long peerId = ctx.channel().attr(PEER_ID_KEY).get();
        log.warn("Node {}: Exception on channel with Node {}: {}",
                thisNodeId, (peerId != null ? peerId : "unknown"), cause.getMessage());
        ctx.close();
    }
}
