package org.cloudburstmc.netty.channel.nethernet.backend;

import org.cloudburstmc.netty.channel.nethernet.NetherNetChannel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetClientChannel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetClientSignaling;
import io.github.sendablemetatype.webrtc.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultEventLoop;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "webrtc.nativeTests", matches = "true")
@Timeout(20)
class WebRtcNativeSendTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void acceptedNativeSendCompletesTheWriteAndDeliversItsWindow(boolean server, boolean direct) throws Exception {
        try (Peers peers = new Peers()) {
            peers.connect();
            DefaultEventLoop loop = new DefaultEventLoop();
            NetherNetChannel channel = channel(server, peers);
            try {
                loop.register(channel).sync();
                ByteBuf bytes = direct ? Unpooled.directBuffer(5) : Unpooled.buffer(5);
                bytes.writeBytes(new byte[]{99, 0, 1, 2, 99}).setIndex(1, 4);
                ChannelFuture write = channel.writeAndFlush(bytes);
                assertTrue(write.await(5, TimeUnit.SECONDS));
                assertTrue(write.isSuccess(), () -> String.valueOf(write.cause()));
                assertEquals(0, bytes.refCnt());
                assertArrayEquals(new byte[]{0, 1, 2}, peers.messages.poll(5, TimeUnit.SECONDS));
            } finally {
                channel.close().syncUninterruptibly();
                loop.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeRejectionReachesTheNettyWrite(boolean server) throws Exception {
        try (Peers peers = new Peers()) {
            // Model the interval where Netty is active but the native transport cannot send.
            DefaultEventLoop loop = new DefaultEventLoop();
            NetherNetChannel channel = channel(server, peers);
            try {
                loop.register(channel).sync();
                ByteBuf bytes = Unpooled.buffer(2).writeByte(0).writeByte(1);
                ChannelFuture write = channel.writeAndFlush(bytes);
                assertTrue(write.await(5, TimeUnit.SECONDS));
                assertFalse(write.isSuccess());
                assertTrue(write.cause().getMessage().contains("[INVALID_STATE]"), write.cause().toString());
                assertEquals(0, bytes.refCnt());
                assertFalse(channel.isOpen());
            } finally {
                channel.close().syncUninterruptibly();
                loop.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            }
        }
    }

    private static NetherNetChannel channel(boolean server, Peers peers) throws Exception {
        if (server) {
            LibWebRtcServerBackend.Session session = new LibWebRtcServerBackend.Session(new WebRtcSessionListener() {
                @Override public void onAnswerReady(String sdp) { }
                @Override public void onLocalCandidate(String candidate) { }
                @Override public void onTransportOpen() { }
                @Override public void onMessage(ByteBuffer data) { }
                @Override public void onRemoteAddress(InetSocketAddress address, String type) { }
                @Override public void onTransportClosed() { }
            }, ignored -> { }, false);
            session.observer.onDataChannel(peers.sender);
            return new ActiveChild(session);
        }
        ActiveClient client = new ActiveClient(peers.factory);
        Field reliable = NetherNetClientChannel.class.getDeclaredField("reliableChannel");
        reliable.setAccessible(true);
        reliable.set(client, peers.sender);
        Field handshake = NetherNetClientChannel.class.getDeclaredField("handshakeComplete");
        handshake.setAccessible(true);
        handshake.setBoolean(client, true);
        return client;
    }

    private static final class ActiveChild extends NetherNetChildChannel {
        ActiveChild(WebRtcSession session) {
            super(null, new InetSocketAddress("127.0.0.1", 19132), new InetSocketAddress("127.0.0.1", 19133));
            attachSession(session);
            markTransportOpen();
        }
    }

    private static final class ActiveClient extends NetherNetClientChannel {
        ActiveClient(PeerConnectionFactory factory) {
            super(factory, new NetherNetClientSignaling() {
                @Override public CompletableFuture<List<IceServerInfo>> connect(SocketAddress address) {
                    return CompletableFuture.completedFuture(List.of());
                }
                @Override public void setNotFoundHandler(NotFoundHandler handler) { }
                @Override public void sendSignal(String target, String data) { }
                @Override public void setSignalHandler(long id, SignalHandler handler) { }
                @Override public void removeSignalHandler(long id) { }
                @Override public String getLocalNetworkId() { return "1"; }
                @Override public void close() { }
            });
            markTransportOpen();
        }
    }

    private static final class Peers implements AutoCloseable {
        final PeerConnectionFactory factory = new PeerConnectionFactory();
        final RTCPeerConnection caller;
        final RTCPeerConnection callee;
        final RTCDataChannel sender;
        volatile RTCDataChannel receiver;
        final CompletableFuture<Void> senderOpen = new CompletableFuture<>();
        final CompletableFuture<Void> receiverOpen = new CompletableFuture<>();
        final BlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();

        Peers() {
            RTCPeerConnection[] peers = new RTCPeerConnection[2];
            caller = factory.createPeerConnection(new RTCConfiguration(), candidate -> peers[1].addIceCandidate(candidate));
            callee = factory.createPeerConnection(new RTCConfiguration(), new PeerConnectionObserver() {
                @Override public void onIceCandidate(RTCIceCandidate candidate) { peers[0].addIceCandidate(candidate); }
                @Override public void onDataChannel(RTCDataChannel channel) {
                    receiver = channel;
                    observe(channel, receiverOpen);
                }
            });
            peers[0] = caller;
            peers[1] = callee;
            sender = caller.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL, new RTCDataChannelInit());
            observe(sender, senderOpen);
        }

        void observe(RTCDataChannel channel, CompletableFuture<Void> opened) {
            channel.registerObserver(new RTCDataChannelObserver() {
                @Override public void onBufferedAmountChange(long bytes) { }
                @Override public void onStateChange() {
                    if (channel.getState() == RTCDataChannelState.OPEN) { opened.complete(null); }
                }
                @Override public void onMessage(RTCDataChannelBuffer buffer) {
                    byte[] copy = new byte[buffer.data.remaining()];
                    buffer.data.duplicate().get(copy);
                    messages.add(copy);
                }
            });
            if (channel.getState() == RTCDataChannelState.OPEN) { opened.complete(null); }
        }

        void connect() throws Exception {
            CompletableFuture<RTCSessionDescription> offer = new CompletableFuture<>();
            caller.createOffer(new RTCOfferOptions(), descriptionObserver(offer));
            RTCSessionDescription offered = offer.get(5, TimeUnit.SECONDS);
            setDescription(caller, offered, true);
            setDescription(callee, offered, false);
            CompletableFuture<RTCSessionDescription> answer = new CompletableFuture<>();
            callee.createAnswer(new RTCAnswerOptions(), descriptionObserver(answer));
            RTCSessionDescription answered = answer.get(5, TimeUnit.SECONDS);
            setDescription(callee, answered, true);
            setDescription(caller, answered, false);
            senderOpen.get(5, TimeUnit.SECONDS);
            receiverOpen.get(5, TimeUnit.SECONDS);
        }

        private static CreateSessionDescriptionObserver descriptionObserver(CompletableFuture<RTCSessionDescription> future) {
            return new CreateSessionDescriptionObserver() {
                @Override public void onSuccess(RTCSessionDescription description) { future.complete(description); }
                @Override public void onFailure(String error) { future.completeExceptionally(new IllegalStateException(error)); }
            };
        }

        private static void setDescription(RTCPeerConnection peer, RTCSessionDescription description, boolean local) throws Exception {
            CompletableFuture<Void> done = new CompletableFuture<>();
            SetSessionDescriptionObserver observer = new SetSessionDescriptionObserver() {
                @Override public void onSuccess() { done.complete(null); }
                @Override public void onFailure(String error) { done.completeExceptionally(new IllegalStateException(error)); }
            };
            if (local) { peer.setLocalDescription(description, observer); }
            else { peer.setRemoteDescription(description, observer); }
            done.get(5, TimeUnit.SECONDS);
        }

        @Override public void close() {
            sender.unregisterObserver();
            if (receiver != null) { receiver.unregisterObserver(); }
            caller.close();
            callee.close();
            sender.dispose();
            if (receiver != null) { receiver.dispose(); }
            factory.dispose();
        }
    }
}
