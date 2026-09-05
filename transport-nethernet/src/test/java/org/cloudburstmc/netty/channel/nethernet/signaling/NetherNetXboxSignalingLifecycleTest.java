package org.cloudburstmc.netty.channel.nethernet.signaling;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NetherNetXboxSignalingLifecycleTest {

    @Test
    void failedOldWaiterCannotAbortTheReplacementAttempt() {
        try (Signaling signaling = new Signaling()) {
            EmbeddedChannel replacement = signaling.newSocket();
            CompletableFuture<?> pending = signaling.install(replacement);

            assertThrows(ConnectException.class,
                    () -> signaling.awaitAttempt(CompletableFuture.failedFuture(new ConnectException("old attempt failed"))));

            assertTrue(replacement.isOpen());
            assertFalse(pending.isDone());
            assertSame(replacement, signaling.channel);
        }
    }

    @Test
    void staleCredentialsCannotCompleteTheNewAttemptOrRefreshItsLiveness() {
        try (Signaling signaling = new Signaling()) {
            EmbeddedChannel previous = signaling.newSocket();
            signaling.install(previous);
            EmbeddedChannel replacement = signaling.newSocket();
            CompletableFuture<?> pending = signaling.install(replacement);
            signaling.lastMessageReceivedAt = 123;
            TextWebSocketFrame stale = credentials("turn:old.invalid");

            previous.writeInbound(stale);

            assertEquals(0, stale.refCnt());
            assertEquals(123, signaling.lastMessageReceivedAt);
            assertFalse(pending.isDone());
            assertTrue(signaling.getIceServers().isEmpty());

            replacement.writeInbound(credentials("turn:current.invalid"));

            assertTrue(pending.isDone());
            assertFalse(pending.isCompletedExceptionally());
            assertEquals(List.of("turn:current.invalid"), signaling.getIceServers().getFirst().urls());
        }
    }

    @Test
    void staleHandshakeAndExceptionLeaveTheReplacementAlone() {
        try (Signaling signaling = new Signaling()) {
            EmbeddedChannel previous = signaling.newSocket();
            signaling.install(previous);
            EmbeddedChannel replacement = signaling.newSocket();
            CompletableFuture<?> pending = signaling.install(replacement);
            signaling.lastMessageReceivedAt = 123;

            previous.pipeline().fireUserEventTriggered(WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE);

            assertEquals(-1, previous.runScheduledPendingTasks(), "A stale handshake must not start ping loops");
            assertEquals(123, signaling.lastMessageReceivedAt);
            previous.pipeline().fireExceptionCaught(new IllegalStateException("old socket failed"));

            assertFalse(previous.isOpen());
            assertTrue(replacement.isOpen());
            assertFalse(pending.isDone());
            assertSame(replacement, signaling.channel);
        }
    }

    private static TextWebSocketFrame credentials(String url) {
        JsonArray urls = new JsonArray();
        urls.add(url);
        JsonObject server = new JsonObject();
        server.add("Urls", urls);
        JsonArray servers = new JsonArray();
        servers.add(server);
        JsonObject credentials = new JsonObject();
        credentials.add("TurnAuthServers", servers);
        JsonObject message = new JsonObject();
        message.addProperty("Type", 2);
        message.addProperty("Message", credentials.toString());
        return new TextWebSocketFrame(message.toString());
    }

    private static final class Signaling extends NetherNetXboxSignaling implements AutoCloseable {
        private final List<EmbeddedChannel> sockets = new ArrayList<>();

        private Signaling() {
            super("1", "MCToken unused");
        }

        private EmbeddedChannel newSocket() {
            EmbeddedChannel socket = new EmbeddedChannel(this);
            socket.freezeTime();
            sockets.add(socket);
            return socket;
        }

        private synchronized CompletableFuture<List<IceServerInfo>> install(EmbeddedChannel socket) {
            channel = socket;
            connectFuture = new CompletableFuture<>();
            return connectFuture;
        }

        private void awaitAttempt(CompletableFuture<List<IceServerInfo>> attempt) throws ConnectException {
            joinConnect(attempt);
        }

        @Override
        public void close() {
            try {
                super.close();
                sockets.forEach(EmbeddedChannel::finishAndReleaseAll);
            } finally {
                eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            }
        }
    }
}
