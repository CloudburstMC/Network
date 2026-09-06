package org.cloudburstmc.netty.channel.nethernet.backend;

import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class LibWebRtcSessionSendTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableBackendFailsTheNettyWriteAndReleasesQueuedBuffers(boolean closed) {
        WebRtcSessionListener listener = new WebRtcSessionListener() {
            @Override public void onAnswerReady(String answer) { }
            @Override public void onLocalCandidate(String candidate) { }
            @Override public void onTransportOpen() { }
            @Override public void onMessage(ByteBuffer data) { }
            @Override public void onRemoteAddress(InetSocketAddress address, String type) { }
            @Override public void onTransportClosed() { }
        };
        LibWebRtcServerBackend.Session session = new LibWebRtcServerBackend.Session(listener, ignored -> {}, false);
        EmbeddedChannel clock = new EmbeddedChannel();
        ActiveChild child = new ActiveChild(session);
        ByteBuf first = Unpooled.buffer().writeByte(0).writeByte(1);
        ByteBuf second = Unpooled.buffer().writeByte(0).writeByte(2);
        try {
            clock.freezeTime();
            clock.eventLoop().register(child).syncUninterruptibly();
            clock.runPendingTasks();
            if (closed) {
                // The backend closes before the channel processes its closure notification.
                session.close();
            }
            assertTrue(child.isActive());

            ChannelFuture firstWrite = child.write(first);
            ChannelFuture secondWrite = child.write(second);
            child.flush();
            clock.runPendingTasks();

            assertTrue(firstWrite.isDone());
            assertFalse(firstWrite.isSuccess());
            IllegalStateException failure = assertInstanceOf(IllegalStateException.class, firstWrite.cause().getCause());
            assertEquals(closed ? "WebRTC session is closed" : "Reliable data channel is unavailable", failure.getMessage());
            assertTrue(secondWrite.isDone());
            assertFalse(secondWrite.isSuccess());
            assertEquals(0, first.refCnt());
            assertEquals(0, second.refCnt());
            assertFalse(child.isOpen());
        } finally {
            child.close().syncUninterruptibly();
            clock.finishAndReleaseAll();
            session.close();
        }
    }

    private static final class ActiveChild extends NetherNetChildChannel {
        private ActiveChild(WebRtcSession session) {
            super(null, new InetSocketAddress("127.0.0.1", 19132), new InetSocketAddress("127.0.0.1", 19133));
            attachSession(session);
            markTransportOpen();
        }
    }
}
