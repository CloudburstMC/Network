package org.cloudburstmc.netty.channel.nethernet;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tel.schich.libdatachannel.*;
import tel.schich.libdatachannel.exception.LibDataChannelException;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class NetherNetServerOwnershipTest {
    @Test
    void signalingSetupFailureClosesAllocatedPeer() throws Exception {
        assertSetupFailureClosesPeer(false);
    }

    @Test
    void signalingCleanupFailureCannotSkipPeerDeletion() throws Exception {
        assertSetupFailureClosesPeer(true);
    }

    private void assertSetupFailureClosesPeer(boolean failRemoval) throws Exception {
        try (var server = new NetherNetTestServer()) {
            server.signaling.failSetup = true;
            server.signaling.failRemoval = failRemoval;
            server.bind();
            assertPeerClosed(server, server.accept("unused"));
        }
    }

    @Test
    void negotiationFailureClosesAllocatedPeer() throws Exception {
        try (var server = new NetherNetTestServer()) {
            server.bind();
            assertPeerClosed(server, server.accept("not an SDP offer"));
        }
    }

    @Test
    void handshakeTimeoutClosesRegisteredChildAndItsPeer() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            server.server.config().setOption(NetherChannelOption.NETHER_SERVER_RTC_HANDSHAKE_TIMEOUT_SECONDS, 1);
            server.bind();
            client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            var accepted = server.accept(NetherNetTestServer.offer(client));
            assertTrue(accepted.child().isRegistered());
            assertPeerClosed(server, accepted);
        }
    }

    @Test
    void unknownAndDuplicateChannelsAreClosedAndOriginalChannelStillCarriesMessages() throws Exception {
        try (var server = new NetherNetTestServer();
             PeerConnection client = PeerConnection.createPeer(NetherNetTestServer.CONFIG)) {
            server.bind();
            DataChannel reliable = client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
            client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
            NetherNetChildChannel child = server.connect(client);
            var received = new CompletableFuture<Integer>();
            child.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, ByteBuf message) {
                    received.complete(message.readInt());
                }
            });
            for (String label : new String[]{"unexpected", NetherNetConstants.RELIABLE_CHANNEL_LABEL,
                    NetherNetConstants.UNRELIABLE_CHANNEL_LABEL}) {
                var closed = new CompletableFuture<Void>();
                try (DataChannel extra = client.createDataChannel(label)) {
                    extra.onClosed.register(channel -> closed.complete(null));
                    if (extra.isClosed()) closed.complete(null);
                    closed.get(5, TimeUnit.SECONDS);
                }
            }
            reliable.sendMessage(ByteBuffer.allocateDirect(5).put((byte) 0).putInt(1234).flip());
            assertEquals(1234, received.get(5, TimeUnit.SECONDS));
            assertTrue(child.isActive());
        }
    }

    private static void assertPeerClosed(NetherNetTestServer server, NetherNetTestServer.Accepted accepted)
            throws Exception {
        assertTrue(accepted.child().closeFuture().await(5, TimeUnit.SECONDS));
        server.signaling.removed.get(5, TimeUnit.SECONDS);
        assertNull(accepted.child().peerConnection);
        assertThrows(LibDataChannelException.class, () -> accepted.peer().createDataChannel("must-be-deleted"));
    }
}
