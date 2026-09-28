package com.agv.fleet.network.mesh;

import com.agv.fleet.network.codec.AgvPacketDecoder;
import com.agv.fleet.network.codec.AgvPacketEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Netty TCP Server allowing incoming P2P connections from other AGV nodes.
 */
public class AgvTcpServer {

    private static final Logger log = LoggerFactory.getLogger(AgvTcpServer.class);

    private final long thisNodeId;
    private final String host;
    private final int configuredPort;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final PeerManager peerManager;
    private final LongSupplier sequenceSupplier;
    private final LongSupplier lamportSupplier;

    private volatile Channel serverChannel;
    private volatile int boundPort;
    private volatile boolean running = false;

    public AgvTcpServer(long thisNodeId,
                        String host,
                        int configuredPort,
                        EventLoopGroup bossGroup,
                        EventLoopGroup workerGroup,
                        PeerManager peerManager,
                        LongSupplier sequenceSupplier,
                        LongSupplier lamportSupplier) {
        this.thisNodeId = thisNodeId;
        this.host = (host != null && !host.isBlank()) ? host : "0.0.0.0";
        this.configuredPort = configuredPort;
        this.bossGroup = Objects.requireNonNull(bossGroup, "bossGroup cannot be null");
        this.workerGroup = Objects.requireNonNull(workerGroup, "workerGroup cannot be null");
        this.peerManager = Objects.requireNonNull(peerManager, "peerManager cannot be null");
        this.sequenceSupplier = Objects.requireNonNull(sequenceSupplier, "sequenceSupplier cannot be null");
        this.lamportSupplier = Objects.requireNonNull(lamportSupplier, "lamportSupplier cannot be null");
    }

    public synchronized void start() throws InterruptedException {
        if (running) {
            return;
        }

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
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

        ChannelFuture future = bootstrap.bind(host, configuredPort).sync();
        this.serverChannel = future.channel();
        this.boundPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        this.running = true;

        log.info("Node {}: TCP Server listening on {}:{}", thisNodeId, host, boundPort);
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        log.info("Node {}: Stopping TCP Server on port {}", thisNodeId, boundPort);
        if (serverChannel != null && serverChannel.isOpen()) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
    }

    public int getBoundPort() {
        return boundPort;
    }

    public boolean isRunning() {
        return running;
    }
}
