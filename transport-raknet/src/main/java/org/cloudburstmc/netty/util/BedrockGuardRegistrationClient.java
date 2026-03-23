/*
 * Copyright 2026 CloudburstMC
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

package org.cloudburstmc.netty.util;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class BedrockGuardRegistrationClient {

    private static final byte PROTOCOL_VERSION = 1;
    private static final byte STATUS_OK = 0;
    private static final byte STATUS_ERROR = 1;
    private static final byte OP_REGISTER = 1;
    private static final byte OP_UNREGISTER = 2;
    private static final int REGISTER_PAYLOAD_LEN = 4 + 2 + 32;
    private static final int UNREGISTER_PAYLOAD_LEN = 4 + 2;

    private BedrockGuardRegistrationClient() {
    }

    public static void registerListener(Path socketPath, InetSocketAddress localAddress, byte[] masterSecret) throws IOException {
        RegistrationResponse response = sendListenerRequest(socketPath, OP_REGISTER, localAddress, masterSecret);
        if (!response.ok) {
            throw new IOException(response.message);
        }
    }

    public static void unregisterListener(Path socketPath, InetSocketAddress localAddress) throws IOException {
        RegistrationResponse response = sendListenerRequest(socketPath, OP_UNREGISTER, localAddress, null);
        if (!response.ok) {
            throw new IOException(response.message);
        }
    }

    private static RegistrationResponse sendListenerRequest(Path socketPath, byte opcode, InetSocketAddress localAddress, byte[] masterSecret) throws IOException {
        if (!(localAddress.getAddress() instanceof Inet4Address)) {
            throw new IOException("bedrock-guard integration requires an IPv4 local bind address");
        }

        byte[] request = buildRequest(opcode, localAddress, masterSecret);

        try (SocketChannel channel = openUnixSocketChannel(socketPath)) {
            writeFully(channel, ByteBuffer.wrap(request));
            channel.shutdownOutput();
            return readResponse(channel);
        }
    }

    private static byte[] buildRequest(byte opcode, InetSocketAddress localAddress, byte[] masterSecret) throws IOException {
        byte[] addressBytes = ((Inet4Address) localAddress.getAddress()).getAddress();
        ByteBuffer buffer;

        if (opcode == OP_REGISTER) {
            if (masterSecret == null || masterSecret.length != 32) {
                throw new IOException("bedrock-guard integration requires a 32-byte master secret");
            }
            buffer = ByteBuffer.allocate(4 + REGISTER_PAYLOAD_LEN);
            buffer.put(PROTOCOL_VERSION);
            buffer.put(OP_REGISTER);
            buffer.putShort((short) REGISTER_PAYLOAD_LEN);
            buffer.put(addressBytes);
            buffer.putShort((short) localAddress.getPort());
            buffer.put(masterSecret);
        } else if (opcode == OP_UNREGISTER) {
            buffer = ByteBuffer.allocate(4 + UNREGISTER_PAYLOAD_LEN);
            buffer.put(PROTOCOL_VERSION);
            buffer.put(OP_UNREGISTER);
            buffer.putShort((short) UNREGISTER_PAYLOAD_LEN);
            buffer.put(addressBytes);
            buffer.putShort((short) localAddress.getPort());
        } else {
            throw new IOException("unsupported bedrock-guard registration opcode: " + opcode);
        }

        return buffer.array();
    }

    private static RegistrationResponse readResponse(SocketChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(4);
        readFully(channel, header);
        header.flip();

        byte version = header.get();
        if (version != PROTOCOL_VERSION) {
            throw new IOException("unsupported bedrock-guard registration protocol version: " + Byte.toUnsignedInt(version));
        }
        byte status = header.get();
        int messageLength = Short.toUnsignedInt(header.getShort());

        ByteBuffer messageBuffer = ByteBuffer.allocate(messageLength);
        readFully(channel, messageBuffer);
        String message = new String(messageBuffer.array(), StandardCharsets.UTF_8);

        if (status == STATUS_OK) {
            return new RegistrationResponse(true, message);
        }
        if (status == STATUS_ERROR) {
            return new RegistrationResponse(false, message);
        }
        throw new IOException("unsupported bedrock-guard registration response status: " + Byte.toUnsignedInt(status));
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
                throw new IOException("unexpected EOF from bedrock-guard registration socket");
            }
        }
    }

    private static SocketChannel openUnixSocketChannel(Path socketPath) throws IOException {
        try {
            Method openMethod = SocketChannel.class.getMethod("open", ProtocolFamily.class);
            SocketChannel channel = (SocketChannel) openMethod.invoke(null, resolveUnixProtocolFamily());
            try {
                channel.connect(resolveUnixSocketAddress(socketPath));
                return channel;
            } catch (IOException error) {
                channel.close();
                throw error;
            }
        } catch (NoSuchMethodException e) {
            throw new IOException("bedrock-guard registration requires a JDK with Unix domain socket support", e);
        } catch (IllegalAccessException | ClassNotFoundException e) {
            throw new IOException("failed to access JDK Unix domain socket support", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("failed to open Unix domain socket channel", cause);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ProtocolFamily resolveUnixProtocolFamily() throws ClassNotFoundException {
        Class<? extends Enum> protocolFamilyClass = Class.forName("java.net.StandardProtocolFamily").asSubclass(Enum.class);
        return (ProtocolFamily) Enum.valueOf(protocolFamilyClass, "UNIX");
    }

    private static SocketAddress resolveUnixSocketAddress(Path socketPath)
            throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Class<?> addressClass = Class.forName("java.net.UnixDomainSocketAddress");
        Method ofMethod = addressClass.getMethod("of", Path.class);
        return (SocketAddress) ofMethod.invoke(null, socketPath);
    }

    private static final class RegistrationResponse {
        private final boolean ok;
        private final String message;

        private RegistrationResponse(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }
}
