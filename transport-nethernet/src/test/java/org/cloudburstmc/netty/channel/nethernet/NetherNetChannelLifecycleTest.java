package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcSession;
import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherChannelConfig;
import org.cloudburstmc.netty.channel.nethernet.codec.NetherNetFramingCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class NetherNetChannelLifecycleTest {

    private final MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

    @AfterEach
    void tearDown() {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void transportSendFailureFailsTheWriteAndReleasesItsBuffer() throws Exception {
        TestChannel channel = new TestChannel();
        IllegalStateException failure = new IllegalStateException("transport rejected send");
        channel.sendFailure = failure;
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        try {
            group.register(channel).sync();
            ChannelFuture write = channel.writeAndFlush(payload).await();

            assertFalse(write.isSuccess());
            assertSame(failure, write.cause().getCause());
            assertEquals(0, payload.refCnt());
        } finally {
            channel.close().syncUninterruptibly();
        }
    }

    @Test
    void unsupportedWritesFailInsteadOfBeingSilentlyDropped() throws Exception {
        TestChannel channel = new TestChannel();
        try {
            group.register(channel).sync();
            ChannelFuture write = channel.writeAndFlush("not a byte buffer").await();

            assertFalse(write.isSuccess());
            assertInstanceOf(UnsupportedOperationException.class, write.cause());
            assertEquals(0, channel.sends.get());
            assertTrue(channel.isOpen());
        } finally {
            channel.close().syncUninterruptibly();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void inboundCallbackQueuedBehindCloseIsReleasedWithoutDelivery(boolean reliable) throws Exception {
        TestChannel channel = new TestChannel();
        UnpooledByteBufAllocator allocator = new UnpooledByteBufAllocator(false);
        channel.config().setAllocator(allocator);
        AtomicInteger reads = new AtomicInteger();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                reads.incrementAndGet();
                ReferenceCountUtil.release(msg);
            }
        });
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try {
            group.register(channel).sync();
            channel.eventLoop().execute(() -> {
                blocked.countDown();
                try {
                    resume.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            ChannelFuture close = channel.close();
            channel.deliverInbound(ByteBuffer.wrap(new byte[]{0, 1}), reliable);
            assertTrue(allocator.metric().usedHeapMemory() > 0);
            resume.countDown();
            close.sync();
            channel.eventLoop().submit(() -> {}).sync();

            assertEquals(0, reads.get());
            assertEquals(0, allocator.metric().usedHeapMemory());
        } finally {
            resume.countDown();
            channel.close().syncUninterruptibly();
        }
    }

    @Test
    void childCanCloseBeforeRegistrationAndRejectsLateSessionAttachment() throws Exception {
        TestChannel parent = new TestChannel();
        try {
            group.register(parent).sync();
            NetherNetChildChannel child = new NetherNetChildChannel(parent, null, null);
            AtomicInteger closeNotifications = new AtomicInteger();
            child.closeFuture().addListener(future -> closeNotifications.incrementAndGet());

            child.close().sync();
            TestSession lateSession = new TestSession();
            child.attachSession(lateSession);

            assertFalse(child.isRegistered());
            assertFalse(child.isOpen());
            assertTrue(child.closeFuture().isSuccess());
            assertEquals(1, closeNotifications.get());
            assertEquals(1, lateSession.closes.get());
        } finally {
            parent.close().syncUninterruptibly();
        }
    }

    @Test
    void unregisteredChildCloseClosesTheAttachedSessionOnce() throws Exception {
        TestChannel parent = new TestChannel();
        try {
            group.register(parent).sync();
            NetherNetChildChannel child = new NetherNetChildChannel(parent, null, null);
            TestSession session = new TestSession();
            child.attachSession(session);

            child.close().sync();
            child.close().sync();

            assertEquals(1, session.closes.get());
            assertTrue(child.closeFuture().isSuccess());
        } finally {
            parent.close().syncUninterruptibly();
        }
    }

    @Test
    void inboundQueuedBeforeRegistrationDoesNotOutliveCloseDuringActivation() throws Exception {
        TestChannel parent = new TestChannel();
        MultiThreadIoEventLoopGroup workers = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        NetherNetChildChannel child = new NetherNetChildChannel(parent, null, null);
        UnpooledByteBufAllocator allocator = new UnpooledByteBufAllocator(false);
        child.config().setAllocator(allocator);
        AtomicInteger reads = new AtomicInteger();
        child.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                ctx.close();
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object message) {
                reads.incrementAndGet();
                ReferenceCountUtil.release(message);
            }
        });
        CountDownLatch parentBlocked = new CountDownLatch(1);
        CountDownLatch parentResume = new CountDownLatch(1);
        CountDownLatch workerBlocked = new CountDownLatch(1);
        CountDownLatch workerResume = new CountDownLatch(1);
        try {
            group.register(parent).sync();
            parent.eventLoop().execute(() -> awaitRelease(parentBlocked, parentResume));
            assertTrue(parentBlocked.await(2, TimeUnit.SECONDS));
            child.deliverInbound(ByteBuffer.wrap(new byte[]{0, 1}));

            workers.register(child).sync();
            child.eventLoop().execute(() -> awaitRelease(workerBlocked, workerResume));
            assertTrue(workerBlocked.await(2, TimeUnit.SECONDS));
            child.markTransportOpen();
            parentResume.countDown();
            parent.eventLoop().submit(() -> { }).sync();
            workerResume.countDown();
            child.closeFuture().sync();
            child.eventLoop().submit(() -> { }).sync();

            assertEquals(0, reads.get());
            assertEquals(0, allocator.metric().usedHeapMemory());
        } finally {
            parentResume.countDown();
            workerResume.countDown();
            child.close().syncUninterruptibly();
            parent.close().syncUninterruptibly();
            workers.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inboundWaitsForRegistrationAndActivationInArrivalOrder(boolean transportOpensFirst) throws Exception {
        TestChannel parent = new TestChannel();
        NetherNetChildChannel child = new NetherNetChildChannel(parent, null, null);
        List<String> events = new ArrayList<>();
        child.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRegistered(ChannelHandlerContext ctx) {
                events.add("registered");
                ctx.fireChannelRegistered();
            }

            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                events.add("active");
                ctx.fireChannelActive();
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object message) {
                ByteBuf buffer = (ByteBuf) message;
                try {
                    events.add("read " + buffer.readUnsignedByte());
                } finally {
                    buffer.release();
                }
            }
        });
        try {
            group.register(parent).sync();
            if (transportOpensFirst) {
                child.markTransportOpen();
            }
            child.deliverInbound(ByteBuffer.wrap(new byte[]{1}));
            parent.eventLoop().submit(() -> { }).sync();
            assertTrue(events.isEmpty(), "An unregistered child's pipeline is not ready to receive data");

            group.register(child).sync();
            child.eventLoop().submit(() -> { }).sync();
            if (!transportOpensFirst) {
                assertEquals(List.of("registered"), events);
                child.markTransportOpen();
            }
            child.deliverInbound(ByteBuffer.wrap(new byte[]{2}));
            child.eventLoop().submit(() -> { }).sync();

            assertEquals(List.of("registered", "active", "read 1", "read 2"), events);
        } finally {
            child.close().syncUninterruptibly();
            parent.close().syncUninterruptibly();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pendingInboundIsBoundedAndReleasedOnClose(boolean exceedByteLimit) throws Exception {
        TestChannel parent = new TestChannel();
        NetherNetChildChannel child = new NetherNetChildChannel(parent, null, null);
        UnpooledByteBufAllocator allocator = new UnpooledByteBufAllocator(false);
        child.config().setAllocator(allocator);
        try {
            group.register(parent).sync();
            if (exceedByteLimit) {
                child.deliverInbound(ByteBuffer.allocate(32 * 1024 * 1024 + 512));
            } else {
                for (int i = 0; i < 512; i++) {
                    child.deliverInbound(ByteBuffer.wrap(new byte[]{1}));
                }
            }
            parent.eventLoop().submit(() -> { }).sync();
            assertTrue(child.isOpen());
            assertTrue(allocator.metric().usedHeapMemory() > 0);

            child.deliverInbound(ByteBuffer.wrap(new byte[]{2}));
            child.closeFuture().sync();

            assertFalse(child.isOpen());
            assertEquals(0, allocator.metric().usedHeapMemory());
        } finally {
            child.close().syncUninterruptibly();
            parent.close().syncUninterruptibly();
        }
    }

    @Test
    void remoteAddressTracksIceChangesAfterAnEarlyLookup() {
        TestChannel channel = new TestChannel();
        try {
            assertEquals(new InetSocketAddress("127.0.0.1", 19132), channel.remoteAddress());
            InetSocketAddress nominated = new InetSocketAddress("192.0.2.8", 32000);
            channel.remoteAddress = nominated;
            assertSame(nominated, channel.remoteAddress());
            InetSocketAddress replacement = new InetSocketAddress("192.0.2.9", 32001);
            channel.remoteAddress = replacement;
            assertSame(replacement, channel.remoteAddress());
        } finally {
            channel.unsafe().closeForcibly();
        }
    }

    @Test
    void manualReadFinishesOneMessageAndLeavesTheNextQueued() throws Exception {
        TestChannel channel = new TestChannel();
        channel.config().setAutoRead(false);
        LinkedBlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();
        channel.pipeline().addLast(new NetherNetFramingCodec(), new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object message) {
                ByteBuf buffer = (ByteBuf) message;
                try {
                    messages.add(ByteBufUtil.getBytes(buffer));
                } finally {
                    buffer.release();
                }
            }
        });
        try {
            group.register(channel).sync();
            channel.deliverInbound(ByteBuffer.wrap(new byte[]{1, 11}));
            channel.deliverInbound(ByteBuffer.wrap(new byte[]{0, 12}));
            channel.deliverInbound(ByteBuffer.wrap(new byte[]{0, 13}));
            channel.eventLoop().submit(() -> { }).sync();
            assertTrue(messages.isEmpty());

            channel.read();
            assertArrayEquals(new byte[]{11, 12}, messages.poll(2, TimeUnit.SECONDS));
            assertNull(messages.poll(100, TimeUnit.MILLISECONDS));

            channel.read();
            assertArrayEquals(new byte[]{13}, messages.poll(2, TimeUnit.SECONDS));
            assertTrue(messages.isEmpty());
        } finally {
            channel.close().syncUninterruptibly();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disablingAutoReadClearsAutomaticDemand(boolean queuedToggle) throws Exception {
        TestChannel channel = new TestChannel();
        LinkedBlockingQueue<Integer> messages = new LinkedBlockingQueue<>();
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object message) {
                ByteBuf buffer = (ByteBuf) message;
                try {
                    messages.add((int) buffer.readUnsignedByte());
                } finally {
                    buffer.release();
                }
            }
        });
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try {
            group.register(channel).sync();
            channel.eventLoop().submit(() -> { }).sync();
            if (queuedToggle) {
                channel.config().setAutoRead(false);
                channel.eventLoop().execute(() -> awaitRelease(blocked, resume));
                assertTrue(blocked.await(2, TimeUnit.SECONDS));
                channel.config().setAutoRead(true);
                channel.config().setAutoRead(false);
            } else {
                channel.eventLoop().submit(() -> channel.config().setAutoRead(false)).sync();
            }
            channel.deliverInbound(ByteBuffer.wrap(new byte[]{21}));
            channel.deliverInbound(ByteBuffer.wrap(new byte[]{22}));
            resume.countDown();
            channel.eventLoop().submit(() -> { }).sync();
            channel.eventLoop().submit(() -> { }).sync();
            assertTrue(messages.isEmpty());

            channel.read();
            assertEquals(21, messages.poll(2, TimeUnit.SECONDS));
            assertNull(messages.poll(100, TimeUnit.MILLISECONDS));
            channel.read();
            assertEquals(22, messages.poll(2, TimeUnit.SECONDS));
        } finally {
            resume.countDown();
            channel.close().syncUninterruptibly();
        }
    }

    @Test
    void enablingAutoReadDrainsQueuedFramesInFairBatches() throws Exception {
        TestChannel channel = new TestChannel();
        channel.config().setAutoRead(false);
        List<Integer> messages = new ArrayList<>();
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger countAtMarker = new AtomicInteger();
        CountDownLatch received = new CountDownLatch(192);
        channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object message) {
                ByteBuf buffer = (ByteBuf) message;
                try {
                    messages.add(buffer.readInt());
                } finally {
                    buffer.release();
                    received.countDown();
                }
            }

            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) {
                completions.incrementAndGet();
            }
        });
        try {
            group.register(channel).sync();
            for (int i = 0; i < 192; i++) {
                ByteBuffer frame = ByteBuffer.allocate(4).putInt(i).flip();
                channel.deliverInbound(frame);
            }
            channel.eventLoop().submit(() -> {
                channel.config().setAutoRead(true);
                channel.eventLoop().execute(() -> countAtMarker.set(messages.size()));
            }).sync();
            assertTrue(received.await(2, TimeUnit.SECONDS));
            channel.eventLoop().submit(() -> { }).sync();

            assertEquals(java.util.stream.IntStream.range(0, 192).boxed().toList(), messages);
            assertTrue(countAtMarker.get() > 0 && countAtMarker.get() < 192);
            assertTrue(completions.get() > 1 && completions.get() < 192);
        } finally {
            channel.close().syncUninterruptibly();
        }
    }

    @Test
    void activePausedReaderCannotAccumulateAnUnboundedBacklog() throws Exception {
        TestChannel channel = new TestChannel();
        UnpooledByteBufAllocator allocator = new UnpooledByteBufAllocator(false);
        channel.config().setAllocator(allocator).setAutoRead(false);
        try {
            group.register(channel).sync();
            for (int i = 0; i < 513; i++) {
                channel.deliverInbound(ByteBuffer.wrap(new byte[]{1}));
            }
            assertTrue(channel.closeFuture().await(2, TimeUnit.SECONDS));
            assertFalse(channel.isOpen());
            assertEquals(0, allocator.metric().usedHeapMemory());
        } finally {
            channel.close().syncUninterruptibly();
        }
    }

    @Test
    void pausedReaderCanQueueTwoMaximumReassembledMessages() throws Exception {
        TestChannel channel = new TestChannel();
        UnpooledByteBufAllocator allocator = new UnpooledByteBufAllocator(false);
        channel.config().setAllocator(allocator).setAutoRead(false);
        LinkedBlockingQueue<ByteBuf> messages = new LinkedBlockingQueue<>();
        channel.pipeline().addLast(new NetherNetFramingCodec(), new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object message) {
                messages.add((ByteBuf) message);
            }
        });
        try {
            group.register(channel).sync();
            for (int message = 0; message < 2; message++) {
                for (int countdown = 255; countdown >= 0; countdown--) {
                    ByteBuffer frame = ByteBuffer.allocate(65537);
                    frame.put((byte) countdown).position(0);
                    channel.deliverInbound(frame);
                }
            }
            assertTrue(channel.isOpen());
            assertTrue(messages.isEmpty());
            for (int message = 0; message < 2; message++) {
                channel.read();
                ByteBuf received = messages.poll(3, TimeUnit.SECONDS);
                assertTrue(received != null);
                try {
                    assertEquals(16 * 1024 * 1024, received.readableBytes());
                } finally {
                    received.release();
                }
            }
            channel.eventLoop().submit(() -> { }).sync();
            assertEquals(0, allocator.metric().usedHeapMemory());
        } finally {
            channel.close().syncUninterruptibly();
            messages.forEach(ByteBuf::release);
        }
    }

    private static void awaitRelease(CountDownLatch blocked, CountDownLatch release) {
        blocked.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void bothDataChannelsShareTheInboundQueueBudget() throws Exception {
        TestChannel channel = new TestChannel();
        UnpooledByteBufAllocator allocator = new UnpooledByteBufAllocator(false);
        channel.config().setAllocator(allocator).setAutoRead(false);
        try {
            group.register(channel).sync();
            for (int frame = 0; frame < 512; frame++) {
                channel.deliverInbound(ByteBuffer.wrap(new byte[]{0, 1}), frame % 2 == 0);
            }
            assertTrue(channel.isOpen());
            assertTrue(allocator.metric().usedHeapMemory() > 0);

            channel.deliverInbound(ByteBuffer.wrap(new byte[]{0, 2}), false);
            assertTrue(channel.closeFuture().await(2, TimeUnit.SECONDS));
            assertEquals(0, allocator.metric().usedHeapMemory());
            channel.deliverInbound(ByteBuffer.wrap(new byte[]{0, 3}), false);
            assertEquals(0, allocator.metric().usedHeapMemory());
        } finally {
            channel.close().syncUninterruptibly();
        }
    }

    private static final class TestSession implements WebRtcSession {
        private final AtomicInteger closes = new AtomicInteger();

        @Override public void send(ByteBuffer data) { }
        @Override public void addRemoteCandidate(String candidateSdp) { }
        @Override public void close() { closes.incrementAndGet(); }
    }

    private static final class TestChannel extends NetherNetChannel {
        private final AtomicInteger sends = new AtomicInteger();
        private RuntimeException sendFailure;

        private TestChannel() {
            super(null, new InetSocketAddress("127.0.0.1", 19132), new InetSocketAddress("127.0.0.1", 19133));
            config = new DefaultNetherChannelConfig(this);
            markTransportOpen();
        }

        @Override
        protected void sendFramed(ByteBuf framed) {
            sends.incrementAndGet();
            if (sendFailure != null) {
                throw sendFailure;
            }
        }

        @Override
        protected AbstractUnsafe newUnsafe() {
            return new AbstractUnsafe() {
                @Override
                public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
                    promise.setFailure(new UnsupportedOperationException());
                }
            };
        }
    }
}
