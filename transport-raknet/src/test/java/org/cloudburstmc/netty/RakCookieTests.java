/*
 * Copyright 2025 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakClientChannel;
import org.cloudburstmc.netty.channel.raknet.RakConstants;
import org.cloudburstmc.netty.channel.raknet.RakServerChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.config.RakServerCookieMode;
import org.cloudburstmc.netty.util.RakUtils;
import org.cloudburstmc.netty.util.SipHash;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.*;

public class RakCookieTests {

    private static final int PORT = 19134;
    private static final byte[] SECRET = new byte[]{
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
    };
    private static final int PROTOCOL_VERSION = 11;

    private EventLoopGroup group;
    private Channel serverChannel;
    private BlockingQueue<Channel> acceptedChannels;

    @BeforeEach
    public void setup() {
        group = new NioEventLoopGroup();
        acceptedChannels = new LinkedBlockingQueue<>();
    }

    @AfterEach
    public void teardown() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        group.shutdownGracefully().awaitUninterruptibly();
    }

    private void setupServer(RakServerCookieMode mode, byte[] secret) {
        ServerBootstrap b = new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(group)
                .option(RakChannelOption.RAK_SERVER_COOKIE_MODE, mode)
                .handler(new ChannelInitializer<RakServerChannel>() {
                    @Override
                    protected void initChannel(RakServerChannel ch) {
                    }
                })
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        acceptedChannels.add(ch);
                    }
                });

        if (secret != null) {
            b.option(RakChannelOption.RAK_SERVER_COOKIE_SECRET, secret);
        }

        this.serverChannel = b.bind(new InetSocketAddress(PORT)).awaitUninterruptibly().channel();
    }

    private Bootstrap clientBootstrap() {
        return new Bootstrap()
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .group(group)
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, PROTOCOL_VERSION)
                .handler(new ChannelInitializer<RakClientChannel>() {
                    @Override
                    protected void initChannel(RakClientChannel ch) {
                    }
                });
    }

    @Test
    public void testActiveMode() {
        // ACTIVE mode: Server generates cookie, client must echo it.
        // Standard client behavior.
        // Cookie requirement: Valid timestamp AND Valid signature.
        setupServer(RakServerCookieMode.ACTIVE, SECRET);

        Channel client = clientBootstrap()
                .connect(new InetSocketAddress("127.0.0.1", PORT))
                .awaitUninterruptibly()
                .channel();

        Assertions.assertTrue(client.isActive(), "Client should connect in ACTIVE mode");
        client.close().awaitUninterruptibly();
    }

    @Test
    public void testInvalidMode() {
        // INVALID mode: Server sends no cookie request, expects no cookie.
        // Cookie requirement: completely absent.
        setupServer(RakServerCookieMode.INVALID, null);

        Channel client = clientBootstrap()
                .connect(new InetSocketAddress("127.0.0.1", PORT))
                .awaitUninterruptibly()
                .channel();

        Assertions.assertTrue(client.isActive(), "Client should connect in INVALID (no cookie) mode");
        client.close().awaitUninterruptibly();
    }

    @Test
    public void testOffloadedMode() throws InterruptedException {
        // OFFLOADED mode: Server ignores OCR1.
        // We must simulate an offloaded handshake by sending OCR2 directly.
        // Cookie requirement: Valid timestamp, signature ignored.
        setupServer(RakServerCookieMode.OFFLOADED, SECRET);

        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", PORT);
        BlockingQueue<DatagramPacket> responses = new LinkedBlockingQueue<>();

        // Raw UDP socket to send OCR2
        Channel rawClient = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        if (msg instanceof DatagramPacket) {
                            responses.add(((DatagramPacket) msg).retain());
                        }
                    }
                })
                .bind(0).awaitUninterruptibly().channel();

        // Generate cookie with Valid Timestamp but Garbage Signature
        SipHash sipHash = new SipHash(SECRET);
        int validCookie = sipHash.generateStatelessCookie(serverAddress, PROTOCOL_VERSION); // Gives valid signature
        // Corrupt the signature (top 24 bits), keep combined byte (bottom 8 bits: time + proto)
        int forgedCookie = (validCookie & 0xFF) | 0xABCDEF00; 

        ByteBuf ocr2 = createOCR2(rawClient.localAddress(), serverAddress, forgedCookie, true);
        rawClient.writeAndFlush(new DatagramPacket(ocr2, serverAddress));

        DatagramPacket response = responses.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(response, "Server should respond to OCR2 in OFFLOADED mode with valid timestamp");
        
        ByteBuf content = response.content();
        Assertions.assertEquals(ID_OPEN_CONNECTION_REPLY_2, content.getUnsignedByte(0));
        response.release();
        Channel accepted = acceptedChannels.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(accepted, "Server should create a child channel in OFFLOADED mode");
        Assertions.assertEquals(
                PROTOCOL_VERSION,
                accepted.config().getOption(RakChannelOption.RAK_PROTOCOL_VERSION),
                "OFFLOADED mode should recover RakNet protocol version from the cookie");
        rawClient.close();
    }

    @Test
    public void testOffloadedPskMode_Success() throws InterruptedException {
        // OFFLOADED_PSK mode: Server ignores OCR1.
        // Cookie requirement: Valid timestamp AND Valid signature.
        setupServer(RakServerCookieMode.OFFLOADED_PSK, SECRET);

        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", PORT);
        BlockingQueue<DatagramPacket> responses = new LinkedBlockingQueue<>();

        // We must explicitly bind to 127.0.0.1 to ensure the same IP is used in the cookie generation.
        Channel rawClient = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        if (msg instanceof DatagramPacket) {
                            responses.add(((DatagramPacket) msg).retain());
                        }
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 0)).awaitUninterruptibly().channel();

        // Valid Cookie
        SipHash sipHash = new SipHash(SECRET);
        // Note: The server verifies the signature based on the SENDER address (rawClient.localAddress())
        int validCookie = sipHash.generateStatelessCookie((InetSocketAddress) rawClient.localAddress(), PROTOCOL_VERSION);

        ByteBuf ocr2 = createOCR2(rawClient.localAddress(), serverAddress, validCookie, true);
        rawClient.writeAndFlush(new DatagramPacket(ocr2, serverAddress));

        DatagramPacket response = responses.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(response, "Server should respond to OCR2 with valid PSK cookie");
        Assertions.assertEquals(ID_OPEN_CONNECTION_REPLY_2, response.content().getUnsignedByte(0));
        response.release();
        Channel accepted = acceptedChannels.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(accepted, "Server should create a child channel in OFFLOADED_PSK mode");
        Assertions.assertEquals(
                PROTOCOL_VERSION,
                accepted.config().getOption(RakChannelOption.RAK_PROTOCOL_VERSION),
                "OFFLOADED_PSK mode should recover RakNet protocol version from the cookie");
        rawClient.close();
    }

    @Test
    public void testOffloadedPskMode_Failure() throws InterruptedException {
        // OFFLOADED_PSK mode: Invalid signature should be dropped.
        // Cookie requirement: Valid timestamp AND Valid signature.
        setupServer(RakServerCookieMode.OFFLOADED_PSK, SECRET);

        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", PORT);
        BlockingQueue<DatagramPacket> responses = new LinkedBlockingQueue<>();

        Channel rawClient = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        if (msg instanceof DatagramPacket) {
                            responses.add(((DatagramPacket) msg).retain());
                        }
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 0)).awaitUninterruptibly().channel();

        // Invalid Cookie (Garbage Signature)
        // Timestamp is 4 bits, Proto is 4 bits.
        int time = (int) ((System.currentTimeMillis() / 60000) & 0x0F);
        int proto = (PROTOCOL_VERSION - 1) & 0x0F;
        int combined = (time << 4) | proto;

        int invalidCookie = 0xABCDEF00 | combined; 

        ByteBuf ocr2 = createOCR2(rawClient.localAddress(), serverAddress, invalidCookie, true);
        rawClient.writeAndFlush(new DatagramPacket(ocr2, serverAddress));

        DatagramPacket response = responses.poll(500, TimeUnit.MILLISECONDS);
        Assertions.assertNull(response, "Server should NOT respond to OCR2 with invalid PSK signature");
        rawClient.close();
    }

    @Test
    public void testOffMode() throws InterruptedException {
        // OFF mode: Server expects a cookie structure but accepts anything.
        // Cookie requirement: Any 4 byte cookie is valid.
        setupServer(RakServerCookieMode.OFF, SECRET);

        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", PORT);
        BlockingQueue<DatagramPacket> responses = new LinkedBlockingQueue<>();

        Channel rawClient = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        if (msg instanceof DatagramPacket) {
                            responses.add(((DatagramPacket) msg).retain());
                        }
                    }
                })
                .bind(0).awaitUninterruptibly().channel();

        // Completely garbage cookie
        int garbageCookie = 0x12345678;

        ByteBuf ocr2 = createOCR2(rawClient.localAddress(), serverAddress, garbageCookie, true);
        rawClient.writeAndFlush(new DatagramPacket(ocr2, serverAddress));

        DatagramPacket response = responses.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(response, "Server should respond to OCR2 in OFF mode with garbage cookie");
        Assertions.assertEquals(ID_OPEN_CONNECTION_REPLY_2, response.content().getUnsignedByte(0));
        response.release();
        rawClient.close();
    }

    /**
     * Verifies that the keys actually rotate based on the epoch (every 10 minutes).
     */
    @Test
    public void testKeyRotation() {
        TestSipHash sipHash = new TestSipHash(SECRET);
        InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 12345);

        // Epoch 0: Time 0
        sipHash.setTime(0);
        int cookieEpoch0 = sipHash.generateStatelessCookie(sender, PROTOCOL_VERSION);

        // Epoch 1: Time 10 minutes (600 seconds)
        sipHash.setTime(TimeUnit.MINUTES.toMillis(10));
        int cookieEpoch1 = sipHash.generateStatelessCookie(sender, PROTOCOL_VERSION);
        
        // Ensure signatures are different for the same input address/timestamp-slot        
        int sig0 = (cookieEpoch0 >>> 8) & 0xFFFFFF;
        int sig1 = (cookieEpoch1 >>> 8) & 0xFFFFFF;
        
        Assertions.assertNotEquals(sig0, sig1, "Signatures must differ between epochs due to key rotation");
    }

    /**
     * Verifies that a cookie generated near the end of Epoch 0 is still valid
     * when received in Epoch 1, provided the 2-minute expiration window hasn't passed.
     */
    @Test
    public void testEpochCrossing() {
        TestSipHash sipHash = new TestSipHash(SECRET);
        InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 12345);

        // Time: 9 minutes 50 seconds (Epoch 0)
        long timeGen = TimeUnit.MINUTES.toMillis(9) + TimeUnit.SECONDS.toMillis(50);
        sipHash.setTime(timeGen);
        
        int cookie = sipHash.generateStatelessCookie(sender, PROTOCOL_VERSION);

        // Time: 10 minutes 10 seconds (Epoch 1)
        // This is 20 seconds later real-time, across the 10-minute Epoch boundary and within the 2-minute validity window.
        long timeVerify = TimeUnit.MINUTES.toMillis(10) + TimeUnit.SECONDS.toMillis(10);
        sipHash.setTime(timeVerify);
        
        boolean valid = sipHash.validateCookie(cookie, sender, RakServerCookieMode.ACTIVE);
        Assertions.assertTrue(valid, "Cookie from previous epoch (within valid window) should be accepted");
    }

    /**
     * Verifies that a cookie expires after the 2-minute window, even if within the same epoch.
     */
    @Test
    public void testCookieExpiry() {
        TestSipHash sipHash = new TestSipHash(SECRET);
        InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 12345);

        // Time: 5 minutes
        sipHash.setTime(TimeUnit.MINUTES.toMillis(5));
        int cookie = sipHash.generateStatelessCookie(sender, PROTOCOL_VERSION);

        // Time: 7 minutes + 1ms (Diff > 2 minutes)
        sipHash.setTime(TimeUnit.MINUTES.toMillis(7) + 1000); // 2 mins and 1 sec later
        
        boolean valid = sipHash.validateCookie(cookie, sender, RakServerCookieMode.ACTIVE);
        Assertions.assertFalse(valid, "Cookie should expire after 2 minutes");
    }

    /**
     * Helper subclass to mock time for testing rotation.
     */
    private static class TestSipHash extends SipHash {
        private long mockedTime;

        public TestSipHash(byte[] key) {
            super(key);
            this.mockedTime = System.currentTimeMillis();
        }

        public void setTime(long timeMillis) {
            this.mockedTime = timeMillis;
        }

        @Override
        protected long now() {
            return this.mockedTime;
        }
    }

    private ByteBuf createOCR2(java.net.SocketAddress clientAddr, InetSocketAddress serverAddr, int cookie, boolean hasCookie) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(ID_OPEN_CONNECTION_REQUEST_2);
        buf.writeBytes(RakConstants.DEFAULT_UNCONNECTED_MAGIC);
        if (hasCookie) {
            buf.writeInt(cookie);
            buf.writeBoolean(false); // Challenge
        }
        RakUtils.writeAddress(buf, serverAddr);
        buf.writeShort(RakConstants.MAXIMUM_MTU_SIZE);
        buf.writeLong(12345L); // Client GUID
        return buf;
    }
}
