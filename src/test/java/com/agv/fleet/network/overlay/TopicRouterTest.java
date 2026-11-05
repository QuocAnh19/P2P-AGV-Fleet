package com.agv.fleet.network.overlay;

import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TopicRouterTest {

    @Test
    @DisplayName("Task 6: Exact topic subscription and publishing")
    void testExactTopicPublishSubscribe() {
        TopicRouter router = new TopicRouter();
        List<AgvMessage> received = new ArrayList<>();

        router.subscribe("nav/position", received::add);

        AgvMessage posMsg = AgvMessage.create(Opcode.POSITION_UPDATE, 1, 100, 1L, new byte[]{1, 2, 3});
        AgvMessage hbMsg = AgvMessage.create(Opcode.HEARTBEAT, 2, 101, 1L, new byte[0]);

        router.publish("nav/position", posMsg);
        router.publish("sys/heartbeat", hbMsg); // should NOT be received

        assertThat(received).containsExactly(posMsg);
    }

    @Test
    @DisplayName("Task 6: Wildcard topic subscription (mutex/* and task/*)")
    void testWildcardTopicPublishSubscribe() {
        TopicRouter router = new TopicRouter();
        List<AgvMessage> mutexMessages = new ArrayList<>();
        List<AgvMessage> taskMessages = new ArrayList<>();

        router.subscribe("mutex/*", mutexMessages::add);
        router.subscribe("task/*", taskMessages::add);

        AgvMessage reqMsg = AgvMessage.create(Opcode.MUTEX_REQUEST, 1, 100, 1L, new byte[]{1});
        AgvMessage replyMsg = AgvMessage.create(Opcode.MUTEX_REPLY, 2, 101, 2L, new byte[]{2});
        AgvMessage releaseMsg = AgvMessage.create(Opcode.MUTEX_RELEASE, 3, 102, 1L, new byte[]{3});

        AgvMessage announceMsg = AgvMessage.create(Opcode.TASK_ANNOUNCE, 4, 103, 1L, new byte[]{4});
        AgvMessage bidMsg = AgvMessage.create(Opcode.TASK_BID, 5, 104, 2L, new byte[]{5});

        router.publish("mutex/request", reqMsg);
        router.publish("mutex/reply", replyMsg);
        router.publish("mutex/release", releaseMsg);

        router.publish("task/announce", announceMsg);
        router.publish("task/bid", bidMsg);

        assertThat(mutexMessages).containsExactly(reqMsg, replyMsg, releaseMsg);
        assertThat(taskMessages).containsExactly(announceMsg, bidMsg);
    }

    @Test
    @DisplayName("Task 6: Universal wildcard '*' matches all topics")
    void testUniversalWildcard() {
        TopicRouter router = new TopicRouter();
        List<AgvMessage> allMessages = new ArrayList<>();

        router.subscribe("*", allMessages::add);

        AgvMessage msg1 = AgvMessage.create(Opcode.PEER_DISCOVERY, 1, 100, 1L, new byte[0]);
        AgvMessage msg2 = AgvMessage.create(Opcode.MUTEX_REQUEST, 2, 101, 2L, new byte[0]);
        AgvMessage msg3 = AgvMessage.create(Opcode.POSITION_UPDATE, 3, 102, 3L, new byte[0]);

        router.publish(TopicRouter.topicFor(msg1.getOpcode()), msg1);
        router.publish(TopicRouter.topicFor(msg2.getOpcode()), msg2);
        router.publish(TopicRouter.topicFor(msg3.getOpcode()), msg3);

        assertThat(allMessages).containsExactly(msg1, msg2, msg3);
    }

    @Test
    @DisplayName("Task 6: Unsubscribe removes callback")
    void testUnsubscribe() {
        TopicRouter router = new TopicRouter();
        AtomicInteger count = new AtomicInteger(0);
        java.util.function.Consumer<AgvMessage> consumer = msg -> count.incrementAndGet();

        router.subscribe("sys/heartbeat", consumer);
        AgvMessage hb = AgvMessage.create(Opcode.HEARTBEAT, 1, 100, 1L, new byte[0]);

        router.publish("sys/heartbeat", hb);
        assertThat(count.get()).isEqualTo(1);

        router.unsubscribe("sys/heartbeat", consumer);
        router.publish("sys/heartbeat", hb);
        assertThat(count.get()).isEqualTo(1); // not incremented
    }

    @Test
    @DisplayName("Task 6: Error in one subscriber does not prevent others")
    void testSubscriberFaultIsolation() {
        TopicRouter router = new TopicRouter();
        AtomicBoolean secondReceived = new AtomicBoolean(false);

        router.subscribe("mutex/*", msg -> {
            throw new RuntimeException("Simulated subscriber failure");
        });
        router.subscribe("mutex/*", msg -> secondReceived.set(true));

        AgvMessage msg = AgvMessage.create(Opcode.MUTEX_REQUEST, 1, 100, 1L, new byte[0]);
        router.publish("mutex/request", msg);

        assertThat(secondReceived.get()).isTrue();
    }

    @Test
    @DisplayName("Task 6: Opcode to Topic canonical mapping")
    void testOpcodeToTopicMapping() {
        assertThat(TopicRouter.topicFor(Opcode.PEER_DISCOVERY)).isEqualTo("sys/discovery");
        assertThat(TopicRouter.topicFor(Opcode.HEARTBEAT)).isEqualTo("sys/heartbeat");
        assertThat(TopicRouter.topicFor(Opcode.MUTEX_REQUEST)).isEqualTo("mutex/request");
        assertThat(TopicRouter.topicFor(Opcode.MUTEX_REPLY)).isEqualTo("mutex/reply");
        assertThat(TopicRouter.topicFor(Opcode.MUTEX_RELEASE)).isEqualTo("mutex/release");
        assertThat(TopicRouter.topicFor(Opcode.TASK_ANNOUNCE)).isEqualTo("task/announce");
        assertThat(TopicRouter.topicFor(Opcode.TASK_BID)).isEqualTo("task/bid");
        assertThat(TopicRouter.topicFor(Opcode.TASK_AWARD)).isEqualTo("task/award");
        assertThat(TopicRouter.topicFor(Opcode.TASK_COMPLETE)).isEqualTo("task/complete");
        assertThat(TopicRouter.topicFor(Opcode.POSITION_UPDATE)).isEqualTo("nav/position");
        assertThat(TopicRouter.topicFor(Opcode.PING)).isEqualTo("sys/ping");
        assertThat(TopicRouter.topicFor(Opcode.PONG)).isEqualTo("sys/pong");
    }
}
