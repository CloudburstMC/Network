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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BedrockGuardRegistrationClient {

    private static final Pattern TYPE_PATTERN = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MESSAGE_PATTERN = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]+)\"");

    private BedrockGuardRegistrationClient() {
    }

    public static void registerListener(Path socketPath, InetSocketAddress localAddress, byte[] masterSecret) throws IOException {
        String response = sendListenerRequest(socketPath, "register_listener", localAddress, masterSecret);
        String responseType = extractField(TYPE_PATTERN, response);
        if ("error".equals(responseType)) {
            throw new IOException(extractMessage(response));
        }
        if (!"listener_registration".equals(responseType)) {
            throw new IOException("unexpected bedrock-guard registration response: " + response);
        }
    }

    public static void unregisterListener(Path socketPath, InetSocketAddress localAddress) throws IOException {
        String response = sendListenerRequest(socketPath, "unregister_listener", localAddress);
        String responseType = extractField(TYPE_PATTERN, response);
        if ("error".equals(responseType)) {
            throw new IOException(extractMessage(response));
        }
        if (!"ack".equals(responseType)) {
            throw new IOException("unexpected bedrock-guard unregister response: " + response);
        }
    }

    private static String sendListenerRequest(Path socketPath, String type, InetSocketAddress localAddress, byte[] masterSecret) throws IOException {
        if (!(localAddress.getAddress() instanceof Inet4Address)) {
            throw new IOException("bedrock-guard integration requires an IPv4 local bind address");
        }

        String address = localAddress.getAddress().getHostAddress();
        String payload;
        if ("register_listener".equals(type)) {
            if (masterSecret == null || masterSecret.length != 32) {
                throw new IOException("bedrock-guard integration requires a 32-byte master secret");
            }
            payload = "{\"type\":\"" + type + "\",\"address\":\"" + address + "\",\"port\":" + localAddress.getPort() + ",\"master_secret_hex\":\"" + hexEncode(masterSecret) + "\"}";
        } else {
            payload = "{\"type\":\"" + type + "\",\"address\":\"" + address + "\",\"port\":" + localAddress.getPort() + "}";
        }

        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            channel.write(ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8)));
            channel.shutdownOutput();

            String response = readAll(channel);
            return response;
        }
    }

    private static String sendListenerRequest(Path socketPath, String type, InetSocketAddress localAddress) throws IOException {
        return sendListenerRequest(socketPath, type, localAddress, null);
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

    private static String extractMessage(String response) {
        return extractField(MESSAGE_PATTERN, response);
    }

    private static String extractField(Pattern pattern, String response) {
        Matcher matcher = pattern.matcher(response);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1);
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            builder.append(Character.forDigit(value & 0x0f, 16));
        }
        return builder.toString();
    }
}
