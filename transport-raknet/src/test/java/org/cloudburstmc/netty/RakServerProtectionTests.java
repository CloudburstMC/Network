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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakServerChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.config.RakServerCookieMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class RakServerProtectionTests {

    private static final String TEST_MASTER_SECRET_HEX = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private static final byte PROTOCOL_VERSION = 1;
    private static final byte STATUS_OK = 0;
    private static final byte STATUS_ERROR = 1;
    private static final byte OP_REGISTER = 1;
    private static final byte OP_UNREGISTER = 2;

    private NioEventLoopGroup group;
    private Channel serverChannel;
    private ServerSocketChannel registrationSocket;
    private Thread registrationThread;
    private final BlockingQueue<RegistrationRequestFrame> requests = new LinkedBlockingQueue<>();
    private final AtomicReference<RegistrationResponseFrame> response = new AtomicReference<>(new RegistrationResponseFrame(true, "ok"));

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
        Assumptions.assumeTrue(unixDomainSocketsSupported(), "JDK runtime lacks Unix domain socket support");
        Path socketPath = tempDir.resolve("bedrock-guard.sock");
        this.startRegistrationSocket(socketPath);

        ServerBootstrap bootstrap = this.serverBootstrap(socketPath);
        this.serverChannel = bootstrap.bind(new InetSocketAddress("127.0.0.1", 0)).awaitUninterruptibly().channel();
        InetSocketAddress localAddress = (InetSocketAddress) this.serverChannel.localAddress();
        RakServerChannel serverChannel = (RakServerChannel) this.serverChannel;

        Assertions.assertEquals(RakServerCookieMode.OFFLOADED_PSK, serverChannel.config().getCookieMode());
        Assertions.assertArrayEquals(hexDecode(TEST_MASTER_SECRET_HEX), serverChannel.config().getCookieSecret());

        this.serverChannel.close().awaitUninterruptibly();

        RegistrationRequestFrame register = this.requests.poll(1, TimeUnit.SECONDS);
        RegistrationRequestFrame unregister = this.requests.poll(1, TimeUnit.SECONDS);

        Assertions.assertNotNull(register, "expected register_listener request");
        Assertions.assertEquals(OP_REGISTER, register.opcode);
        Assertions.assertEquals("127.0.0.1", register.address.getHostAddress());
        Assertions.assertEquals(localAddress.getPort(), register.port);
        Assertions.assertArrayEquals(hexDecode(TEST_MASTER_SECRET_HEX), register.masterSecret);

        Assertions.assertNotNull(unregister, "expected unregister_listener request");
        Assertions.assertEquals(OP_UNREGISTER, unregister.opcode);
        Assertions.assertEquals("127.0.0.1", unregister.address.getHostAddress());
        Assertions.assertEquals(localAddress.getPort(), unregister.port);
        Assertions.assertNull(unregister.masterSecret);
    }

    @Test
    public void testFailedProtectionRegistrationFallsBackToActive(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(unixDomainSocketsSupported(), "JDK runtime lacks Unix domain socket support");
        Path socketPath = tempDir.resolve("bedrock-guard.sock");
        this.response.set(new RegistrationResponseFrame(false, "listener denied"));
        this.startRegistrationSocket(socketPath);

        ServerBootstrap bootstrap = this.serverBootstrap(socketPath);
        ChannelFuture bindFuture = bootstrap.bind(new InetSocketAddress("127.0.0.1", 0)).awaitUninterruptibly();

        Assertions.assertTrue(bindFuture.isSuccess(), "bind should succeed when protection registration fails");
        this.serverChannel = bindFuture.channel();
        RakServerChannel serverChannel = (RakServerChannel) this.serverChannel;
        Assertions.assertEquals(RakServerCookieMode.ACTIVE, serverChannel.config().getCookieMode());
        Assertions.assertNotEquals(RakServerCookieMode.OFFLOADED_PSK, serverChannel.config().getCookieMode());

        RegistrationRequestFrame register = this.requests.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(register, "expected register request before bind failure");
        Assertions.assertEquals(OP_REGISTER, register.opcode);
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
        this.registrationSocket = openUnixServerSocketChannel();
        this.registrationSocket.bind(resolveUnixSocketAddress(socketPath));
        this.registrationThread = new Thread(this::acceptLoop, "bedrock-guard-test-socket");
        this.registrationThread.setDaemon(true);
        this.registrationThread.start();
    }

    private void acceptLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try (SocketChannel channel = this.registrationSocket.accept()) {
                RegistrationRequestFrame request = readRequest(channel);
                this.requests.add(request);
                RegistrationResponseFrame response = request.opcode == OP_UNREGISTER
                        ? new RegistrationResponseFrame(true, "ok")
                        : this.response.get();
                writeFully(channel, ByteBuffer.wrap(encodeResponse(response)));
            } catch (IOException e) {
                if (this.registrationSocket.isOpen()) {
                    throw new RuntimeException(e);
                }
                return;
            }
        }
    }

    private static RegistrationRequestFrame readRequest(SocketChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(4);
        readFully(channel, header);
        header.flip();

        byte version = header.get();
        Assertions.assertEquals(PROTOCOL_VERSION, version);
        byte opcode = header.get();
        int payloadLength = Short.toUnsignedInt(header.getShort());

        ByteBuffer payload = ByteBuffer.allocate(payloadLength);
        readFully(channel, payload);
        payload.flip();

        byte[] rawAddress = new byte[4];
        payload.get(rawAddress);
        Inet4Address address = (Inet4Address) Inet4Address.getByAddress(rawAddress);
        int port = Short.toUnsignedInt(payload.getShort());

        if (opcode == OP_REGISTER) {
            byte[] masterSecret = new byte[32];
            payload.get(masterSecret);
            return new RegistrationRequestFrame(opcode, address, port, masterSecret);
        }
        if (opcode == OP_UNREGISTER) {
            return new RegistrationRequestFrame(opcode, address, port, null);
        }

        throw new IOException("unsupported opcode: " + Byte.toUnsignedInt(opcode));
    }

    private static byte[] encodeResponse(RegistrationResponseFrame response) {
        byte[] messageBytes = response.message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + messageBytes.length);
        buffer.put(PROTOCOL_VERSION);
        buffer.put(response.ok ? STATUS_OK : STATUS_ERROR);
        buffer.putShort((short) messageBytes.length);
        buffer.put(messageBytes);
        return buffer.array();
    }

    private static void writeFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void readFully(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                throw new IOException("unexpected EOF from registration socket");
            }
        }
    }

    private static byte[] hexDecode(String value) {
        byte[] output = new byte[value.length() / 2];
        for (int index = 0; index < output.length; index++) {
            int start = index * 2;
            output[index] = (byte) Integer.parseInt(value.substring(start, start + 2), 16);
        }
        return output;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean unixDomainSocketsSupported() {
        try {
            Class.forName("java.net.UnixDomainSocketAddress");
            ServerSocketChannel.class.getMethod("open", ProtocolFamily.class);
            SocketChannel.class.getMethod("open", ProtocolFamily.class);
            Class<? extends Enum> protocolFamilyClass = Class.forName("java.net.StandardProtocolFamily").asSubclass(Enum.class);
            Enum.valueOf(protocolFamilyClass, "UNIX");
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return false;
        }
    }

    private static ServerSocketChannel openUnixServerSocketChannel() throws IOException {
        try {
            Method openMethod = ServerSocketChannel.class.getMethod("open", ProtocolFamily.class);
            return (ServerSocketChannel) openMethod.invoke(null, resolveUnixProtocolFamily());
        } catch (NoSuchMethodException e) {
            throw new IOException("test requires a JDK with Unix domain socket support", e);
        } catch (IllegalAccessException | ClassNotFoundException e) {
            throw new IOException("failed to access JDK Unix domain socket support", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("failed to open Unix domain server socket channel", cause);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ProtocolFamily resolveUnixProtocolFamily() throws ClassNotFoundException {
        Class<? extends Enum> protocolFamilyClass = Class.forName("java.net.StandardProtocolFamily").asSubclass(Enum.class);
        return (ProtocolFamily) Enum.valueOf(protocolFamilyClass, "UNIX");
    }

    private static SocketAddress resolveUnixSocketAddress(Path socketPath) throws IOException {
        try {
            Class<?> addressClass = Class.forName("java.net.UnixDomainSocketAddress");
            Method ofMethod = addressClass.getMethod("of", Path.class);
            return (SocketAddress) ofMethod.invoke(null, socketPath);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            throw new IOException("failed to access JDK Unix domain socket address support", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("failed to create Unix domain socket address", cause);
        }
    }

    private static final class RegistrationRequestFrame {
        private final byte opcode;
        private final Inet4Address address;
        private final int port;
        private final byte[] masterSecret;

        private RegistrationRequestFrame(byte opcode, Inet4Address address, int port, byte[] masterSecret) {
            this.opcode = opcode;
            this.address = address;
            this.port = port;
            this.masterSecret = masterSecret;
        }
    }

    private static final class RegistrationResponseFrame {
        private final boolean ok;
        private final String message;

        private RegistrationResponseFrame(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }
}
