package com.agv.fleet.cli;

import com.agv.fleet.core.AgvNode;
import com.agv.fleet.core.agent.AgentState;
import com.agv.fleet.core.agent.Position2D;
import com.agv.fleet.core.agent.Task;
import com.agv.fleet.network.mesh.PeerLifecycleListener;
import com.agv.fleet.network.mesh.PeerSession;
import io.netty.channel.Channel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Standalone Headless Command-Line Interface (CLI) runner for a single AGV Node.
 * <p>
 * Designed for multi-machine LAN live demonstrations and automated testing.
 * Supports:
 * <ul>
 *   <li>{@code --id <nodeId>}: Unique ID of this AGV (default: 1)</li>
 *   <li>{@code --port <tcpPort>}: TCP server port for peer mesh connections (default: 19501)</li>
 *   <li>{@code --iface <interfaceName>}: Network interface to bind UDP multicast (optional)</li>
 *   <li>{@code --multicast-group <ip:port>}: Multicast group address and port (default: 239.255.0.1:20156)</li>
 *   <li>{@code --host <ip>}: Explicit local IP to advertise to peers (optional)</li>
 *   <li>{@code --battery <level>}: Initial battery level percentage (default: 100.0)</li>
 *   <li>{@code --x <x>} and {@code --y <y>}: Initial coordinates (default: 0, 0)</li>
 * </ul>
 */
public class NodeCli {

    public static final String DEFAULT_MULTICAST = "239.255.0.1:20156";
    public static final int DEFAULT_PORT = 19501;

    private final long nodeId;
    private final int port;
    private final String iface;
    private final String multicastGroup;
    private final int multicastPort;
    private final String host;
    private final double battery;
    private final double initialX;
    private final double initialY;

    private AgvNode agvNode;
    private volatile boolean running = false;
    private Thread consoleThread;

    public NodeCli(long nodeId, int port, String iface, String multicastGroup, int multicastPort,
                   String host, double battery, double initialX, double initialY) {
        this.nodeId = nodeId;
        this.port = port;
        this.iface = iface;
        this.multicastGroup = multicastGroup;
        this.multicastPort = multicastPort;
        this.host = host;
        this.battery = battery;
        this.initialX = initialX;
        this.initialY = initialY;
    }

    public static class Config {
        public long nodeId = 1;
        public int port = DEFAULT_PORT;
        public String iface = null;
        public String multicastGroup = "239.255.0.1";
        public int multicastPort = 20156;
        public String host = null;
        public double battery = 100.0;
        public double x = 0.0;
        public double y = 0.0;
        public boolean showHelp = false;

