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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakConstants;
import org.cloudburstmc.netty.channel.raknet.RakServerChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.util.RakUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.*;

public class RakCookieServiceTests {

    private static final int PORT = 19135;
    private static final byte[] SECRET = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    private EventLoopGroup group;
    private Channel serverChannel;

    @BeforeEach
    public void setup() {
        group = new NioEventLoopGroup();
    }

    @AfterEach
    public void teardown() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        group.shutdownGracefully().awaitUninterruptibly();
    }

    private void setupCookieService() {
        this.serverChannel = new Bootstrap()
                .channelFactory(RakChannelFactory.cookieService(NioDatagramChannel.class))
                .group(group)
                .option(RakChannelOption.RAK_SERVER_COOKIE_SECRET, SECRET)
                .handler(new ChannelInitializer<RakServerChannel>() {
                    @Override
                    protected void initChannel(RakServerChannel ch) {}
                })
                .bind(new InetSocketAddress(PORT))
                .awaitUninterruptibly()
                .channel();
    }

    @Test
    public void testRespondsToOCR1() throws InterruptedException {
        setupCookieService();
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", PORT);
        BlockingQueue<DatagramPacket> responses = new LinkedBlockingQueue<>();

        Channel rawClient = createRawClient(responses);

        ByteBuf ocr1 = createOCR1(11, RakConstants.MAXIMUM_MTU_SIZE);
        rawClient.writeAndFlush(new DatagramPacket(ocr1, serverAddress));

        DatagramPacket response = responses.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(response, "Cookie Service should respond to OCR1");
        
        ByteBuf content = response.content();
        Assertions.assertEquals(ID_OPEN_CONNECTION_REPLY_1, content.getUnsignedByte(0));
        
        // Verify Cookie is present (Magic + GUID + Security(bool) + Cookie(int))
        // 1 (ID) + 16 (Magic) + 8 (GUID) + 1 (Security) = 26 bytes offset to cookie
        content.skipBytes(1 + 16 + 8);
        boolean security = content.readBoolean();
        Assertions.assertTrue(security, "Cookie Service should always have security enabled in reply");
        
        int cookie = content.readInt();
        Assertions.assertNotEquals(0, cookie, "Cookie should be non-zero");
        
        response.release();
        rawClient.close();
    }

    @Test
    public void testIgnoresOCR2() throws InterruptedException {
        setupCookieService();
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", PORT);
        BlockingQueue<DatagramPacket> responses = new LinkedBlockingQueue<>();

        Channel rawClient = createRawClient(responses);

        ByteBuf ocr2 = createOCR2(serverAddress);
        rawClient.writeAndFlush(new DatagramPacket(ocr2, serverAddress));

        DatagramPacket response = responses.poll(500, TimeUnit.MILLISECONDS);
        Assertions.assertNull(response, "Cookie Service should NOT respond to OCR2");
        
        rawClient.close();
    }

    @Test
    public void testIgnoresUnconnectedPing() throws InterruptedException {
        setupCookieService();
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", PORT);
        BlockingQueue<DatagramPacket> responses = new LinkedBlockingQueue<>();

        Channel rawClient = createRawClient(responses);

        ByteBuf ping = Unpooled.buffer();
        ping.writeByte(ID_UNCONNECTED_PING);
        ping.writeLong(System.currentTimeMillis());
        ping.writeBytes(RakConstants.DEFAULT_UNCONNECTED_MAGIC);
        ping.writeLong(0); // Client GUID

        rawClient.writeAndFlush(new DatagramPacket(ping, serverAddress));

        DatagramPacket response = responses.poll(500, TimeUnit.MILLISECONDS);
        Assertions.assertNull(response, "Cookie Service should not respond to Pings");
        
        rawClient.close();
    }

    private Channel createRawClient(BlockingQueue<DatagramPacket> queue) {
        return new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        if (msg instanceof DatagramPacket) {
                            queue.add(((DatagramPacket) msg).retain());
                        }
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .awaitUninterruptibly()
                .channel();
    }

    private ByteBuf createOCR1(int protocolVersion, int mtu) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(ID_OPEN_CONNECTION_REQUEST_1);
        buf.writeBytes(RakConstants.DEFAULT_UNCONNECTED_MAGIC);
        buf.writeByte(protocolVersion);
        // Pad to MTU size (minus header overhead)
        buf.writeZero(mtu - 46); 
        return buf;
    }

    private ByteBuf createOCR2(InetSocketAddress serverAddr) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(ID_OPEN_CONNECTION_REQUEST_2);
        buf.writeBytes(RakConstants.DEFAULT_UNCONNECTED_MAGIC);
        RakUtils.writeAddress(buf, serverAddr);
        buf.writeShort(RakConstants.MAXIMUM_MTU_SIZE);
        buf.writeLong(12345L); // Client GUID
        return buf;
    }
}