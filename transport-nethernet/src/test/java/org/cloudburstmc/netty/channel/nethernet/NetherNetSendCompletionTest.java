package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcSession;
import org.cloudburstmc.netty.channel.nethernet.codec.NetherNetFramingCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class NetherNetSendCompletionTest {
    @Test
    void writesWaitForAcceptanceAndCompleteInOrder() {
        try (Harness h = new Harness()) {
            ByteBuf first = payload(1);
            ByteBuf second = payload(2);
            ChannelFuture a = h.channel.write(first);
            ChannelFuture b = h.channel.writeAndFlush(second);
            assertEquals(2, h.session.callbacks.size());
            assertFalse(a.isDone());
            assertFalse(b.isDone());
            assertEquals(0, first.refCnt());
            h.session.complete(1, null);
            h.run();
            assertFalse(b.isDone());
            h.session.complete(0, null);
            h.run();
            assertTrue(a.isSuccess());
            assertTrue(b.isSuccess());
            assertEquals(0, first.refCnt());
            assertEquals(0, second.refCnt());
            h.session.complete(0, new IOException("duplicate callback"));
            h.run();
            assertTrue(h.channel.isOpen());
        }
    }

    @Test
    void repeatedFlushesCoalesceWithoutSerializingEachSend() {
        try (Harness h = new Harness()) {
            List<ChannelFuture> writes = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                writes.add(h.channel.writeAndFlush(payload(i)));
            }
            assertEquals(1, h.session.callbacks.size());
            h.run();
            assertEquals(100, h.session.callbacks.size());
            assertTrue(writes.stream().noneMatch(ChannelFuture::isDone));
            h.session.callbacks.forEach(callback -> callback.accept(null));
            h.run();
            assertTrue(writes.stream().allMatch(ChannelFuture::isSuccess));
        }
    }

    @Test
    void acceptanceDoesNotCountAsBufferDrain() {
        try (Harness h = new Harness()) {
            ChannelFuture first = h.channel.write(Unpooled.buffer(2 * 1024 * 1024).writeZero(2 * 1024 * 1024));
            ChannelFuture second = h.channel.writeAndFlush(payload(1));
            assertEquals(1, h.session.callbacks.size());
            h.session.complete(0, null);
            h.run();
            assertTrue(first.isSuccess());
            assertFalse(second.isDone());
            assertEquals(1, h.session.callbacks.size());
            h.channel.onEngineBytesSent(-1);
            h.channel.onEngineBytesSent(0);
            h.run();
            assertEquals(1, h.session.callbacks.size());
            h.channel.onEngineBytesSent(2 * 1024 * 1024);
            h.run();
            assertEquals(2, h.session.callbacks.size());
            assertFalse(second.isDone());
            h.session.complete(1, null);
            h.run();
            assertTrue(second.isSuccess());
        }
    }

    @Test
    void completionListenersSubmitTheirNextBatchAfterDraining() {
        try (Harness h = new Harness()) {
            List<ChannelFuture> writes = new ArrayList<>();
            java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
            List<Integer> acceptedAtSend = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                ChannelFuture write = h.channel.write(payload(i));
                write.addListener(ignored -> {
                    accepted.incrementAndGet();
                    writes.add(h.channel.writeAndFlush(payload(42)));
                });
            }
            h.channel.flush();
            h.session.onSend = () -> acceptedAtSend.add(accepted.get());
            List.copyOf(h.session.callbacks).forEach(callback -> callback.accept(null));
            h.run();
            assertEquals(20, writes.size());
            assertEquals(java.util.Collections.nCopies(20, 20), acceptedAtSend);
            assertTrue(writes.stream().noneMatch(ChannelFuture::isDone));
            h.session.callbacks.subList(20, 40).forEach(callback -> callback.accept(null));
            h.run();
            assertTrue(writes.stream().allMatch(ChannelFuture::isSuccess));
        }
    }

    @Test
    void bufferDrainCanArriveBeforeAcceptance() {
        try (Harness h = new Harness()) {
            ChannelFuture first = h.channel.write(Unpooled.buffer(2 * 1024 * 1024).writeZero(2 * 1024 * 1024));
            ChannelFuture second = h.channel.writeAndFlush(payload(1));
            h.channel.onEngineBytesSent(2 * 1024 * 1024);
            h.run();
            assertEquals(2, h.session.callbacks.size());
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            h.session.complete(0, null);
            h.session.complete(1, null);
            h.run();
            assertTrue(first.isSuccess());
            assertTrue(second.isSuccess());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resumingWritesDoesNotFlushNewUnflushedMessages(boolean atWatermark) {
        try (Harness h = new Harness()) {
            h.channel.writeAndFlush(atWatermark
                    ? Unpooled.buffer(2 * 1024 * 1024).writeZero(2 * 1024 * 1024) : payload(1));
            ChannelFuture flushed = h.channel.writeAndFlush(payload(2));
            ChannelFuture unflushed = h.channel.write(payload(3));
            h.session.complete(0, null);
            if (atWatermark) {
                h.channel.onEngineBytesSent(2 * 1024 * 1024);
            }
            h.run();
            assertEquals(2, h.session.callbacks.size());
            h.session.complete(1, null);
            h.run();
            assertTrue(flushed.isSuccess());
            assertFalse(unflushed.isDone());
            h.channel.flush();
            assertEquals(3, h.session.callbacks.size());
            h.session.complete(2, null);
            h.run();
            assertTrue(unflushed.isSuccess());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failureClosesAndReleasesFlushedAndUnflushedWrites(boolean firstAccepted) {
        try (Harness h = new Harness()) {
            ByteBuf first = payload(1);
            ByteBuf second = payload(2);
            ByteBuf unflushed = payload(3);
            ChannelFuture a = h.channel.write(first);
            ChannelFuture b = h.channel.writeAndFlush(second);
            ChannelFuture c = h.channel.write(unflushed);
            IOException failure = new IOException("native queue rejected the frame");
            if (firstAccepted) {
                h.session.complete(0, null);
            }
            h.session.complete(1, failure);
            h.run();
            assertEquals(firstAccepted, a.isSuccess());
            assertTrue(a.isDone());
            assertSame(failure, b.cause());
            assertTrue(c.isDone());
            assertFalse(c.isSuccess());
            assertEquals(0, first.refCnt());
            assertEquals(0, second.refCnt());
            assertEquals(0, unflushed.refCnt());
            assertFalse(h.channel.isOpen());
            assertEquals(1, h.session.closes);
            h.channel.onEngineBytesSent(4);
            h.session.complete(0, null);
            h.session.complete(1, null);
            h.run();
            assertFalse(b.isSuccess());
            assertFalse(h.channel.isOpen());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sendFailureClosesEvenWithAutoCloseDisabled(boolean synchronous) {
        try (Harness h = new Harness()) {
            h.channel.config().setAutoClose(false);
            if (synchronous) {
                h.session.failure = new IllegalStateException("preparation failed");
            }
            ByteBuf buffer = payload(1);
            ChannelFuture write = h.channel.writeAndFlush(buffer);
            if (!synchronous) {
                h.session.complete(0, new IOException("native send rejected"));
            }
            h.run();
            assertTrue(write.isDone());
            assertFalse(write.isSuccess());
            assertFalse(h.channel.isOpen());
            assertTrue(h.channel.closeFuture().isDone());
            assertEquals(0, buffer.refCnt());
        }
    }

    @Test
    void closeFailsPendingSendsAndIgnoresLateCallbacks() {
        try (Harness h = new Harness()) {
            ByteBuf buffer = payload(1);
            ChannelFuture write = h.channel.writeAndFlush(buffer);
            h.channel.close().syncUninterruptibly();
            assertTrue(write.isDone());
            assertFalse(write.isSuccess());
            assertEquals(0, buffer.refCnt());
            h.session.complete(0, null);
            h.session.complete(0, new IOException("late failure"));
            h.run();
            assertFalse(write.isSuccess());
            assertEquals(1, h.session.closes);
        }
    }

    @Test
    void cancellationBetweenPendingWritesDoesNotStrandTheQueue() {
        try (Harness h = new Harness()) {
            ChannelFuture first = h.channel.writeAndFlush(payload(1));
            ByteBuf cancelled = payload(2);
            ChannelFuture cancellation = h.channel.write(cancelled);
            assertTrue(cancellation.cancel(false));
            ByteBuf third = payload(3);
            ChannelFuture last = h.channel.writeAndFlush(third);
            h.run();
            assertEquals(2, h.session.callbacks.size());
            assertEquals(0, cancelled.refCnt());
            h.session.complete(0, null);
            h.session.complete(1, null);
            h.run();
            assertTrue(first.isSuccess());
            assertTrue(last.isSuccess());
            assertEquals(0, third.refCnt());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fragmentedMessageWaitsForEveryNativeResult(boolean rejected) {
        try (Harness h = new Harness()) {
            h.channel.setMaxOutboundMessageSize(4);
            h.channel.pipeline().addLast(new NetherNetFramingCodec());
            ByteBuf message = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5, 6});
            ChannelFuture write = h.channel.writeAndFlush(message);
            assertEquals(2, h.session.callbacks.size());
            assertArrayEquals(new byte[]{1, 1, 2, 3}, h.session.messages.get(0));
            assertArrayEquals(new byte[]{0, 4, 5, 6}, h.session.messages.get(1));
            h.session.complete(0, null);
            h.run();
            assertFalse(write.isDone());
            h.session.complete(1, rejected ? new IOException("second fragment rejected") : null);
            h.run();
            assertTrue(write.isDone());
            assertEquals(!rejected, write.isSuccess());
            assertEquals(0, message.refCnt());
        }
    }

    @Test
    void heapAndDirectWindowsArePassedWithoutAnotherDirectCopy() {
        try (Harness h = new Harness()) {
            for (boolean direct : new boolean[]{false, true}) {
                ByteBuf buffer = direct ? Unpooled.directBuffer(4) : Unpooled.buffer(4);
                buffer.writeBytes(new byte[]{9, 0, 7, 9});
                buffer.setIndex(1, 3);
                int index = h.session.callbacks.size();
                ChannelFuture write = h.channel.writeAndFlush(buffer);
                h.run();
                assertArrayEquals(new byte[]{0, 7}, h.session.messages.get(index));
                assertEquals(direct, h.session.direct.get(index));
                assertEquals(1, buffer.readerIndex());
                assertEquals(3, buffer.writerIndex());
                h.session.complete(index, null);
                h.run();
                assertTrue(write.isSuccess());
                assertEquals(0, buffer.refCnt());
            }
        }
    }

    @Test
    void completionsRunOnTheEventLoop() throws Exception {
        DefaultEventLoop loop = new DefaultEventLoop();
        ManualSession session = new ManualSession();
        ActiveChild channel = new ActiveChild(session);
        try {
            loop.register(channel).sync();
            ChannelFuture write = channel.writeAndFlush(payload(1));
            loop.submit(() -> { }).sync();
            java.util.concurrent.CompletableFuture<Boolean> listenerThread = new java.util.concurrent.CompletableFuture<>();
            write.addListener(ignored -> listenerThread.complete(loop.inEventLoop()));
            Thread engine = new Thread(() -> session.complete(0, null), "native-send-callback");
            engine.start();
            engine.join(2000);
            assertTrue(listenerThread.get(3, TimeUnit.SECONDS));
            assertTrue(write.isSuccess());
        } finally {
            channel.close().syncUninterruptibly();
            loop.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    void loopShutdownFailsPendingWritesBeforeLateCallbacks() throws Exception {
        DefaultEventLoop loop = new DefaultEventLoop();
        ManualSession session = new ManualSession();
        ActiveChild channel = new ActiveChild(session);
        ByteBuf buffer = payload(1);
        try {
            loop.register(channel).sync();
            ChannelFuture write = channel.writeAndFlush(buffer);
            loop.submit(() -> { }).sync();
            loop.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
            assertTrue(write.isDone());
            Thread engine = new Thread(() -> session.complete(0, null), "native-send-callback");
            engine.start();
            engine.join(2000);
            assertTrue(channel.closeFuture().await(3, TimeUnit.SECONDS));
            assertTrue(write.await(3, TimeUnit.SECONDS));
            assertFalse(write.isSuccess());
            assertEquals(0, buffer.refCnt());
            assertNotEquals("native-send-callback", session.closeThread);
        } finally {
            if (!loop.isTerminated()) {
                channel.close().syncUninterruptibly();
                loop.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            }
        }
    }

    @Test
    void aTemporarilyRejectedCompletionTaskIsRetried() throws Exception {
        DefaultEventLoop loop = new DefaultEventLoop() {
            @Override public void execute(Runnable task) {
                if (Thread.currentThread().getName().equals("native-send-callback")) {
                    throw new java.util.concurrent.RejectedExecutionException("task queue is full");
                }
                super.execute(task);
            }
        };
        ManualSession session = new ManualSession();
        ActiveChild channel = new ActiveChild(session);
        ByteBuf buffer = payload(1);
        try {
            loop.register(channel).sync();
            ChannelFuture write = channel.writeAndFlush(buffer);
            loop.submit(() -> { }).sync();
            Thread engine = new Thread(() -> session.complete(0, null), "native-send-callback");
            engine.start();
            engine.join(2000);
            assertTrue(write.await(3, TimeUnit.SECONDS));
            assertTrue(write.isSuccess());
            assertTrue(channel.isOpen());
            assertEquals(0, buffer.refCnt());
        } finally {
            channel.close().syncUninterruptibly();
            loop.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private static ByteBuf payload(int value) {
        return Unpooled.buffer(2).writeByte(0).writeByte(value);
    }

    private static final class ManualSession implements WebRtcSession {
        final List<Consumer<Throwable>> callbacks = new ArrayList<>();
        final List<byte[]> messages = new ArrayList<>();
        final List<Boolean> direct = new ArrayList<>();
        RuntimeException failure;
        Runnable onSend = () -> { };
        volatile int closes;
        volatile String closeThread;

        @Override
        public void send(ByteBuffer data, Consumer<Throwable> completion) {
            if (failure != null) {
                throw failure;
            }
            onSend.run();
            byte[] bytes = new byte[data.remaining()];
            data.duplicate().get(bytes);
            messages.add(bytes);
            direct.add(data.isDirect());
            callbacks.add(completion);
        }

        void complete(int index, Throwable cause) {
            callbacks.get(index).accept(cause);
        }

        @Override public void addRemoteCandidate(String candidateSdp) { }
        @Override public void close() { closeThread = Thread.currentThread().getName(); closes++; }
    }

    private static final class ActiveChild extends NetherNetChildChannel {
        ActiveChild(WebRtcSession session) {
            super(null, new InetSocketAddress("127.0.0.1", 19132), new InetSocketAddress("127.0.0.1", 19133));
            attachSession(session);
            markTransportOpen();
        }
    }

    private static final class Harness implements AutoCloseable {
        final EmbeddedChannel clock = new EmbeddedChannel();
        final ManualSession session = new ManualSession();
        final ActiveChild channel = new ActiveChild(session);

        Harness() {
            clock.freezeTime();
            clock.eventLoop().register(channel).syncUninterruptibly();
            run();
        }

        void run() { clock.runPendingTasks(); }

        @Override public void close() {
            if (channel.isOpen()) {
                channel.close().syncUninterruptibly();
            }
            run();
            clock.finishAndReleaseAll();
        }
    }
}