        public static Config parse(String[] args) {
            Config cfg = new Config();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--help".equalsIgnoreCase(arg) || "-h".equalsIgnoreCase(arg)) {
                    cfg.showHelp = true;
                    return cfg;
                } else if ("--id".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    cfg.nodeId = Long.parseLong(args[++i]);
                } else if ("--port".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    cfg.port = Integer.parseInt(args[++i]);
                } else if ("--iface".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    cfg.iface = args[++i];
                } else if ("--multicast-group".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    String val = args[++i];
                    if (val.contains(":")) {
                        String[] parts = val.split(":", 2);
                        cfg.multicastGroup = parts[0];
                        cfg.multicastPort = Integer.parseInt(parts[1]);
                    } else {
                        cfg.multicastGroup = val;
                    }
                } else if ("--host".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    cfg.host = args[++i];
                } else if ("--battery".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    cfg.battery = Double.parseDouble(args[++i]);
                } else if ("--x".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    cfg.x = Double.parseDouble(args[++i]);
                } else if ("--y".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    cfg.y = Double.parseDouble(args[++i]);
                }
            }
            if (cfg.host == null || cfg.host.isBlank()) {
                cfg.host = resolveHostIp(cfg.iface, null);
            }
            return cfg;
        }
    }

    public synchronized void start() throws Exception {
        if (running) return;

        agvNode = AgvNode.builder()
                .nodeId(nodeId)
                .host(host)
                .tcpPort(port)
                .multicastGroup(multicastGroup)
                .multicastPort(multicastPort)
                .networkInterfaceName(iface)
                .initialBattery(battery)
                .initialPosition(initialX, initialY)
                .build();

        // Register Peer Join / Leave listeners for concise console logging
        agvNode.registerPeerLifecycleListener(new PeerLifecycleListener() {
            @Override
            public void onPeerConnected(long peerId, Channel channel) {
                System.out.printf("[PEER JOIN] Node %d connected from %s (Total peers: %d)%n",
                        peerId, channel.remoteAddress(), agvNode.getActivePeerCount());
            }

            @Override
            public void onPeerDropped(long peerId) {
                System.out.printf("[PEER EXIT] Node %d disconnected (Remaining peers: %d)%n",
                        peerId, agvNode.getActivePeerCount());
            }
        });

        // Register Mutex Request / Acquired / Released listeners
        agvNode.addMutexEventListener(new com.agv.fleet.distributed.mutex.RicartAgrawalaEngine.MutexEventListener() {
            @Override
            public void onMutexRequested(String cellId, long requestTimestamp) {
                System.out.printf("[MUTEX REQUEST] Requesting access to intersection %s with timestamp %d%n",
                        cellId, requestTimestamp);
            }

            @Override
            public void onMutexAcquired(String cellId) {
                System.out.printf("[MUTEX ACQUIRED] Granted access to intersection %s - entering critical section!%n",
                        cellId);
            }

            @Override
            public void onMutexReleased(String cellId) {
                System.out.printf("[MUTEX RELEASED] Exited intersection %s%n", cellId);
            }
        });

        // Register Contract Net Protocol listeners
        agvNode.addCnpEventListener(new com.agv.fleet.distributed.auction.CnpEngine.CnpEventListener() {
            @Override
            public void onTaskAnnounced(Task task) {
                System.out.printf("[CNP ANNOUNCE] Announced task %s (Pickup: (%.1f, %.1f) -> Delivery: (%.1f, %.1f))%n",
                        task.getTaskId(), task.getPickupX(), task.getPickupY(), task.getDeliveryX(), task.getDeliveryY());
            }

            @Override
            public void onBidSubmitted(String taskId, double cost, double battery) {
                System.out.printf("[CNP BID] Submitted bid for task %s (Cost: %.2f, Battery: %.1f%%)%n",
                        taskId, cost, battery);
            }

            @Override
            public void onTaskWon(Task task) {
                System.out.printf("[CNP WON] Won task %s! Assigned to this AGV.%n", task.getTaskId());
            }

            @Override
            public void onTaskLost(String taskId, long winnerNodeId) {
                System.out.printf("[CNP LOST] Lost task %s to Node %d%n", taskId, winnerNodeId);
            }

            @Override
            public void onTaskCompleted(String taskId, long contractorNodeId) {
                System.out.printf("[CNP COMPLETE] Task %s completed by contractor Node %d%n",
                        taskId, contractorNodeId);
            }
        });

        agvNode.start();
        running = true;

        printBanner();
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        System.out.printf("%n[SYSTEM] Shutting down AGV Node %d...%n", nodeId);
        if (agvNode != null) {
            agvNode.shutdownGracefully();
            agvNode = null;
        }
        if (consoleThread != null && consoleThread.isAlive()) {
            consoleThread.interrupt();
        }
        System.out.println("[SYSTEM] Node shutdown complete.");
    }

    public void startConsoleReader() {
        consoleThread = new Thread(this::runConsoleLoop, "cli-console-node-" + nodeId);
        consoleThread.setDaemon(true);
        consoleThread.start();
    }

    private void runConsoleLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                handleCommand(line);
            }
        } catch (Exception ignored) {
        }
    }

    public void handleCommand(String line) {
        String[] parts = line.split("\\s+");
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "help" -> printUsage();
            case "status" -> {
                if (agvNode != null) {
                    Position2D pos = agvNode.getPosition();
                    System.out.printf("--- Node %d Status ---%n", nodeId);
                    System.out.printf("  State  : %s%n", agvNode.getAgentState());
                    System.out.printf("  Battery: %.1f%%%n", agvNode.getBatteryLevel());
                    System.out.printf("  Position: (%.2f, %.2f)%n", pos.x(), pos.y());
                    System.out.printf("  Lamport Clock: %d%n", agvNode.getLamportClock().getTime());
                    System.out.printf("  Active Peers : %d%n", agvNode.getActivePeerCount());
                }
            }
            case "peers" -> {
                if (agvNode != null) {
                    Collection<PeerSession> peers = agvNode.getActivePeers();
                    System.out.printf("--- Active Peers (%d) ---%n", peers.size());
                    for (PeerSession session : peers) {
                        System.out.printf("  Node %d -> %s (active: %b)%n",
                                session.getPeerId(), session.getChannel().remoteAddress(), session.isActive());
                    }
                }
            }
            case "announce" -> {
                if (parts.length >= 6) {
                    String taskId = parts[1];
                    double px = Double.parseDouble(parts[2]);
                    double py = Double.parseDouble(parts[3]);
                    double dx = Double.parseDouble(parts[4]);
                    double dy = Double.parseDouble(parts[5]);
                    Task task = new Task(taskId, px, py, dx, dy);
                    agvNode.announceTask(task);
                } else {
                    System.out.println("Usage: announce <taskId> <pickupX> <pickupY> <deliveryX> <deliveryY>");
                }
            }
            case "mutex" -> {
                if (parts.length >= 2) {
                    String cellId;
                    if (parts.length >= 3) {
                        cellId = "(" + parts[1] + ", " + parts[2] + ")";
                    } else {
                        cellId = parts[1];
                    }
                    System.out.printf("Initiating mutex request for cell %s...%n", cellId);
                    CompletableFuture<Void> future = agvNode.acquireIntersection(cellId);
                    future.thenRun(() -> {
                        System.out.printf("Holding intersection %s for 3 seconds...%n", cellId);
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ignored) {}
                        agvNode.releaseIntersection(cellId);
                    });
                } else {
                    System.out.println("Usage: mutex <cellId> OR mutex <x> <y>");
                }
            }
            case "release" -> {
                if (parts.length >= 2) {
                    String cellId = parts[1];
                    agvNode.releaseIntersection(cellId);
                } else {
                    System.out.println("Usage: release <cellId>");
                }
            }
            case "ping" -> {
                if (parts.length >= 2) {
                    long targetNodeId = Long.parseLong(parts[1]);
                    agvNode.ping(targetNodeId).whenComplete((rttNanos, err) -> {
                        if (err != null) {
                            System.out.printf("[PING FAIL] Node %d: %s%n", targetNodeId, err.getMessage());
                        } else {
                            System.out.printf("[PING SUCCESS] Node %d: RTT = %.3f ms%n",
                                    targetNodeId, rttNanos / 1_000_000.0);
                        }
                    });
                } else {
                    System.out.println("Usage: ping <targetNodeId>");
                }
            }
            case "quit", "exit" -> {
                stop();
                System.exit(0);
            }
            default -> System.out.println("Unknown command: '" + cmd + "'. Type 'help' for instructions.");
        }
    }

    private void printBanner() {
        System.out.println("================================================================================");
        System.out.printf("              AGV FLEET HEADLESS NODE (ID: %d) ONLINE%n", nodeId);
        System.out.println("================================================================================");
        System.out.printf("  Host IP         : %s%n", host);
        System.out.printf("  TCP Port        : %d%n", agvNode != null ? agvNode.getTcpPort() : port);
        System.out.printf("  Multicast Group : %s:%d%n", multicastGroup, multicastPort);
        System.out.printf("  Interface       : %s%n", iface != null ? iface : "AUTO-DETECT");
        System.out.printf("  Battery Level   : %.1f%%%n", battery);
        System.out.printf("  Initial Position: (%.1f, %.1f)%n", initialX, initialY);
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Type 'help' for console commands or 'exit' to terminate.");
        System.out.println("================================================================================");
    }

    public static void printUsage() {
        System.out.println("\nAvailable Console Commands:");
        System.out.println("  status                                             Show local node telemetry & state");
        System.out.println("  peers                                              List active connected mesh peers");
        System.out.println("  announce <id> <px> <py> <dx> <dy>                  Announce CNP auction for a transport task");
        System.out.println("  mutex <x> <y>                                      Request mutex for cell (holds 3s, then releases)");
        System.out.println("  release <cellId>                                   Release intersection lock");
        System.out.println("  ping <nodeId>                                      Send diagnostic ping and measure RTT");
        System.out.println("  exit / quit                                        Gracefully shut down node\n");
    }

    public static void printCliHelp() {
        System.out.println("Usage: mvn -q exec:java -Dexec.mainClass=com.agv.fleet.cli.NodeCli -Dexec.args=\"[OPTIONS]\"");
        System.out.println("\nOptions:");
        System.out.println("  --id <nodeId>                 Node ID of this AGV (default: 1)");
        System.out.println("  --port <tcpPort>              TCP mesh server port (default: 19501)");
        System.out.println("  --iface <name>                Network interface name for multicast (e.g. eth0, wlan0)");
        System.out.println("  --multicast-group <ip:port>   UDP multicast discovery group (default: 239.255.0.1:20156)");
        System.out.println("  --host <ip>                   Advertised host IP address (default: auto LAN IP)");
        System.out.println("  --battery <level>             Initial battery percentage (default: 100.0)");
        System.out.println("  --x <x> --y <y>               Initial position coordinates (default: 0, 0)");
        System.out.println("  -h, --help                    Display this help message");
    }

    public static String resolveHostIp(String interfaceName, String explicitHost) {
        if (explicitHost != null && !explicitHost.isBlank()) {
            return explicitHost;
        }
        try {
            if (interfaceName != null && !interfaceName.isBlank()) {
                NetworkInterface iface = NetworkInterface.getByName(interfaceName);
                if (iface == null) {
                    for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                        if (n.getName().equalsIgnoreCase(interfaceName) || n.getDisplayName().equalsIgnoreCase(interfaceName)) {
                            iface = n;
                            break;
                        }
                    }
                }
                if (iface != null) {
                    for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }

            // Auto-detect non-loopback site-local IPv4 address
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface iface : Collections.list(interfaces)) {
                if (iface.isUp() && !iface.isLoopback()) {
                    for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
            // Second pass: any non-loopback IPv4
            interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface iface : Collections.list(interfaces)) {
                if (iface.isUp() && !iface.isLoopback()) {
                    for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    public AgvNode getAgvNode() {
        return agvNode;
    }

    public boolean isRunning() {
        return running;
    }

    public static void main(String[] args) {
        Config cfg = Config.parse(args);
        if (cfg.showHelp) {
            printCliHelp();
            return;
        }

        NodeCli cli = new NodeCli(
                cfg.nodeId,
                cfg.port,
                cfg.iface,
                cfg.multicastGroup,
                cfg.multicastPort,
                cfg.host,
                cfg.battery,
                cfg.x,
                cfg.y
        );

        Runtime.getRuntime().addShutdownHook(new Thread(cli::stop, "cli-shutdown-hook"));

        try {
            cli.start();
            cli.startConsoleReader();

            // Keep main thread alive while running
            while (cli.isRunning()) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException ignored) {
            cli.stop();
        } catch (Exception e) {
            System.err.println("Fatal error starting NodeCli: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
