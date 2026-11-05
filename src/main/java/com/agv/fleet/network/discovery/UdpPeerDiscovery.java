package com.agv.fleet.network.discovery;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.*;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * UDP Multicast Peer Discovery service using Netty {@link NioDatagramChannel}.
 * <p>
 * Periodically broadcasts a {@link PeerDiscoveryInfo} announcement across the P2P mesh network
 * and listens for discovery announcements from neighboring AGV nodes.
 */
public class UdpPeerDiscovery {

    private static final Logger log = LoggerFactory.getLogger(UdpPeerDiscovery.class);

    public static final String DEFAULT_MULTICAST_GROUP = "239.255.0.1";
    public static final int DEFAULT_MULTICAST_PORT = 9876;
    public static final long DEFAULT_BROADCAST_INTERVAL_MS = 1000;

    private final long thisNodeId;
    private final String tcpHost;
    private final int tcpPort;
    private final String multicastGroup;
    private final int multicastPort;
    private final long broadcastIntervalMs;
    private final String preferredInterfaceName;
    private final EventLoopGroup eventLoopGroup;
    private final Consumer<PeerDiscoveryInfo> peerDiscoveredCallback;

    private volatile NioDatagramChannel datagramChannel;
    private volatile ScheduledFuture<?> broadcastTask;
    private volatile NetworkInterface networkInterface;
    private volatile InetSocketAddress groupAddress;
    private volatile boolean running = false;

    public UdpPeerDiscovery(long thisNodeId,
                            String tcpHost,
                            int tcpPort,
                            String multicastGroup,
                            int multicastPort,
                            long broadcastIntervalMs,
                            EventLoopGroup eventLoopGroup,
                            Consumer<PeerDiscoveryInfo> peerDiscoveredCallback) {
        this(thisNodeId, tcpHost, tcpPort, multicastGroup, multicastPort, broadcastIntervalMs, null, eventLoopGroup, peerDiscoveredCallback);
    }

    public UdpPeerDiscovery(long thisNodeId,
                            String tcpHost,
                            int tcpPort,
                            String multicastGroup,
                            int multicastPort,
                            long broadcastIntervalMs,
                            String preferredInterfaceName,
                            EventLoopGroup eventLoopGroup,
                            Consumer<PeerDiscoveryInfo> peerDiscoveredCallback) {
        this.thisNodeId = thisNodeId;
        this.tcpHost = Objects.requireNonNull(tcpHost, "tcpHost cannot be null");
        this.tcpPort = tcpPort;
        this.multicastGroup = (multicastGroup != null) ? multicastGroup : DEFAULT_MULTICAST_GROUP;
        this.multicastPort = (multicastPort > 0) ? multicastPort : DEFAULT_MULTICAST_PORT;
        this.broadcastIntervalMs = (broadcastIntervalMs > 0) ? broadcastIntervalMs : DEFAULT_BROADCAST_INTERVAL_MS;
        this.preferredInterfaceName = preferredInterfaceName;
        this.eventLoopGroup = Objects.requireNonNull(eventLoopGroup, "eventLoopGroup cannot be null");
        this.peerDiscoveredCallback = Objects.requireNonNull(peerDiscoveredCallback, "peerDiscoveredCallback cannot be null");
    }

