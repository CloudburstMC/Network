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
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakChildChannel;
import org.cloudburstmc.netty.channel.raknet.RakClientChannel;
import org.cloudburstmc.netty.channel.raknet.RakServerChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;

public class RakTests {

    private static final byte[] ADVERTISEMENT = new StringJoiner(";", "", ";")
            .add("MCPE")
            .add("RakNet unit test")
            .add(Integer.toString(542))
            .add("1.19.0")
            .add(Integer.toString(0))
            .add(Integer.toString(4))
            .add(Long.toUnsignedString(ThreadLocalRandom.current().nextLong()))
            .add("C")
            .add("Survival")
            .add("1")
            .add("19132")
            .add("19132")
            .toString().getBytes(StandardCharsets.UTF_8);

    @Test
    public void testServer() {
        Channel channel = new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(new NioEventLoopGroup())
                .option(RakChannelOption.RAK_SUPPORTED_PROTOCOLS, new int[]{11})
                .option(RakChannelOption.RAK_MAX_CONNECTIONS, 1)
                .childOption(RakChannelOption.RAK_ORDERING_CHANNELS, 1)
                .option(RakChannelOption.RAK_GUID, ThreadLocalRandom.current().nextLong())
                .option(RakChannelOption.RAK_ADVERTISEMENT, Unpooled.wrappedBuffer(ADVERTISEMENT))
                .handler(new ChannelInitializer<RakServerChannel>() {
                    @Override
                    protected void initChannel(RakServerChannel ch) throws Exception {
                        System.out.println("Initialised server channel");
                    }
                })
                .childHandler(new ChannelInitializer<RakChildChannel>() {
                    @Override
                    protected void initChannel(RakChildChannel ch) throws Exception {
                        System.out.println("Initialised");
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 19132))
                .awaitUninterruptibly().channel();

        Object o = new Object();
        synchronized (o) {
            try {
                o.wait();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    public void testClient() {
        Channel channel = new Bootstrap()
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .group(new NioEventLoopGroup())
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                .handler(new ChannelInitializer<RakClientChannel>() {
                    @Override
                    protected void initChannel(RakClientChannel ch) throws Exception {
                        System.out.println("Initialised");
                    }
                })
                .connect(new InetSocketAddress("127.0.0.1", 19132))
                .awaitUninterruptibly()
                .channel();

        Object o = new Object();
        synchronized (o) {
            try {
                o.wait();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
