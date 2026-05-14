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
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakClientChannel;
import org.cloudburstmc.netty.channel.raknet.config.DefaultRakServerThrottle;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

public class RakThrottleTests {

    private static final int PORT = 19134;
    private static final int PROTOCOL_VERSION = 11;

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

    private void setupServer() {
        ServerBootstrap b = new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(group)
                .option(RakChannelOption.RAK_THROTTLE, new DefaultRakServerThrottle(3, 1_000, 1))
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                    }
                });

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
    public void testConnectsMax() {
        setupServer();

        for (int i = 0; i < 2; i++) {
            Channel client = clientBootstrap()
                    .connect(new InetSocketAddress("127.0.0.1", PORT))
                    .awaitUninterruptibly()
                    .channel();
            Assertions.assertEquals(i < 1, client.isActive());
        }

        try {
            Thread.sleep(1_000);
        } catch (InterruptedException ignored) {
        }

        for (int i = 0; i < 2; i++) {
            Channel client = clientBootstrap()
                    .connect(new InetSocketAddress("127.0.0.1", PORT))
                    .awaitUninterruptibly()
                    .channel();
            Assertions.assertEquals(i < 1, client.isActive());
        }
    }

    @Test
    public void testConnectionsMax() {
        setupServer();

        List<Channel> clients = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Channel client = clientBootstrap()
                    .connect(new InetSocketAddress("127.0.0.1", PORT))
                    .awaitUninterruptibly()
                    .channel();
            clients.add(client);
            Assertions.assertEquals(i < 3, client.isActive());

            try {
                Thread.sleep(1_000);
            } catch (InterruptedException ignored) {
            }
        }

        for (Channel client : clients) {
            client.close().awaitUninterruptibly();
        }
        clients.clear();

        try {
            Thread.sleep(10_000);
        } catch (InterruptedException ignored) {
        }

        for (int i = 0; i < 4; i++) {
            Channel client = clientBootstrap()
                    .connect(new InetSocketAddress("127.0.0.1", PORT))
                    .awaitUninterruptibly()
                    .channel();
            Assertions.assertEquals(i < 3, client.isActive());

            try {
                Thread.sleep(1_000);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
