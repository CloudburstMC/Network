// SPDX-License-Identifier: MPL-2.0
// Adapted from teamziax/libdatachannel-java at 40f2c329dcb63a762a701b987dc9995d76fd18c7.
// The original file license is preserved; see LICENSES/MPL-2.0.txt.
package org.cloudburstmc.netty.signalling.admission;

import org.cloudburstmc.netty.channel.nethernet.admission.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import tel.schich.libdatachannel.*;

import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Standalone wire probe using the same admission adapter as the running host.
 */
public final class AdmissionPrimitiveProbe {
    static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    static final int PORT = 49184;

    static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        var identity = NativeHostIdentity.load(Path.of(args[0]), Path.of(args[1]));
        for (int passwordLength : new int[]{24, 32, 91}) {
            run(identity, passwordLength, false);
        }
        run(identity, 24, true);
    }

    static void run(NativeHostIdentity identity, int passwordLength, boolean wrongFingerprint) throws Exception {
        var validator = new StatelessAdmissionValidator(TestSignallingProvider.AUDIENCE, 60_000);
        validator.installKeys(
                List.of(new StatelessAdmissionValidator.TicketKey("K001", TestSignallingProvider.SECRET)));
        var endpoint = new NativeAdmissionServerChannel(identity, validator, new AdmissionGate.Limits(4, 8, 2, 10_000));
        var group = new DefaultEventLoopGroup(1);
        var messages = new CountDownLatch(2);
        var failure = new AtomicReference<Throwable>();
        var received = new AtomicInteger();
        try (var client = PeerConnection.createPeer(
                PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true).withBindAddress(LOOPBACK),
                Runnable::run)) {
            new ServerBootstrap().group(group).channelFactory(() -> endpoint)
                    .childHandler(new ChannelInitializer<AdmittedNetherNetChildChannel>() {
                        @Override
                        protected void initChannel(AdmittedNetherNetChildChannel child) {
                            child.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                                boolean reliable = true;

                                @Override
                                public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                                    if (event instanceof NetherNetPacket.Delivery delivery) {
                                        reliable = delivery.reliable();
                                    }
                                }

                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, ByteBuf message) {
                                    int bit = reliable ? 1 : 2;
                                    check(message.readableBytes() == 1 && message.readByte() == bit,
                                            "channel identity and payload");
                                    received.getAndUpdate(mask -> mask | bit);
                                    messages.countDown();
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) {
                                    failure.set(error);
                                    ctx.close();
                                }
                            });
                        }
                    }).bind(LOOPBACK, PORT).sync();
            for (boolean reliable : new boolean[]{true, false}) {
                var channel = client.createDataChannel(reliable ? "ReliableDataChannel" : "UnreliableDataChannel",
                        DataChannelInitSettings.DEFAULT.withReliability(
                                new DataChannelReliability(!reliable, !reliable, 0, 0)));
                channel.onOpen.register(dc -> dc.sendMessage(
                        ByteBuffer.allocateDirect(2).put((byte) 0).put((byte) (reliable ? 1 : 2)).flip()));
            }
            client.setLocalDescription("offer", "clientFixtureUf", "p".repeat(passwordLength));
            var answer = TestSignallingProvider.answer(client.localDescription(), identity.fingerprint(), PORT,
                    System.currentTimeMillis() + 30_000, TestSignallingProvider.AUDIENCE, wrongFingerprint);
            check(answer.token().length() == 8 + (int) Math.ceil((95 + passwordLength) * 4.0 / 3), "token byte budget");
            check(endpoint.nativeStats()[2] == 0, "no host peer before an incoming request");
            client.setRemoteDescription(answer.sdp(), SessionDescriptionType.ANSWER);
            if (wrongFingerprint) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                boolean rejected = false;
                while (!rejected && System.nanoTime() < deadline) {
                    rejected = endpoint.pollEvents().stream().anyMatch(event -> event.stage().equals("ticket.failed"));
                    if (!rejected) {
                        Thread.sleep(10);
                    }
                }
                check(rejected && received.get() == 0,
                        "DTLS rejects a client certificate that does not match its token");
            } else {
                check(messages.await(10, TimeUnit.SECONDS), "both channels deliver messages");
                check(failure.get() == null && received.get() == 3, "both channel payloads match");
            }
            check(endpoint.creationAttempts() == 1 && endpoint.nativeStats()[5] == 1,
                    "one peer and one admission notification");
            System.out.println(
                    "native-admission PASS ufragChars=" + answer.token().length() + " wrongClientFingerprint="
                            + wrongFingerprint + " channels=" + received.get() + " admissionNotifications=1");
        } finally {
            endpoint.close().awaitUninterruptibly();
            endpoint.termination().toCompletableFuture().get(6, TimeUnit.SECONDS);
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
}
