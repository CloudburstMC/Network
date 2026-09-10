package org.cloudburstmc.netty.signalling.admission;

import com.google.gson.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.cloudburstmc.netty.signalling.ProviderTransport;
import tel.schich.libdatachannel.*;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Separate-process loopback bench. Host input is one background key snapshot, before any offer exists.
 */
public final class NativeAdmissionBench {
    private static final Gson JSON = new Gson();

    private static synchronized void emit(String kind, Object value) {
        JsonObject event = new JsonObject();
        event.addProperty("kind", kind);
        event.add("value", JSON.toJsonTree(value));
        System.out.println(JSON.toJson(event));
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("host")) {
            host(args);
        } else if (args[0].equals("client")) {
            client();
        } else {
            throw new IllegalArgumentException("host or client");
        }
    }

    private static void host(String[] args) throws Exception {
        // No stdin reader, HTTP client, lookup store or offer input remains after background setup.
        JsonObject key;
        try (var input = new BufferedReader(new InputStreamReader(System.in))) {
            key = JsonParser.parseString(input.readLine()).getAsJsonObject();
            if (input.readLine() != null) {
                throw new IllegalArgumentException("Only one background key snapshot permitted");
            }
        }
        var group = new DefaultEventLoopGroup(2);
        AtomicInteger delivered = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ServerBootstrap bootstrap = new ServerBootstrap().group(group)
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
                            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf data) {
                                delivered.getAndUpdate(mask -> mask | (reliable ? 1 : 2));
                                ctx.writeAndFlush(new NetherNetPacket(data.retainedDuplicate(), reliable));
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) {
                                failure.set(error);
                                ctx.close();
                            }
                        });
                    }
                });
        NativeProviderTransport host = null;
        try {
            host = NativeProviderTransport.open(bootstrap,
                    new InetSocketAddress("127.0.0.1", Integer.parseInt(args[1])), Path.of(args[2]), Path.of(args[3]),
                    new AdmissionGate.Limits(4, 8, 2, 10_000)).toCompletableFuture().get(5, TimeUnit.SECONDS);
            host.installTicketKeys(List.of(new ProviderTransport.TicketKey(key.get("keyId").getAsString(),
                    key.get("secret").getAsString()))).toCompletableFuture().get();
            key = null;
            emit("profile", host.hostProfile().toCompletableFuture().get());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(100);
            Path stop = Path.of(args[4]);
            while (!Files.exists(stop)) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("Bench host deadline");
                }
                if (failure.get() != null) {
                    throw new IllegalStateException("Host pipeline failure", failure.get());
                }
                var endpoint = host.channel();
                emit("stats", Map.of("admission", endpoint.admissionStats(), "native", endpoint.nativeStats(),
                        "nativeCreationAttempts", PeerConnection.nativeCreationAttempts(), "hostCreations",
                        endpoint.creationAttempts(), "deliveredChannels", delivered.get()));
                for (var event : endpoint.pollEvents()) {
                    emit("stage", Map.of("stage", event.stage(), "ticketId", event.ticketId(),
                            "occurredAt", Instant.ofEpochMilli(event.occurredAt()).toString(), "reason",
                            event.reason(),
                            "validationToCreationNanos", event.validationToCreationNanos()));
                }
                Thread.sleep(50);
            }
            host.close().toCompletableFuture().get(6, TimeUnit.SECONDS);
            try (var reuse = new DatagramSocket(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[1])))) {
                emit("closed", Map.of("udpReleased", reuse.getLocalPort() == Integer.parseInt(args[1])));
            }
        } finally {
            if (host != null) {
                host.close().toCompletableFuture().get(6, TimeUnit.SECONDS);
            }
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    private static void client() throws Exception {
        var configuration = PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true)
                .withBindAddress(InetAddress.getByName("127.0.0.1"));
        PeerConnection peer = PeerConnection.createPeer(configuration, Runnable::run);
        try (var input = new BufferedReader(new InputStreamReader(System.in))) {
            CountDownLatch echoes = new CountDownLatch(2);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            for (boolean reliable : new boolean[]{true, false}) {
                var channel = peer.createDataChannel(reliable ? "ReliableDataChannel" : "UnreliableDataChannel",
                        DataChannelInitSettings.DEFAULT.withReliability(
                                new DataChannelReliability(!reliable, !reliable, 0, 0)));
                byte[] payload = new byte[reliable ? 20013 : 7];
                Arrays.fill(payload, (byte) (reliable ? 31 : 47));
                var decoder = new NetherNetFrameDecoder();
                channel.onMessage.register(DataChannelCallback.Message.handleBinary((dc, bytes) -> {
                    try {
                        byte[] frame = new byte[bytes.remaining()];
                        bytes.get(frame);
                        byte[] message = decoder.decode(frame, reliable);
                        if (message != null) {
                            if (!Arrays.equals(payload, message)) {
                                throw new IllegalStateException("Echo payload mismatch");
                            }
                            echoes.countDown();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                }));
                channel.onOpen.register(dc -> {
                    int chunks = (payload.length + 9998) / 9999;
                    for (int i = 0; i < chunks; i++) {
                        int length = Math.min(9999, payload.length - i * 9999);
                        ByteBuffer frame = ByteBuffer.allocateDirect(length + 1);
                        frame.put((byte) (chunks - i - 1)).put(payload, i * 9999, length).flip();
                        dc.sendMessage(frame);
                    }
                });
            }
            peer.setLocalDescription("offer", "nativeBenchClient", "p".repeat(32));
            emit("offer", Map.of("sdp", peer.localDescription()));
            JsonObject answer = JsonParser.parseString(input.readLine()).getAsJsonObject();
            peer.setRemoteDescription(answer.get("sdp").getAsString(), SessionDescriptionType.ANSWER);
            if (!echoes.await(15, TimeUnit.SECONDS) || failure.get() != null) {
                throw new IllegalStateException("Both channel echoes required", failure.get());
            }
            emit("connected", Map.of("reliableBytes", 20013, "unreliableBytes", 7));
            if (!"stop".equals(input.readLine())) {
                throw new IllegalStateException("Expected bench stop");
            }
        } finally {
            if (!peer.closeAndAwait(Duration.ofSeconds(5))) {
                throw new IllegalStateException("Client cleanup timeout");
            }
        }
    }
}
