package com.agv.fleet.network.mesh;

import com.agv.fleet.network.codec.AgvPacketDecoder;
import com.agv.fleet.network.codec.AgvPacketEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Netty TCP Client for initiating outbound peer-to-peer mesh connections to other AGVs.
 */
public class AgvTcpClient {

    private static final Logger log = LoggerFactory.getLogger(AgvTcpClient.class);

    private final long thisNodeId;
    private final EventLoopGroup workerGroup;
    private final PeerManager peerManager;
    private final LongSupplier sequenceSupplier;
    private final LongSupplier lamportSupplier;

    public AgvTcpClient(long thisNodeId,
                        EventLoopGroup workerGroup,
                        PeerManager peerManager,
                        LongSupplier sequenceSupplier,
                        LongSupplier lamportSupplier) {
        this.thisNodeId = thisNodeId;
        this.workerGroup = Objects.requireNonNull(workerGroup, "workerGroup cannot be null");
        this.peerManager = Objects.requireNonNull(peerManager, "peerManager cannot be null");
        this.sequenceSupplier = Objects.requireNonNull(sequenceSupplier, "sequenceSupplier cannot be null");
        this.lamportSupplier = Objects.requireNonNull(lamportSupplier, "lamportSupplier cannot be null");
    }

    /**
     * Connects to a remote peer's TCP server.
     *
     * @param host remote host / IP
     * @param port remote TCP port
     * @return ChannelFuture for tracking connection result
     */
    public ChannelFuture connect(String host, int port) {
        if (log.isDebugEnabled()) {
            log.debug("Node {}: Initiating TCP connection to {}:{}", thisNodeId, host, port);
        }

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast("decoder", new AgvPacketDecoder());
                        pipeline.addLast("encoder", new AgvPacketEncoder());
                        pipeline.addLast("meshHandler", new AgvMeshHandler(
                                thisNodeId,
                                peerManager,
                                sequenceSupplier,
                                lamportSupplier
                        ));
                    }
                });

        return bootstrap.connect(host, port);
    }
}
