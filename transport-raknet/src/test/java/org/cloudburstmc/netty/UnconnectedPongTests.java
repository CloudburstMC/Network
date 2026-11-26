/*
 * Copyright 2022 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
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
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.*;
import org.cloudburstmc.netty.handler.codec.raknet.common.UnconnectedPongDecoder;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;

public class UnconnectedPongTests {

    private NioEventLoopGroup group;
    private RakClientChannel client;
    private EmbeddedChannel decoder;

    @BeforeEach
    public void setup() {
        group = new NioEventLoopGroup(1);
        client = (RakClientChannel) new Bootstrap()
                .group(group)
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .handler(new ChannelInboundHandlerAdapter())
                .register().syncUninterruptibly().channel();
        decoder = new EmbeddedChannel(new UnconnectedPongDecoder(client));
    }

    @AfterEach
    public void teardown() {
        if (decoder != null) decoder.finishAndReleaseAll();
        if (client != null) client.close().syncUninterruptibly();
        if (group != null) group.shutdownGracefully().syncUninterruptibly();
    }

    private boolean decode(ByteBuf buffer) {
        return decoder.writeInbound(new DatagramPacket(buffer,
                new InetSocketAddress("127.0.0.1", 19132)));
    }

    private ByteBuf header() {
        return Unpooled.buffer()
                .writeByte(RakConstants.ID_UNCONNECTED_PONG)
                .writeLong(123L)
                .writeLong(456L)
                .writeBytes(RakConstants.DEFAULT_UNCONNECTED_MAGIC);
    }

    @Test
    public void testTruncatedHeader() {
        Assertions.assertFalse(decode(Unpooled.buffer()
                .writeByte(RakConstants.ID_UNCONNECTED_PONG)));
    }

    @Test
    public void testTruncatedPayload() {
        Assertions.assertFalse(decode(header().writeShort(3).writeByte(1)));
    }

    @Test
    public void testValidPong() {
        Assertions.assertTrue(decode(header().writeShort(3)
                .writeBytes(new byte[]{1, 2, 3})));
        RakPong pong = decoder.readInbound();
        try {
            Assertions.assertEquals(123L, pong.getPingTime());
            Assertions.assertEquals(456L, pong.getGuid());
            Assertions.assertEquals(3, pong.getPongData().readableBytes());
        } finally {
            pong.release();
        }
    }
}
