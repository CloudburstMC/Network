package org.cloudburstmc.netty.signaling.admission;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import org.cloudburstmc.netty.channel.nethernet.AddressSource;
import org.cloudburstmc.netty.signaling.control.AssistedJoin;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tel.schich.libdatachannel.DataChannelInitSettings;
import tel.schich.libdatachannel.DataChannelReliability;
import tel.schich.libdatachannel.GatheringState;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.PeerConnectionConfiguration;
import tel.schich.libdatachannel.SessionDescriptionType;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An assisted NXS join whose pair runs through the client's TURN relay. It reserves the offer's first
 * candidate, which the client only claimed, so the child knows no address and TURN falls back to the relay.
 */
@Tag("native")
class NativeAssistedTurnAddressTest {
    @TempDir Path directory;

    @Test
    @Timeout(40)
    void anAssistedJoinThroughTurnNeverReportsAnOfferedCandidate() throws Exception {
        var identityHelper = new NativeDiagnosticHostTest();
        identityHelper.directory = directory;
        var identity = identityHelper.identity();
        InetAddress bind = InetAddress.getByName("127.0.0.1");
        int port = NativeDiagnosticHostTest.port(bind);
        var loop = new DefaultEventLoopGroup(1);
        var endpoint = new NativeAdmissionServerChannel(
                identity, (r, now) -> null, new AdmissionGate.Limits(4, 8, 2, 15000));
        var active = new CompletableFuture<AdmittedNetherNetChildChannel>();
        try {
            new ServerBootstrap().group(loop).channelFactory(() -> endpoint)
                    .childHandler(new ChannelInitializer<AdmittedNetherNetChildChannel>() {
                        @Override
                        protected void initChannel(AdmittedNetherNetChildChannel ch) {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelActive(ChannelHandlerContext ctx) {
                                    active.complete(ch);
                                    ctx.fireChannelActive();
                                }
                            });
                        }
                    })
                    .bind(bind, port).sync();
            endpoint.admitOnly(StatelessAdmissionCodec.icePasswordContext("assisted"), Map.of());
            try (var client = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT
                    .withBindAddress(bind).withDisableAutoNegotiation(true), Runnable::run)) {
                CountDownLatch gathered = new CountDownLatch(1);
                client.onGatheringStateChange.register((p, s) -> {
                    if (s == GatheringState.RTC_GATHERING_COMPLETE) {
                        gathered.countDown();
                    }
                });
                for (int i = 0; i < 2; i++) {
                    client.createDataChannel(i == 0 ? "ReliableDataChannel" : "UnreliableDataChannel",
                            DataChannelInitSettings.DEFAULT.withReliability(DataChannelReliability.DEFAULT
                                    .withUnordered(i == 1).withUnreliable(i == 1).withMaxRetransmits(0)));
                }
                client.setLocalDescription("offer", "assistedPlayer", "p".repeat(128));
                assertTrue(gathered.await(5, TimeUnit.SECONDS));
                // A private host candidate first, as clients list them, then the relay the media takes,
                // played by the client's own socket
                InetSocketAddress offered = new InetSocketAddress("192.168.1.23", 50000);
                String offer = client.localDescription()
                        .replace(" typ host", " typ relay raddr 0.0.0.0 rport 0")
                        .replaceFirst("a=candidate:", "a=candidate:lan 1 UDP 2122260223 "
                                + offered.getAddress().getHostAddress() + " " + offered.getPort()
                                + " typ host\r\na=candidate:");
                var join = new AssistedJoin("12".repeat(16), "instance-test", 1, "34".repeat(16), "K001",
                        identity.fingerprint(), System.currentTimeMillis() + 15000, "1234",
                        TestSignalingProvider.IDENTITY_CPK, "assistedLocalUfrag", "s".repeat(32), offer);
                String answer = endpoint.assist(join, () -> {}).toCompletableFuture().get(5, TimeUnit.SECONDS);
                client.setRemoteDescription(answer.replaceAll("(?m)^a=candidate:[^\\r\\n]*\\r?\\n", "")
                        .replace("a=end-of-candidates\r\n", ""), SessionDescriptionType.ANSWER);

                AdmittedNetherNetChildChannel child = active.get(15, TimeUnit.SECONDS);
                var signaled = child.signaledAddress();
                var media = child.mediaAddress();
                var reported = child.remoteAddress();
                child.close().sync();
                assertTrue(client.closeAndAwait(java.time.Duration.ofSeconds(5)));

                assertEquals(AddressSource.TURN, media.source());
                assertEquals(AddressSource.UNKNOWN, signaled.source(),
                        "an offered candidate is only the client's claim");
                assertNotEquals(offered, reported, "a candidate the client only claimed");
                assertEquals(media.address(), reported,
                        "NXS signaling knew no address, so TURN falls back to the relay");
            }
        } finally {
            endpoint.close().awaitUninterruptibly();
            endpoint.termination().toCompletableFuture().get(6, TimeUnit.SECONDS);
            loop.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
}
