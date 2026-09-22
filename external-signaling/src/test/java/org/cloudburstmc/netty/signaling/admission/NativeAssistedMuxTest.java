package org.cloudburstmc.netty.signaling.admission;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.*;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Known-peer outbound ICE must share the listening gameplay socket, not allocate another UDP port. */
@Tag("native")
class NativeAssistedMuxTest {
    @Test
    @Timeout(35)
    void proactivePeerUsesListeningMuxForBothFamiliesAndBothChannels() throws Exception {
        for (String address : List.of("127.0.0.1", "::1")) {
            InetAddress bind = InetAddress.getByName(address);
            int port = NativeDiagnosticHostTest.port(bind);
            AtomicInteger unknown = new AtomicInteger();
            var existing = new java.util.concurrent.atomic.AtomicReference<PeerConnection>();
            try (var listener =
                            new IceUdpMuxListener(
                                    bind,
                                    port,
                                    4,
                                    Duration.ofSeconds(5),
                                    Runnable::run,
                                    request -> {
                                        unknown.incrementAndGet();
                                        return CompletableFuture.completedFuture(
                                                IceUdpMuxListener.Acceptance.reuse(
                                                        existing.get(),
                                                        java.time.Instant.now().plusSeconds(10)));
                                    });
                    var host =
                            PeerConnection.createPeer(
                                    PeerConnectionConfiguration.DEFAULT
                                            .withBindAddress(bind)
                                            .withEnableIceUdpMux(true)
                                            .withPortRangeBegin(port)
                                            .withPortRangeEnd(port)
                                            .withDisableAutoNegotiation(true),
                                    Runnable::run);
                    var client =
                            PeerConnection.createPeer(
                                    PeerConnectionConfiguration.DEFAULT
                                            .withBindAddress(bind)
                                            .withDisableAutoNegotiation(true),
                                    Runnable::run)) {
                existing.set(host);
                CountDownLatch clientGathered = new CountDownLatch(1),
                        hostGathered = new CountDownLatch(1);
                client.onGatheringStateChange.register(
                        (p, s) -> {
                            if (s == GatheringState.RTC_GATHERING_COMPLETE) {
                                clientGathered.countDown();
                            }
                        });
                host.onGatheringStateChange.register(
                        (p, s) -> {
                            if (s == GatheringState.RTC_GATHERING_COMPLETE) {
                                hostGathered.countDown();
                            }
                        });
                var returned = new LinkedBlockingQueue<String>();
                var received = new LinkedBlockingQueue<String>();
                host.onDataChannel.register(
                        (p, channel) ->
                                channel.onMessage.register(
                                        new DataChannelCallback.Message() {
                                            public void onText(DataChannel dc, String text) {
                                                fail("binary only");
                                            }

                                            public void onBinary(DataChannel dc, ByteBuffer bytes) {
                                                byte value = bytes.get();
                                                received.add(dc.label() + ":" + value);
                                                dc.sendMessage(
                                                        ByteBuffer.allocateDirect(1)
                                                                .put((byte) (value + 1))
                                                                .flip());
                                            }
                                        }));
                DataChannel[] channels = new DataChannel[2];
                for (int i = 0; i < 2; i++) {
                    channels[i] =
                            client.createDataChannel(
                                    i == 0 ? "ReliableDataChannel" : "UnreliableDataChannel",
                                    DataChannelInitSettings.DEFAULT.withReliability(
                                            DataChannelReliability.DEFAULT
                                                    .withUnordered(i == 1)
                                                    .withUnreliable(i == 1)
                                                    .withMaxRetransmits(0)));
                    channels[i].onMessage.register(
                            new DataChannelCallback.Message() {
                                public void onText(DataChannel dc, String text) {
                                    fail("binary only");
                                }

                                public void onBinary(DataChannel dc, ByteBuffer bytes) {
                                    returned.add(dc.label() + ":" + bytes.get());
                                }
                            });
                }
                client.setLocalDescription("offer", "assistedClientUfrag", "c".repeat(32));
                assertTrue(clientGathered.await(5, TimeUnit.SECONDS));
                host.setRemoteDescription(client.localDescription(), SessionDescriptionType.OFFER);
                host.setLocalDescription("answer", "assistedHostUfrag", "h".repeat(32));
                assertTrue(hostGathered.await(5, TimeUnit.SECONDS));
                // No host candidates reach the client: only the host can start the ICE checks.
                String answer =
                        host.localDescription()
                                .replaceAll("(?m)^a=candidate:[^\\r\\n]*\\r?\\n", "")
                                .replace("a=end-of-candidates\r\n", "");
                client.setRemoteDescription(answer, SessionDescriptionType.ANSWER);
                NativeDiagnosticHostTest.await(() -> channels[0].isOpen() && channels[1].isOpen());
                for (int i = 0; i < 2; i++) {
                    channels[i].sendMessage(
                            ByteBuffer.allocateDirect(1).put((byte) (40 + i)).flip());
                }
                assertEquals(
                        java.util.Set.of("ReliableDataChannel:40", "UnreliableDataChannel:41"),
                        java.util.Set.of(
                                received.poll(5, TimeUnit.SECONDS),
                                received.poll(5, TimeUnit.SECONDS)));
                assertEquals(
                        java.util.Set.of("ReliableDataChannel:41", "UnreliableDataChannel:42"),
                        java.util.Set.of(
                                returned.poll(5, TimeUnit.SECONDS),
                                returned.poll(5, TimeUnit.SECONDS)));
                assertEquals(port, host.selectedCandidatePair().local().getPort());
                assertEquals(port, client.selectedCandidatePair().remote().getPort());
                assertEquals(
                        bind,
                        InetAddress.getByName(
                                client.selectedCandidatePair().remote().getHostString()));
                assertEquals(1, listener.stats()[2]);
                assertEquals(
                        1,
                        unknown.get(),
                        "returning peer-reflexive STUN attaches the precreated peer");
                assertTrue(client.closeAndAwait(Duration.ofSeconds(5)));
                assertTrue(host.closeAndAwait(Duration.ofSeconds(5)));
                NativeDiagnosticHostTest.await(() -> listener.stats()[2] == 0);
            }
        }
    }
}
