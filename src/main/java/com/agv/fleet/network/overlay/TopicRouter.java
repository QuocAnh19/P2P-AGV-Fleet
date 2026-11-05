package com.agv.fleet.network.overlay;

import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Overlay Pub/Sub Topic Router for P2P Mesh Network.
 * <p>
 * Routes network messages to subscribers based on topic patterns.
 * Supports exact matching (e.g. "sys/discovery", "nav/position")
 * and hierarchical wildcard prefix matching (e.g. "mutex/*", "task/*").
 */
public class TopicRouter {

    private static final Logger log = LoggerFactory.getLogger(TopicRouter.class);

    private final Map<String, List<Consumer<AgvMessage>>> subscriptions = new ConcurrentHashMap<>();

    /**
     * Subscribes a consumer callback to a topic pattern.
     *
     * @param topicPattern topic name or pattern (e.g. "mutex/*", "task/*", "nav/position")
     * @param consumer     callback to invoke when matching message arrives
     */
    public void subscribe(String topicPattern, Consumer<AgvMessage> consumer) {
        Objects.requireNonNull(topicPattern, "topicPattern cannot be null");
        Objects.requireNonNull(consumer, "consumer cannot be null");
        subscriptions.computeIfAbsent(topicPattern, k -> new CopyOnWriteArrayList<>()).add(consumer);
    }

    /**
     * Unsubscribes a consumer from a topic pattern.
     *
     * @param topicPattern topic pattern previously subscribed to
     * @param consumer     callback to remove
     */
    public void unsubscribe(String topicPattern, Consumer<AgvMessage> consumer) {
        List<Consumer<AgvMessage>> list = subscriptions.get(topicPattern);
        if (list != null) {
            list.remove(consumer);
        }
    }

    /**
     * Publishes a message to a specific topic.
     * All subscribers whose patterns match the topic will be invoked.
     *
     * @param topic   target topic (e.g. "mutex/request", "task/announce")
     * @param message AGV message being routed
     */
    public void publish(String topic, AgvMessage message) {
        if (topic == null || message == null) {
            return;
        }

        for (Map.Entry<String, List<Consumer<AgvMessage>>> entry : subscriptions.entrySet()) {
            String pattern = entry.getKey();
            if (matches(pattern, topic)) {
                for (Consumer<AgvMessage> consumer : entry.getValue()) {
                    try {
                        consumer.accept(message);
                    } catch (Exception e) {
                        log.error("Error executing subscriber callback for topic {} on pattern {}", topic, pattern, e);
                    }
                }
            }
        }
    }

    /**
     * Checks if a topic pattern matches a published topic.
     * Supports:
     * - Exact match: "sys/discovery" matches "sys/discovery"
     * - Suffix wildcard: "mutex/*" matches "mutex/request", "mutex/reply", "mutex/release"
     * - Universal wildcard: "*" matches any topic
     *
     * @param pattern subscription pattern
     * @param topic   concrete published topic
     * @return true if pattern matches topic
     */
    public static boolean matches(String pattern, String topic) {
        if (pattern.equals(topic) || pattern.equals("*")) {
            return true;
        }
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 1); // e.g. "mutex/"
            return topic.startsWith(prefix);
        }
        return false;
    }

    /**
     * Maps an {@link Opcode} to its canonical topic.
     *
     * @param opcode message opcode
     * @return canonical topic string
     */
    public static String topicFor(Opcode opcode) {
        if (opcode == null) return null;
        return switch (opcode) {
            case PEER_DISCOVERY -> "sys/discovery";
            case HEARTBEAT -> "sys/heartbeat";
            case MUTEX_REQUEST -> "mutex/request";
            case MUTEX_REPLY -> "mutex/reply";
            case MUTEX_RELEASE -> "mutex/release";
            case TASK_ANNOUNCE -> "task/announce";
            case TASK_BID -> "task/bid";
            case TASK_AWARD -> "task/award";
            case TASK_COMPLETE -> "task/complete";
            case POSITION_UPDATE -> "nav/position";
            case PING -> "sys/ping";
            case PONG -> "sys/pong";
        };
    }
}
