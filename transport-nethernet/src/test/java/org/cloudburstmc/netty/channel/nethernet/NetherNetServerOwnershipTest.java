package org.cloudburstmc.netty.channel.nethernet;

import io.netty.channel.*;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.*;
import tel.schich.libdatachannel.exception.LibDataChannelException;

import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class NetherNetServerOwnershipTest {
    private static final PeerConnectionConfiguration CONFIG =
            PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true);

    @Test
    void signalingSetupFailureClosesAllocatedPeer() throws Exception {
        assertSetupFailureClosesPeer(false);
    }

    @Test
    void signalingCleanupFailureCannotSkipPeerDeletion() throws Exception {
        assertSetupFailureClosesPeer(true);
    }

    private void assertSetupFailureClosesPeer(boolean failRemoval) throws Exception {
        var signaling = new TestSignaling();
        signaling.failSetup = true;
        signaling.failRemoval = failRemoval;
        var server = new NetherNetServerChannel(signaling);
        var group = new DefaultEventLoopGroup(1);
        try {
            group.register(server).sync();
            assertThrows(IllegalStateException.class, () -> server.acceptConnection(1, "unused", "test"));
            assertNotNull(signaling.peer);
            assertTrue(signaling.removed, "the failed setup must remove its signaling handler");
            assertPeerDeleted(signaling.peer);
        } finally {
            if (signaling.peer != null) signaling.peer.close();
            server.close().sync();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    @Test
    void negotiationFailureClosesAllocatedPeer() throws Exception {
        var signaling = new TestSignaling();
        var server = new NetherNetServerChannel(signaling);
        var group = new DefaultEventLoopGroup(1);
        try {
            group.register(server).sync();
            server.acceptConnection(2, "not an SDP offer", "test");
            assertTrue(signaling.removed);
            assertPeerDeleted(signaling.peer);
        } finally {
            if (signaling.peer != null) signaling.peer.close();
            server.close().sync();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    @Test
    void timeoutClosesPeerEvenWhenChildWasNeverRegistered() throws Exception {
        var signaling = new TestSignaling();
        var server = new NetherNetServerChannel(signaling);
        server.config().setOption(NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS, 1);
        var group = new DefaultEventLoopGroup(1);
        var accepted = new CompletableFuture<NetherNetChildChannel>();
        server.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                accepted.complete((NetherNetChildChannel) msg); // intentionally do not register the child
            }
        });
        try (PeerConnection client = PeerConnection.createPeer(CONFIG)) {
            group.register(server).sync();
            client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.setLocalDescription("offer");
            server.acceptConnection(3, client.localDescription(), "test");
            assertFalse(accepted.get(5, TimeUnit.SECONDS).isRegistered());
            // The barrier runs after the one-second handshake timeout on the same event loop.
            server.eventLoop().schedule(() -> {}, 2, TimeUnit.SECONDS).sync();
            assertPeerDeleted(signaling.peer);
        } finally {
            if (accepted.isDone()) accepted.join().unsafe().closeForcibly();
            if (signaling.peer != null) signaling.peer.close();
            server.close().sync();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    @Test
    void unknownAndDuplicateDataChannelsAreDeletedWithoutReplacingAcceptedChannels() throws Exception {
        var server = new NetherNetServerChannel(new TestSignaling());
        Class<?> type = Class.forName(NetherNetServerChannel.class.getName() + "$ServerPeerConnectionObserver");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var observer = constructor.newInstance(server, 4L, "test", "", null);
        var callback = type.getDeclaredMethod("onDataChannel", DataChannel.class);
        callback.setAccessible(true);
        try (PeerConnection peer = PeerConnection.createPeer(CONFIG)) {
            DataChannel unknown = peer.createDataChannel("unexpected");
            callback.invoke(observer, unknown);
            assertThrows(LibDataChannelException.class, unknown::maxMessageSize);
            for (String label : new String[]{NetherNetConstants.RELIABLE_CHANNEL_LABEL,
                    NetherNetConstants.UNRELIABLE_CHANNEL_LABEL}) {
                DataChannel first = peer.createDataChannel(label);
                callback.invoke(observer, first);
                DataChannel duplicate = peer.createDataChannel(label);
                callback.invoke(observer, duplicate);
                assertThrows(LibDataChannelException.class, duplicate::maxMessageSize);
                assertEquals(label, first.label());
                Field selected = type.getDeclaredField(label.equals(NetherNetConstants.RELIABLE_CHANNEL_LABEL)
                        ? "reliable" : "unreliable");
                selected.setAccessible(true);
                assertSame(first, selected.get(observer));
            }
        } finally {
            server.unsafe().closeForcibly();
        }
    }

    @Test
    void unsupportedChannelCanBeDeletedInsideItsNativeArrivalCallback() throws Exception {
        var server = new NetherNetServerChannel(new TestSignaling());
        Class<?> type = Class.forName(NetherNetServerChannel.class.getName() + "$ServerPeerConnectionObserver");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var observer = constructor.newInstance(server, 5L, "test", "", null);
        var callback = type.getDeclaredMethod("onDataChannel", DataChannel.class);
        callback.setAccessible(true);
        var received = new CompletableFuture<DataChannel>();
        try (PeerConnection offerer = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT);
             PeerConnection answerer = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT)) {
            offerer.onLocalDescription.register((peer, sdp, kind) -> answerer.setRemoteDescription(sdp, kind));
            answerer.onLocalDescription.register((peer, sdp, kind) -> offerer.setRemoteDescription(sdp, kind));
            offerer.onLocalCandidate.register((peer, candidate, mid) -> answerer.addRemoteCandidate(candidate, mid));
            answerer.onLocalCandidate.register((peer, candidate, mid) -> offerer.addRemoteCandidate(candidate, mid));
            answerer.onDataChannel.register((peer, channel) -> {
                try {
                    callback.invoke(observer, channel);
                    received.complete(channel);
                } catch (ReflectiveOperationException e) {
                    received.completeExceptionally(e);
                }
            });
            offerer.createDataChannel("unexpected");
            offerer.setLocalDescription("offer");
            DataChannel rejected = received.get(10, TimeUnit.SECONDS);
            assertThrows(LibDataChannelException.class, rejected::maxMessageSize);
        } finally {
            server.unsafe().closeForcibly();
        }
    }

    private static void assertPeerDeleted(PeerConnection peer) {
        assertNotNull(peer);
        assertThrows(LibDataChannelException.class, () -> peer.createDataChannel("must-be-deleted"));
    }

    private static class TestSignaling implements NetherNetServerSignaling {
        PeerConnection peer;
        boolean failSetup, failRemoval, removed;

        public void setSignalHandler(long id, SignalHandler handler) {
            // Capture the real peer owned by the handler, without replacing native creation with a mock.
            for (Field field : handler.getClass().getDeclaredFields()) {
                if (field.getType() == PeerConnection.class) {
                    try {
                        field.setAccessible(true);
                        peer = (PeerConnection) field.get(handler);
                    } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                }
            }
            if (failSetup) throw new IllegalStateException("signaling setup failed");
        }
        public void removeSignalHandler(long id) {
            removed = true;
            if (failRemoval) throw new IllegalStateException("signaling cleanup failed");
        }
        public boolean usesTrickleIce() { return false; }
        public void bind(SocketAddress address, EventLoop loop) { }
        public void setNewConnectionHandler(NewConnectionHandler handler) { }
        public void setAdvertisementData(PongData data) { }
        public String getLocalNetworkId() { return "test"; }
        public boolean isActive() { return true; }
        public void close() { }
    }
}
