package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcSession;
import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherChannelConfig;
import io.netty.buffer.ByteBuf;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void inboundCallbackQueuedBehindCloseIsReleasedWithoutDelivery() throws Exception {
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
            channel.deliverInbound(ByteBuffer.wrap(new byte[]{0, 1}));
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
                child.deliverInbound(ByteBuffer.allocate(8 * 1024 * 1024));
            } else {
                for (int i = 0; i < 256; i++) {
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

    private static void awaitRelease(CountDownLatch blocked, CountDownLatch release) {
        blocked.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