    public synchronized void start() throws Exception {
        if (running) {
            return;
        }

        this.groupAddress = new InetSocketAddress(multicastGroup, multicastPort);
        this.networkInterface = findMulticastInterface(preferredInterfaceName);

        log.info("Starting UDP Discovery for Node {} on multicast {}:{} using interface: {} ({})",
                thisNodeId, multicastGroup, multicastPort,
                networkInterface.getName(), networkInterface.getDisplayName());

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channelFactory(() -> new NioDatagramChannel(InternetProtocolFamily.IPv4))
                .option(ChannelOption.SO_REUSEADDR, true)
                .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                        handleIncomingPacket(packet);
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        log.warn("Error in UDP discovery channel: {}", cause.getMessage());
                    }
                });

        this.datagramChannel = (NioDatagramChannel) bootstrap.bind(multicastPort).sync().channel();
        this.datagramChannel.joinGroup(groupAddress, networkInterface).sync();

        // Schedule periodic discovery announcement
        this.broadcastTask = eventLoopGroup.scheduleAtFixedRate(
                this::broadcastAnnouncement,
                0,
                broadcastIntervalMs,
                TimeUnit.MILLISECONDS
        );

        this.running = true;
        log.info("UDP Peer Discovery started successfully for Node {}", thisNodeId);
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        log.info("Stopping UDP Peer Discovery for Node {}", thisNodeId);

        if (broadcastTask != null) {
            broadcastTask.cancel(false);
            broadcastTask = null;
        }

        if (datagramChannel != null) {
            try {
                if (datagramChannel.isOpen()) {
                    datagramChannel.leaveGroup(groupAddress, networkInterface).syncUninterruptibly();
                    datagramChannel.close().syncUninterruptibly();
                }
            } catch (Exception e) {
                log.warn("Error while leaving multicast group: {}", e.getMessage());
            } finally {
                datagramChannel = null;
            }
        }
    }

    /**
     * Broadcasts a single HELLO announcement containing this node's metadata.
     */
    public void broadcastAnnouncement() {
        if (!running || datagramChannel == null || !datagramChannel.isActive()) {
            return;
        }

        try {
            PeerDiscoveryInfo info = new PeerDiscoveryInfo(thisNodeId, tcpHost, tcpPort);
            byte[] bytes = info.toBytes();
            ByteBuf buf = Unpooled.wrappedBuffer(bytes);
            DatagramPacket packet = new DatagramPacket(buf, groupAddress);

            datagramChannel.writeAndFlush(packet).addListener(future -> {
                if (!future.isSuccess() && log.isDebugEnabled()) {
                    log.debug("Node {}: Failed to send discovery broadcast: {}", thisNodeId, future.cause().getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("Node {}: Error broadcasting discovery announcement: {}", thisNodeId, e.getMessage());
        }
    }

    private void handleIncomingPacket(DatagramPacket packet) {
        try {
            ByteBuf content = packet.content();
            if (content.readableBytes() < 14) {
                return;
            }
            PeerDiscoveryInfo discoveredInfo = PeerDiscoveryInfo.fromByteBuf(content);

            // Filter out packets sent by self
            if (discoveredInfo.nodeId() == this.thisNodeId) {
                return;
            }

            if (log.isTraceEnabled()) {
                log.trace("Node {}: Discovered peer {} at {}:{}",
                        thisNodeId, discoveredInfo.nodeId(), discoveredInfo.host(), discoveredInfo.tcpPort());
            }

            peerDiscoveredCallback.accept(discoveredInfo);
        } catch (Exception e) {
            log.debug("Failed to parse incoming discovery datagram: {}", e.getMessage());
        }
    }

    /**
     * Resolves an active network interface with IPv4 support capable of multicasting.
     */
    public static NetworkInterface findMulticastInterface() {
        return findMulticastInterface(null);
    }

    /**
     * Resolves an active network interface with IPv4 support capable of multicasting,
     * prioritizing the preferred interface name if provided.
     *
     * @param preferredInterfaceName preferred interface name or display name (optional)
     * @return suitable NetworkInterface
     */
    public static NetworkInterface findMulticastInterface(String preferredInterfaceName) {
        try {
            if (preferredInterfaceName != null && !preferredInterfaceName.isBlank()) {
                NetworkInterface ifaceByName = NetworkInterface.getByName(preferredInterfaceName);
                if (ifaceByName != null && ifaceByName.isUp()) {
                    return ifaceByName;
                }
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                for (NetworkInterface iface : Collections.list(interfaces)) {
                    if (iface.isUp() && (iface.getName().equalsIgnoreCase(preferredInterfaceName) ||
                            iface.getDisplayName().equalsIgnoreCase(preferredInterfaceName))) {
                        return iface;
                    }
                }
                log.warn("Preferred interface '{}' not found or not up. Falling back to default selection.", preferredInterfaceName);
            }

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            NetworkInterface loopback = null;
            NetworkInterface candidateVirtual = null;
            for (NetworkInterface iface : Collections.list(interfaces)) {
                if (iface.isUp() && iface.supportsMulticast()) {
                    boolean hasIpv4 = false;
                    for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                        if (addr instanceof Inet4Address) {
                            hasIpv4 = true;
                            break;
                        }
                    }
                    if (hasIpv4) {
                        if (!iface.isLoopback()) {
                            if (!isVirtualOrVpn(iface)) {
                                return iface;
                            } else if (candidateVirtual == null) {
                                candidateVirtual = iface;
                            }
                        } else if (loopback == null) {
                            loopback = iface;
                        }
                    }
                }
            }
            if (candidateVirtual != null && loopback == null) {
                return candidateVirtual;
            }
            return (loopback != null) ? loopback : NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to resolve suitable multicast network interface", e);
        }
    }

    private static boolean isVirtualOrVpn(NetworkInterface iface) {
        try {
            if (iface.isVirtual() || iface.isPointToPoint()) {
                return true;
            }
        } catch (Exception ignored) {
        }
        String name = (iface.getName() + " " + iface.getDisplayName()).toLowerCase();
        return name.contains("vpn") || name.contains("virtual") || name.contains("radmin")
                || name.contains("tap") || name.contains("vbox") || name.contains("vmnet")
                || name.contains("wsl") || name.contains("hyper-v") || name.contains("pseudo")
                || name.contains("wireguard") || name.contains("zerotier") || name.contains("tailscale")
                || name.contains("tunnel") || name.contains("docker");
    }

    public boolean isRunning() {
        return running;
    }
}
