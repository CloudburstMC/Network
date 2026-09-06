package org.cloudburstmc.netty.channel.nethernet.backend;

import dev.kastle.webrtc.CreateSessionDescriptionObserver;
import dev.kastle.webrtc.RTCIceGatheringState;
import dev.kastle.webrtc.RTCPeerConnectionState;
import dev.kastle.webrtc.RTCSdpType;
import dev.kastle.webrtc.RTCSessionDescription;
import dev.kastle.webrtc.SetSessionDescriptionObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibWebRtcSessionNegotiationTest {
    private static final String OFFER = "v=0\r\na=identity:ignored\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";
    private static final RTCSessionDescription ANSWER = new RTCSessionDescription(RTCSdpType.ANSWER,
            "v=0\r\na=candidate:1 1 udp 1 127.0.0.1 19132 typ host\r\n");

    @ParameterizedTest
    @EnumSource(Stage.class)
    void asynchronousSdpFailureClosesAndUntracksBeforeNotifyingOnce(Stage stage) {
        try (Fixture fixture = new Fixture(false)) {
            fixture.reach(stage);

            fixture.peer.fail(stage);

            fixture.assertFailed(stage.operation + " failed");
            int calls = fixture.peer.operations.size();
            fixture.peer.fail(stage);
            fixture.peer.deliverLateSuccesses();
            fixture.session.close();
            assertEquals(calls, fixture.peer.operations.size());
            assertEquals(1, fixture.peer.closes);
            assertEquals(1, fixture.listener.failures.size());
            assertTrue(fixture.listener.answers.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void synchronousSdpFailureUsesTheSameCleanupPath(Stage stage) {
        try (Fixture fixture = new Fixture(false)) {
            fixture.peer.throwAt = stage;

            fixture.reach(stage);

            fixture.assertFailed(stage.operation + " failed");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fullIceAnswerWaitsForDescriptionAndGatheringWithoutDuplicateAnswers(boolean gatheringFirst) {
        try (Fixture fixture = new Fixture(true)) {
            fixture.reach(Stage.SET_LOCAL);
            if (gatheringFirst) {
                fixture.session.observer.onIceGatheringChange(RTCIceGatheringState.COMPLETE);
                assertTrue(fixture.listener.answers.isEmpty());
                fixture.peer.local.onSuccess();
            } else {
                fixture.peer.local.onSuccess();
                assertTrue(fixture.listener.answers.isEmpty());
                fixture.session.observer.onIceGatheringChange(RTCIceGatheringState.COMPLETE);
            }

            assertEquals(List.of(ANSWER.sdp + "a=end-of-candidates\r\n"), fixture.listener.answers);
            assertTrue(fixture.listener.failures.isEmpty());
            assertEquals(0, fixture.peer.closes);
            fixture.peer.local.onSuccess();
            fixture.session.observer.onIceGatheringChange(RTCIceGatheringState.COMPLETE);
            assertEquals(1, fixture.listener.answers.size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableFullIceDescriptionFailsPromptly(boolean nativeCallThrows) {
        try (Fixture fixture = new Fixture(true)) {
            fixture.reach(Stage.SET_LOCAL);
            fixture.peer.localDescription = null;
            fixture.peer.throwOnRead = nativeCallThrows;
            fixture.peer.local.onSuccess();

            fixture.session.observer.onIceGatheringChange(RTCIceGatheringState.COMPLETE);

            fixture.assertFailed("GetLocalDescription failed");
        }
    }

    @Test
    void trickleAnswerDoesNotWaitForFullIceGathering() {
        try (Fixture fixture = new Fixture(false)) {
            fixture.reach(Stage.SET_LOCAL);

            fixture.peer.local.onSuccess();

            assertEquals(List.of(ANSWER.sdp), fixture.listener.answers);
            assertTrue(fixture.listener.failures.isEmpty());
            assertEquals(0, fixture.peer.closes);
        }
    }

    @Test
    void localCloseSuppressesLateSdpCallbacks() {
        try (Fixture fixture = new Fixture(false)) {
            fixture.reach(Stage.SET_LOCAL);
            fixture.session.close();
            int operations = fixture.peer.operations.size();

            fixture.peer.deliverLateSuccesses();
            fixture.peer.local.onFailure("late native failure");
            fixture.session.observer.onConnectionChange(RTCPeerConnectionState.FAILED);

            assertEquals(operations, fixture.peer.operations.size());
            assertEquals(1, fixture.peer.closes);
            assertEquals(1, fixture.untracked);
            assertTrue(fixture.listener.failures.isEmpty());
            assertTrue(fixture.listener.answers.isEmpty());
            assertEquals(0, fixture.listener.transportCloses);
        }
    }

    @Test
    void peerCreatedAfterLocalCloseIsClosedWithoutNegotiationOrHoldingTheSessionLock() {
        try (Fixture fixture = new Fixture(false)) {
            fixture.session.close();

            fixture.session.start(fixture.peer, OFFER);

            assertTrue(fixture.peer.operations.isEmpty());
            assertEquals(1, fixture.peer.closes);
            assertTrue(fixture.peer.closedOutsideMonitor);
            assertEquals(1, fixture.untracked);
            assertTrue(fixture.listener.failures.isEmpty());
        }
    }

    @Test
    void listenersWithoutTheNewCallbackStillReceiveTransportClosure() {
        LegacyListener listener = new LegacyListener();
        LibWebRtcServerBackend.Session session = new LibWebRtcServerBackend.Session(listener, ignored -> {}, false);
        FakePeer peer = new FakePeer();
        peer.session = session;
        try {
            session.start(peer, OFFER);

            peer.remote.onFailure("invalid offer");

            assertEquals(1, listener.transportCloses);
            assertEquals(1, peer.closes);
        } finally {
            session.close();
        }
    }

    private enum Stage {
        SET_REMOTE("SetRemoteDescription"), CREATE_ANSWER("CreateAnswer"), SET_LOCAL("SetLocalDescription");

        private final String operation;

        Stage(String operation) {
            this.operation = operation;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final List<String> terminalEvents = new ArrayList<>();
        private final RecordingListener listener = new RecordingListener(terminalEvents);
        private final FakePeer peer = new FakePeer();
        private final LibWebRtcServerBackend.Session session;
        private int untracked;

        private Fixture(boolean fullIceAnswer) {
            session = new LibWebRtcServerBackend.Session(listener, ignored -> {
                terminalEvents.add("untracked");
                untracked++;
            }, fullIceAnswer);
            peer.session = session;
            peer.terminalEvents = terminalEvents;
        }

        private void reach(Stage stage) {
            session.start(peer, OFFER);
            assertEquals("v=0\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n", peer.remoteDescription.sdp);
            if (stage != Stage.SET_REMOTE) {
                peer.remote.onSuccess();
            }
            if (stage == Stage.SET_LOCAL) {
                peer.answer.onSuccess(ANSWER);
            }
        }

        private void assertFailed(String reason) {
            assertEquals(1, peer.closes);
            assertTrue(peer.closedOutsideMonitor);
            assertEquals(1, untracked);
            assertEquals(List.of(reason), listener.failures);
            assertEquals(List.of("closed", "untracked", "failed"), terminalEvents);
            assertTrue(listener.answers.isEmpty());
            assertEquals(0, listener.transportCloses);
        }

        @Override
        public void close() {
            session.close();
        }
    }

    private static final class FakePeer implements LibWebRtcServerBackend.PeerOperations {
        private final List<Stage> operations = new ArrayList<>();
        private SetSessionDescriptionObserver remote;
        private CreateSessionDescriptionObserver answer;
        private SetSessionDescriptionObserver local;
        private RTCSessionDescription remoteDescription;
        private RTCSessionDescription localDescription = ANSWER;
        private LibWebRtcServerBackend.Session session;
        private List<String> terminalEvents;
        private Stage throwAt;
        private boolean throwOnRead;
        private int closes;
        private boolean closedOutsideMonitor;

        @Override
        public void setRemoteDescription(RTCSessionDescription description, SetSessionDescriptionObserver observer) {
            remoteDescription = description;
            remote = observer;
            operation(Stage.SET_REMOTE);
        }

        @Override
        public void createAnswer(CreateSessionDescriptionObserver observer) {
            answer = observer;
            operation(Stage.CREATE_ANSWER);
        }

        @Override
        public void setLocalDescription(RTCSessionDescription description, SetSessionDescriptionObserver observer) {
            local = observer;
            operation(Stage.SET_LOCAL);
        }

        @Override
        public RTCSessionDescription localDescription() {
            if (throwOnRead) {
                throw new IllegalStateException("native local-description lookup failed");
            }
            return localDescription;
        }

        @Override
        public void close() {
            closes++;
            closedOutsideMonitor = !Thread.holdsLock(session);
            if (terminalEvents != null) {
                terminalEvents.add("closed");
            }
            session.observer.onConnectionChange(RTCPeerConnectionState.CLOSED);
        }

        private void operation(Stage stage) {
            operations.add(stage);
            if (throwAt == stage) {
                throw new IllegalStateException("native operation failed");
            }
        }

        private void fail(Stage stage) {
            switch (stage) {
                case SET_REMOTE -> remote.onFailure("invalid offer");
                case CREATE_ANSWER -> answer.onFailure("answer unavailable");
                case SET_LOCAL -> local.onFailure("invalid answer");
            }
        }

        private void deliverLateSuccesses() {
            remote.onSuccess();
            if (answer != null) {
                answer.onSuccess(ANSWER);
            }
            if (local != null) {
                local.onSuccess();
            }
        }
    }

    private static class LegacyListener implements WebRtcSessionListener {
        int transportCloses;

        @Override public void onAnswerReady(String answerSdp) { }
        @Override public void onLocalCandidate(String candidateSdp) { }
        @Override public void onTransportOpen() { }
        @Override public void onMessage(ByteBuffer data) { }
        @Override public void onRemoteAddress(InetSocketAddress address, String candidateType) { }
        @Override public void onTransportClosed() { transportCloses++; }
    }

    private static final class RecordingListener extends LegacyListener {
        private final List<String> events;
        private final List<String> answers = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();

        private RecordingListener(List<String> events) {
            this.events = events;
        }

        @Override
        public void onAnswerReady(String answerSdp) {
            answers.add(answerSdp);
        }

        @Override
        public void onNegotiationFailed(String reason) {
            events.add("failed");
            failures.add(reason);
        }
    }
}
