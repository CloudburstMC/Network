package org.cloudburstmc.netty.signaling.control;

import java.net.URI;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

/** Test-only access to the real transport pump with a controllable JDK send completion. */
public final class JdkQueuedSendFixture {
    private JdkQueuedSendFixture() { }
    public static JdkWebSocketTransport connect(WebSocket.Builder socket, JdkWebSocketTransport.Limits limits,
                                                ScheduledExecutorService scheduler) {
        return JdkWebSocketTransport.connect(socket, URI.create("ws://localhost/fixture"), "fixture", Map.of(), limits,
                Runnable::run, scheduler, ignored -> CompletableFuture.completedFuture(null));
    }
}
