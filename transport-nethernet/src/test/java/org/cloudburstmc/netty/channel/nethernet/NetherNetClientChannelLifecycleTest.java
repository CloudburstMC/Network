package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherClientChannelConfig;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.config.NetherNetAddress;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetClientSignaling;
import dev.kastle.webrtc.RTCDataChannelBuffer;
import dev.kastle.webrtc.RTCDataChannelInit;
import dev.kastle.webrtc.RTCDataChannelObserver;
import dev.kastle.webrtc.RTCDataChannelState;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetClientChannelLifecycleTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dataChannelsUseTheReferenceSubprotocolAndReliability(boolean reliable) {
        RTCDataChannelInit init = NetherNetClientChannel.dataChannelInit(reliable);
        assertEquals("", init.protocol);
        assertEquals(reliable, init.ordered);
        assertFalse(init.negotiated);
        assertEquals(reliable ? -1 : 0, init.maxRetransmits);
        assertEquals(-1, init.maxPacketLifeTime);
    }

    @Test
    void duplicateConnectCannotReplaceTheOriginalPromiseOrTarget() {
        try (Harness h = new Harness()) {
            ChannelFuture original = h.connect();
            SocketAddress target = h.channel.remoteAddress();
            ChannelFuture duplicate = h.channel.connect(new NetherNetAddress("other-target"));
            h.pump();
            assertInstanceOf(ConnectionPendingException.class, duplicate.cause());
            assertFalse(original.isDone());
            assertSame(target, h.channel.remoteAddress());
            assertEquals(1, h.signaling.connections.size());
            h.channel.close();
            h.pump();
            assertInstanceOf(ClosedChannelException.class, original.cause());
        }
    }

    @Test
    void anOldSignalingFailureCannotCloseTheRetryOrCancelItsTimeout() {
        try (Harness h = new Harness()) {
            ChannelFuture original = h.connect();
            h.advance(100);
            assertEquals(2, h.signaling.connections.size());
            h.signaling.connections.get(0).completeExceptionally(new IllegalStateException("Old attempt failed"));
            h.pump();
            assertTrue(h.channel.isOpen());
            assertFalse(original.isDone());
            h.advance(99);
            assertEquals(2, h.signaling.connections.size());
            h.advance(1);
            assertEquals(3, h.signaling.connections.size());
            h.advance(100);
            assertInstanceOf(ConnectException.class, original.cause());
            assertFalse(h.channel.isOpen());
        }
    }

    @Test
    void oldSignalingSuccessCannotInitializeTheRetry() {
        try (Harness h = new Harness()) {
            ChannelFuture original = h.connect();
            h.advance(100);
            h.signaling.connections.get(0).complete(List.of());
            h.pump();
            assertEquals(0, h.channel.webRtcInitializations);
            assertTrue(h.channel.isOpen());
            assertFalse(original.isDone());
            h.signaling.connections.get(1).complete(List.of());
            h.pump();
            assertEquals(1, h.channel.webRtcInitializations, "The current attempt must still enter WebRTC initialization");
            assertInstanceOf(ConnectException.class, original.cause());
            assertInstanceOf(InitializationProbe.class, original.cause().getCause());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeLinkageFailureFailsTheCurrentHandshakeImmediately(boolean missingClass) {
        try (Harness h = new Harness()) {
            LinkageError failure = missingClass ? new NoClassDefFoundError("Native binding unavailable")
                    : new UnsatisfiedLinkError("Native library unavailable");
            h.channel.initializationLinkageFailure = failure;
            ChannelFuture connect = h.connect();
            h.signaling.connections.get(0).complete(List.of());
            h.pump();
            assertInstanceOf(ConnectException.class, connect.cause());
            assertSame(failure, connect.cause().getCause());
            assertFalse(h.channel.isOpen());
            assertEquals(1, h.signaling.closes);
            h.advance(1000);
            assertEquals(1, h.signaling.connections.size(), "Linkage failures must not wait for handshake retries");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signalingSuccessQueuedBeforeOrCompletedAfterCloseCannotStartWebRtc(boolean queuedBeforeClose) {
        try (Harness h = new Harness()) {
            ChannelFuture original = h.connect();
            if (queuedBeforeClose) {
                h.signaling.connections.get(0).complete(List.of());
            }
            h.channel.close();
            if (!queuedBeforeClose) {
                h.signaling.connections.get(0).complete(List.of());
            }
            h.pump();
            assertEquals(0, h.channel.webRtcInitializations);
            assertInstanceOf(ClosedChannelException.class, original.cause());
            assertEquals(1, h.signaling.closes);
        }
    }

    @Test
    void notFoundCallbacksBelongToTheAttemptThatRegisteredThem() {
        try (Harness h = new Harness()) {
            ChannelFuture original = h.connect();
            h.advance(100);
            h.signaling.notFoundHandlers.get(0).onNotFound("Old attempt not found");
            h.pump();
            assertTrue(h.channel.isOpen());
            assertFalse(original.isDone());
            h.signaling.notFoundHandlers.get(1).onNotFound("Current attempt not found");
            h.pump();
            assertInstanceOf(ConnectException.class, original.cause());
            assertFalse(h.channel.isOpen());
        }
    }

    @Test
    void synchronousSignalingFailureCompletesTheConnectPromise() {
        try (Harness h = new Harness()) {
            IllegalStateException failure = new IllegalStateException("Signaling rejected connect");
            h.signaling.connectFailure = failure;
            ChannelFuture connect = h.connect();
            assertInstanceOf(ConnectException.class, connect.cause());
            assertSame(failure, connect.cause().getCause());
            assertFalse(h.channel.isOpen());
            assertEquals(1, h.signaling.closes);
        }
    }

    @Test
    void retryCleanupFailureDoesNotLeaveTheConnectPromisePending() {
        try (Harness h = new Harness()) {
            ChannelFuture connect = h.connect();
            IllegalStateException failure = new IllegalStateException("Failed to unregister old attempt");
            h.signaling.removeFailure = failure;
            h.advance(100);
            assertInstanceOf(ConnectException.class, connect.cause());
            assertSame(failure, connect.cause().getCause());
            assertFalse(h.channel.isOpen());
        }
    }

    @Test
    void oneObserverPreservesMessagesBeforeAndDuringOpen() throws Exception {
        try (Harness h = new Harness()) {
            ChannelFuture connect = h.connect();
            AtomicReference<RTCDataChannelState> state = new AtomicReference<>(RTCDataChannelState.CONNECTING);
            RTCDataChannelObserver observer = h.observer(state);
            observer.onMessage(message(42));
            h.pump();
            assertTrue(h.events.isEmpty());
            state.set(RTCDataChannelState.OPEN);
            observer.onStateChange();
            observer.onMessage(message(43));
            h.pump();
            assertEquals(List.of("active", "data:42", "data:43"), h.events);
            assertTrue(connect.isSuccess());
            assertInstanceOf(AlreadyConnectedException.class,
                    h.channel.connect(new NetherNetAddress("other-target")).cause());
            h.signaling.connections.get(0).completeExceptionally(new IllegalStateException("Late signaling result"));
            h.signaling.notFoundHandlers.get(0).onNotFound("Late not-found result");
            h.pump();
            assertTrue(h.channel.isActive());
        }
    }

    @Test
    void retryDiscardsEarlyMessagesAndRejectsTheOldObserver() throws Exception {
        try (Harness h = new Harness()) {
            h.connect();
            AtomicReference<RTCDataChannelState> oldState = new AtomicReference<>(RTCDataChannelState.CONNECTING);
            RTCDataChannelObserver old = h.observer(oldState);
            old.onMessage(message(42));
            assertTrue(h.allocator.metric().usedHeapMemory() > 0);
            h.advance(100);
            assertEquals(0, h.allocator.metric().usedHeapMemory());
            old.onMessage(message(43));
            oldState.set(RTCDataChannelState.OPEN);
            old.onStateChange();

            AtomicReference<RTCDataChannelState> currentState = new AtomicReference<>(RTCDataChannelState.CONNECTING);
            RTCDataChannelObserver current = h.observer(currentState);
            current.onMessage(message(44));
            currentState.set(RTCDataChannelState.OPEN);
            current.onStateChange();
            h.pump();
            assertEquals(List.of("active", "data:44"), h.events);
            assertEquals(0, h.allocator.metric().usedHeapMemory());
            oldState.set(RTCDataChannelState.CLOSED);
            old.onStateChange();
            h.pump();
            assertTrue(h.channel.isActive());
        }
    }

    @Test
    void closeDiscardsEarlyMessagesAndInvalidatesOpenNotifications() throws Exception {
        try (Harness h = new Harness()) {
            h.connect();
            AtomicReference<RTCDataChannelState> state = new AtomicReference<>(RTCDataChannelState.CONNECTING);
            RTCDataChannelObserver observer = h.observer(state);
            observer.onMessage(message(42));
            h.channel.close();
            observer.onMessage(message(43));
            state.set(RTCDataChannelState.OPEN);
            observer.onStateChange();
            h.pump();
            assertFalse(h.channel.isActive());
            assertTrue(h.events.isEmpty());
            assertEquals(0, h.allocator.metric().usedHeapMemory());
        }
    }

    @Test
    void ownedFactoryIsNotAllocatedBeforeWebRtcIsNeeded() throws Exception {
        EmbeddedChannel clock = new EmbeddedChannel();
        FakeSignaling signaling = new FakeSignaling();
        NetherNetClientChannel channel = new NetherNetClientChannel(signaling);
        try {
            clock.eventLoop().register(channel).sync();
            channel.connect(new NetherNetAddress("target"));
            clock.runPendingTasks();
            assertNull(field(channel, "factory"));
            channel.close().sync();
            signaling.connections.get(0).complete(List.of());
            clock.runPendingTasks();
            assertNull(field(channel, "factory"));
            assertEquals(1, signaling.closes);
        } finally {
            channel.close().syncUninterruptibly();
            clock.finishAndReleaseAll();
        }
    }

    @Test
    void invalidTimeoutAndRetryValuesAreRejectedWhileZeroRetriesWork() {
        try (Harness h = new Harness()) {
            assertThrows(IllegalArgumentException.class,
                    () -> h.channel.config().setOption(NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> h.channel.config().setOption(NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS, -1));
            assertThrows(IllegalArgumentException.class,
                    () -> h.channel.config().setOption(NetherChannelOption.NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS, -1));
            h.channel.config().setOption(NetherChannelOption.NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS, 0);
            ChannelFuture connect = h.connect();
            h.advance(100);
            assertInstanceOf(ConnectException.class, connect.cause());
            assertEquals(1, h.signaling.connections.size());
        }
    }

    private static RTCDataChannelBuffer message(int value) {
        return new RTCDataChannelBuffer(ByteBuffer.wrap(new byte[]{(byte) value}), true);
    }

    private static Object field(NetherNetClientChannel channel, String name) throws Exception {
        Field field = NetherNetClientChannel.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(channel);
    }

    private static final class Harness implements AutoCloseable {
        private final EmbeddedChannel clock = new EmbeddedChannel();
        private final FakeSignaling signaling = new FakeSignaling();
        private final ProbeClient channel = new ProbeClient(signaling);
        private final UnpooledByteBufAllocator allocator = new UnpooledByteBufAllocator(false);
        private final List<String> events = new ArrayList<>();

        private Harness() {
            clock.freezeTime();
            channel.config().setAllocator(allocator);
            channel.config().setOption(NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS, 100);
            channel.config().setOption(NetherChannelOption.NETHER_CLIENT_MAX_HANDSHAKE_ATTEMPTS, 2);
            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) {
                    events.add("active");
                }

                @Override
                public void channelRead(ChannelHandlerContext ctx, Object message) {
                    ByteBuf buffer = (ByteBuf) message;
                    try {
                        events.add("data:" + buffer.readUnsignedByte());
                    } finally {
                        buffer.release();
                    }
                }
            });
            clock.eventLoop().register(channel).syncUninterruptibly();
            pump();
        }

        private ChannelFuture connect() {
            ChannelFuture connect = channel.connect(new NetherNetAddress("target"));
            pump();
            return connect;
        }

        private RTCDataChannelObserver observer(AtomicReference<RTCDataChannelState> state) throws Exception {
            return channel.createReliableObserver(state::get, (Integer) field(channel, "attemptGeneration"));
        }

        private void pump() {
            clock.runPendingTasks();
            clock.checkException();
        }

        private void advance(long millis) {
            clock.advanceTimeBy(millis, TimeUnit.MILLISECONDS);
            clock.runScheduledPendingTasks();
            pump();
        }

        @Override
        public void close() {
            channel.close().syncUninterruptibly();
            clock.runPendingTasks();
            clock.finishAndReleaseAll();
        }
    }

    private static final class ProbeClient extends NetherNetClientChannel {
        private int webRtcInitializations;
        private LinkageError initializationLinkageFailure;

        private ProbeClient(NetherNetClientSignaling signaling) {
            super(null, signaling);
            config = new DefaultNetherClientChannelConfig(this) {
                @Override
                public <T> T getOption(ChannelOption<T> option) {
                    if (option == NetherChannelOption.NETHER_PORT_ALLOCATOR_CONFIG) {
                        webRtcInitializations++;
                        if (initializationLinkageFailure != null) {
                            throw initializationLinkageFailure;
                        }
                        throw new InitializationProbe();
                    }
                    return super.getOption(option);
                }
            };
        }
    }

    private static final class InitializationProbe extends RuntimeException {
    }

    private static final class FakeSignaling implements NetherNetClientSignaling {
        private final List<CompletableFuture<List<IceServerInfo>>> connections = new ArrayList<>();
        private final List<NotFoundHandler> notFoundHandlers = new ArrayList<>();
        private RuntimeException connectFailure;
        private RuntimeException removeFailure;
        private int closes;

        @Override
        public CompletableFuture<List<IceServerInfo>> connect(SocketAddress remoteAddress) {
            if (connectFailure != null) {
                throw connectFailure;
            }
            CompletableFuture<List<IceServerInfo>> connection = new CompletableFuture<>();
            connections.add(connection);
            return connection;
        }

        @Override public void setNotFoundHandler(NotFoundHandler handler) { notFoundHandlers.add(handler); }
        @Override public void sendSignal(String targetNetworkId, String data) { }
        @Override public void setSignalHandler(long connectionId, SignalHandler handler) { }
        @Override
        public void removeSignalHandler(long connectionId) {
            RuntimeException failure = removeFailure;
            removeFailure = null;
            if (failure != null) {
                throw failure;
            }
        }
        @Override public String getLocalNetworkId() { return "local"; }
        @Override public void close() { closes++; }
    }
}
