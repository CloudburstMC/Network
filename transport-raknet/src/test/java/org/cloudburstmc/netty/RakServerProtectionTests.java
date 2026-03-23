/*
 * Copyright 2026 CloudburstMC
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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakServerChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.config.RakServerCookieMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class RakServerProtectionTests {

    private static final String TEST_MASTER_SECRET_HEX = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    private NioEventLoopGroup group;
    private Channel serverChannel;
    private ServerSocketChannel registrationSocket;
    private Thread registrationThread;
    private final BlockingQueue<String> requests = new LinkedBlockingQueue<>();
    private final AtomicReference<String> response = new AtomicReference<>("{\"type\":\"listener_registration\",\"message\":\"ok\"}");

    @BeforeEach
    public void setup() {
        this.group = new NioEventLoopGroup();
    }

    @AfterEach
    public void teardown() throws IOException {
        if (this.serverChannel != null) {
            this.serverChannel.close().awaitUninterruptibly();
        }
        if (this.registrationSocket != null) {
            this.registrationSocket.close();
        }
        if (this.registrationThread != null) {
            this.registrationThread.interrupt();
            try {
                this.registrationThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        this.group.shutdownGracefully().awaitUninterruptibly();
    }

    @Test
    public void testRegistersAndUnregistersBedrockGuardProtection(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("bedrock-guard.sock");
        this.startRegistrationSocket(socketPath);

        ServerBootstrap bootstrap = this.serverBootstrap(socketPath);
        this.serverChannel = bootstrap.bind(new InetSocketAddress("127.0.0.1", 0)).awaitUninterruptibly().channel();
        InetSocketAddress localAddress = (InetSocketAddress) this.serverChannel.localAddress();
        RakServerChannel serverChannel = (RakServerChannel) this.serverChannel;

        Assertions.assertEquals(RakServerCookieMode.OFFLOADED_PSK, serverChannel.config().getCookieMode());
        Assertions.assertArrayEquals(hexDecode(TEST_MASTER_SECRET_HEX), serverChannel.config().getCookieSecret());

        this.serverChannel.close().awaitUninterruptibly();

        String register = this.requests.poll(1, TimeUnit.SECONDS);
        String unregister = this.requests.poll(1, TimeUnit.SECONDS);

        Assertions.assertNotNull(register, "expected register_listener request");
        Assertions.assertTrue(register.contains("\"type\":\"register_listener\""));
        Assertions.assertTrue(register.contains("\"address\":\"127.0.0.1\""));
        Assertions.assertTrue(register.contains("\"port\":" + localAddress.getPort()));
        Assertions.assertTrue(register.contains("\"master_secret_hex\":\"" + TEST_MASTER_SECRET_HEX + "\""));

        Assertions.assertNotNull(unregister, "expected unregister_listener request");
        Assertions.assertTrue(unregister.contains("\"type\":\"unregister_listener\""));
        Assertions.assertTrue(unregister.contains("\"address\":\"127.0.0.1\""));
        Assertions.assertTrue(unregister.contains("\"port\":" + localAddress.getPort()));
    }

    @Test
    public void testFailedProtectionRegistrationFallsBackToActive(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("bedrock-guard.sock");
        this.response.set("{\"type\":\"error\",\"message\":\"listener denied\"}");
        this.startRegistrationSocket(socketPath);

        ServerBootstrap bootstrap = this.serverBootstrap(socketPath);
        var bindFuture = bootstrap.bind(new InetSocketAddress("127.0.0.1", 0)).awaitUninterruptibly();

        Assertions.assertTrue(bindFuture.isSuccess(), "bind should succeed when protection registration fails");
        this.serverChannel = bindFuture.channel();
        RakServerChannel serverChannel = (RakServerChannel) this.serverChannel;
        Assertions.assertEquals(RakServerCookieMode.ACTIVE, serverChannel.config().getCookieMode());
        Assertions.assertNotEquals(RakServerCookieMode.OFFLOADED_PSK, serverChannel.config().getCookieMode());

        String register = this.requests.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(register, "expected register request before bind failure");
        Assertions.assertTrue(register.contains("\"type\":\"register_listener\""));
    }

    @Test
    public void testRejectsExplicitCookieModeWithFilterRegistrationSocket(@TempDir Path tempDir) {
        RakServerChannel channel = new RakServerChannel(new NioDatagramChannel());
        Path socketPath = tempDir.resolve("bedrock-guard.sock");

        channel.config().setOption(RakChannelOption.RAK_SERVER_COOKIE_MODE, RakServerCookieMode.ACTIVE);
        IllegalStateException error = Assertions.assertThrows(IllegalStateException.class,
                () -> channel.config().setOption(RakChannelOption.RAK_SERVER_FILTER_REGISTRATION_SOCKET_PATH, socketPath));
        Assertions.assertTrue(error.getMessage().contains("cookie mode"));
    }

    private ServerBootstrap serverBootstrap(Path socketPath) {
        return new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(this.group)
                .option(RakChannelOption.RAK_SERVER_COOKIE_SECRET, hexDecode(TEST_MASTER_SECRET_HEX))
                .option(RakChannelOption.RAK_SERVER_FILTER_REGISTRATION_SOCKET_PATH, socketPath)
                .handler(new ChannelInitializer<RakServerChannel>() {
                    @Override
                    protected void initChannel(RakServerChannel ch) {
                    }
                })
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                    }
                });
    }

    private void startRegistrationSocket(Path socketPath) throws IOException {
        this.registrationSocket = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        this.registrationSocket.bind(UnixDomainSocketAddress.of(socketPath));
        this.registrationThread = new Thread(this::acceptLoop, "bedrock-guard-test-socket");
        this.registrationThread.setDaemon(true);
        this.registrationThread.start();
    }

    private void acceptLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try (SocketChannel channel = this.registrationSocket.accept()) {
                String request = readAll(channel);
                this.requests.add(request);
                String response = request.contains("\"type\":\"unregister_listener\"")
                        ? "{\"type\":\"ack\",\"message\":\"ok\"}"
                        : this.response.get();
                channel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                if (this.registrationSocket.isOpen()) {
                    throw new RuntimeException(e);
                }
                return;
            }
        }
    }

    private static String readAll(SocketChannel channel) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        while (true) {
            int read = channel.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            buffer.flip();
            output.write(buffer.array(), 0, buffer.remaining());
            buffer.clear();
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static byte[] hexDecode(String value) {
        byte[] output = new byte[value.length() / 2];
        for (int index = 0; index < output.length; index++) {
            int start = index * 2;
            output[index] = (byte) Integer.parseInt(value.substring(start, start + 2), 16);
        }
        return output;
    }
}
