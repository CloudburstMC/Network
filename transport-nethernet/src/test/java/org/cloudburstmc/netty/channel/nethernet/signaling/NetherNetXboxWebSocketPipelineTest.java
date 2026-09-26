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

package org.cloudburstmc.netty.channel.nethernet.signaling;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetXboxWebSocketPipelineTest {
    @ParameterizedTest
    @CsvSource({"65536,false", "65536,true", "81920,false", "81920,true", "131072,false", "131072,true"})
    void acceptsTheSameMessageSizeRegardlessOfFraming(int size, boolean fragmented) throws Exception {
        try (Signaling signaling = new Signaling("1")) {
            if (fragmented) {
                signaling.receive(new TextWebSocketFrame(false, 0, "x".repeat(size / 2)));
                assertEquals(0, signaling.messages);
                signaling.receive(new ContinuationWebSocketFrame(true, 0, "x".repeat(size - size / 2)));
            } else {
                signaling.receive(new TextWebSocketFrame("x".repeat(size)));
            }
            assertTrue(signaling.socket.isOpen());
            assertEquals(1, signaling.messages);
            assertEquals(size, signaling.receivedBytes);
            assertEquals(0, signaling.received.refCnt());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsOversizedMessagesAndReleasesThePendingConnect(boolean fragmented) throws Exception {
        try (Signaling signaling = new Signaling("1")) {
            if (fragmented) {
                signaling.receive(new TextWebSocketFrame(false, 0, "x".repeat(65536)));
                signaling.receive(new ContinuationWebSocketFrame(true, 0, "x".repeat(65537)));
            } else {
                signaling.receive(new TextWebSocketFrame("x".repeat(131073)));
            }
            assertFalse(signaling.socket.isOpen());
            assertEquals(0, signaling.messages);
            assertTrue(signaling.pending.isCompletedExceptionally());
            assertEquals(-1, signaling.socket.runScheduledPendingTasks());
        }
    }

    @Test
    void protocolPongsRefreshLivenessDuringAFragmentedMessage() throws Exception {
        try (Signaling signaling = new Signaling("1")) {
            signaling.receive(new TextWebSocketFrame(false, 0, "first"));
            signaling.lastMessageReceivedAt = System.currentTimeMillis() - 60_000;
            assertFalse(signaling.isChannelAlive(45_000));

            signaling.receive(new PongWebSocketFrame());

            assertTrue(signaling.isChannelAlive(45_000));
            assertEquals(0, signaling.messages);
            signaling.receive(new ContinuationWebSocketFrame(true, 0, "last"));
            assertEquals(1, signaling.messages);
            assertEquals(9, signaling.receivedBytes);
        }
    }

    @Test
    void serviceCloseReachesSignalingWithItsCodeAndReason() throws Exception {
        try (Signaling signaling = new Signaling("1")) {
            signaling.receive(new CloseWebSocketFrame(1008, "Registration expired"));

            assertEquals(1008, signaling.closeCode);
            assertEquals("Registration expired", signaling.closeReason);
            assertEquals(0, signaling.received.refCnt());
            assertFalse(signaling.socket.isOpen());
            assertTrue(signaling.pending.isCompletedExceptionally());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"peer /?#+%", ".", ".."})
    void handshakeUsesTheEscapedNetworkId(String id) throws Exception {
        try (Signaling signaling = new Signaling(id)) {
            assertTrue(signaling.upgrade.startsWith("GET " + signaling.uri.getRawPath() + " HTTP/1.1\r\n"));
        }
    }

    private static ByteBuf encode(WebSocketFrame frame) {
        EmbeddedChannel encoder = new EmbeddedChannel(new WebSocket13FrameEncoder(false));
        ByteBuf wire = Unpooled.buffer();
        try {
            encoder.writeOutbound(frame);
            ByteBuf part;
            while ((part = encoder.readOutbound()) != null) {
                try {
                    wire.writeBytes(part);
                } finally {
                    part.release();
                }
            }
            assertEquals(0, frame.refCnt());
            return wire;
        } catch (Throwable error) {
            wire.release();
            throw error;
        } finally {
            encoder.finishAndReleaseAll();
        }
    }

    private static final class Signaling extends NetherNetXboxSignaling {
        private final EmbeddedChannel socket;
        private final CompletableFuture<?> pending;
        private final String upgrade;
        private final List<ByteBuf> wireBuffers = new ArrayList<>();
        private int messages;
        private int receivedBytes;
        private int closeCode;
        private String closeReason;
        private WebSocketFrame received;

        private Signaling(String id) throws Exception {
            super(id, "MCToken unused");
            socket = new EmbeddedChannel(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel channel) {
                    configureWebSocketPipeline(channel.pipeline(), newHandshaker());
                }
            });
            socket.freezeTime();
            channel = socket;
            connectFuture = new CompletableFuture<>();
            pending = connectFuture;
            StringBuilder request = new StringBuilder();
            ByteBuf part;
            while ((part = socket.readOutbound()) != null) {
                try {
                    request.append(part.toString(StandardCharsets.US_ASCII));
                } finally {
                    part.release();
                }
            }
            upgrade = request.toString();
            String key = upgrade.lines().filter(line -> line.regionMatches(true, 0, "Sec-WebSocket-Key:", 0, 18))
                    .findFirst().orElseThrow().substring(18).trim();
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            socket.writeInbound(Unpooled.copiedBuffer("HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n",
                    StandardCharsets.US_ASCII));
            assertTrue(socket.isOpen());
        }

        private void receive(WebSocketFrame frame) {
            ByteBuf wire = encode(frame);
            wireBuffers.add(wire);
            socket.writeInbound(wire);
            socket.runPendingTasks();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
            if (message instanceof CloseWebSocketFrame close) {
                received = close;
                closeCode = close.statusCode();
                closeReason = close.reasonText();
            }
            super.channelRead(ctx, message);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
            received = frame;
            messages++;
            receivedBytes = frame.content().readableBytes();
        }

        @Override
        public void close() {
            super.close();
            eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            socket.finishAndReleaseAll();
            wireBuffers.forEach(buffer -> assertEquals(0, buffer.refCnt()));
        }
    }
}
