package com.agv.fleet.distributed.mutex;

import com.agv.fleet.common.protocol.AgvHeader;
import com.agv.fleet.common.protocol.AgvMessage;
import com.agv.fleet.common.protocol.Opcode;
import com.agv.fleet.common.protocol.payload.MutexReplyPayload;
import com.agv.fleet.common.protocol.payload.MutexRequestPayload;
import com.agv.fleet.distributed.clock.LamportClock;
import com.agv.fleet.network.mesh.PeerManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LateJoiningPeerMutexTest {

    @Test
    @DisplayName("Bug 2: Late-joining peer C must be asked for MUTEX_REQUEST, A cannot be HELD until C replies")
    void testLateJoiningPeerMustReplyBeforeHeld() {
        long nodeAId = 1L;
        long nodeBId = 2L;
        long nodeCId = 3L;
        String cellId = "INTERSECTION-A";

        AtomicLong seq = new AtomicLong(1);
        LamportClock clockA = new LamportClock(10);
        PeerManager peerManagerA = new PeerManager(nodeAId);
        RicartAgrawalaEngine engineA = new RicartAgrawalaEngine(nodeAId, peerManagerA, clockA, seq::incrementAndGet);
        peerManagerA.setMutexEngine(engineA);

        // 1. Initially only Node B is connected to Node A
        EmbeddedChannel channelB = new EmbeddedChannel();
        peerManagerA.registerSession(nodeBId, channelB);

        // 2. Node A requests intersection -> transitions to WANTED
        CompletableFuture<Void> grantFuture = engineA.acquireIntersection(cellId);
        assertThat(engineA.getState(cellId)).isEqualTo(MutexState.WANTED);
        CellMutex cellMutex = engineA.getOrCreateCellMutex(cellId);
        long originalReqTs = cellMutex.getRequestTimestamp();

        // Drain initial broadcast to B
        AgvMessage reqToB = channelB.readOutbound();
        assertThat(reqToB).isNotNull();
        assertThat(reqToB.getOpcode()).isEqualTo(Opcode.MUTEX_REQUEST);

        // 3. Late peer Node C connects while A is still in WANTED state
        EmbeddedChannel channelC = new EmbeddedChannel();
        peerManagerA.registerSession(nodeCId, channelC);

        // Verify: Node C must be added to pending replies
        assertThat(cellMutex.getPendingReplies())
                .as("Late-joining Node C must be in pendingReplies of WANTED cell")
                .contains(nodeCId);

        // Verify: MUTEX_REQUEST sent to C must have the exact original requestTimestamp
        AgvMessage reqToC = channelC.readOutbound();
        assertThat(reqToC).as("MUTEX_REQUEST must be sent to late-joining Node C").isNotNull();
        assertThat(reqToC.getOpcode()).isEqualTo(Opcode.MUTEX_REQUEST);
        MutexRequestPayload payloadC = MutexRequestPayload.fromBytes(reqToC.getPayload());
        assertThat(payloadC.requestTimestamp()).isEqualTo(originalReqTs);
        assertThat(reqToC.getHeader().lamportTimestamp()).isEqualTo(originalReqTs);

        // 4. Node B replies to Node A
        MutexReplyPayload replyBPayload = new MutexReplyPayload(cellId, nodeBId);
        AgvMessage replyFromB = AgvMessage.create(
                Opcode.MUTEX_REPLY,
                100L,
                clockA.getTime() + 1,
                nodeBId,
                replyBPayload.toBytes()
        );
        engineA.onMutexReplyReceived(replyFromB);

        // A must NOT be HELD yet because C has not replied!
        assertThat(grantFuture.isDone()).as("A cannot be granted HELD before C replies").isFalse();
        assertThat(engineA.getState(cellId)).isEqualTo(MutexState.WANTED);
        assertThat(cellMutex.getPendingReplies()).containsExactly(nodeCId);

        // 5. Node C replies to Node A
        MutexReplyPayload replyCPayload = new MutexReplyPayload(cellId, nodeCId);
        AgvMessage replyFromC = AgvMessage.create(
                Opcode.MUTEX_REPLY,
                200L,
                clockA.getTime() + 2,
                nodeCId,
                replyCPayload.toBytes()
        );
        engineA.onMutexReplyReceived(replyFromC);

        // Now all replies have arrived -> A successfully enters HELD!
        assertThat(grantFuture.isDone()).isTrue();
        assertThat(engineA.getState(cellId)).isEqualTo(MutexState.HELD);
        assertThat(cellMutex.getPendingReplies()).isEmpty();
    }
}
