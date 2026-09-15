package org.cloudburstmc.netty.signaling.control;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/** Configured authenticated-link adapter. Header mapping is explicit until endpoint negotiation is wired. */
public final class JdkControlLink implements ControlClientIo.Link {
    private final JdkWebSocketTransport delegate;
    private JdkControlLink(JdkWebSocketTransport delegate) { this.delegate = delegate; }

    /** Exact staged Worker carrier; the signed envelope is never placed in a query string. */
    public static JdkControlLink connect(HttpClient client, URI endpoint, ControlSessionCodec.Request upgrade,
            JdkWebSocketTransport.Limits limits, Executor receiverExecutor,
            ScheduledExecutorService scheduler, Consumer<String> received) {
        String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(ControlSessionCodec.encode(upgrade).getBytes(StandardCharsets.UTF_8));
        return connect(client, endpoint, upgrade, Map.of("Nxs-Control-Proof", proof), limits, receiverExecutor, scheduler, received);
    }

    public static JdkControlLink connect(HttpClient client, URI endpoint, ControlSessionCodec.Request upgrade,
            Map<String, String> proofHeaders, JdkWebSocketTransport.Limits limits, Executor receiverExecutor,
            ScheduledExecutorService scheduler, Consumer<String> received) {
        String scheme = endpoint.getScheme();
        String expectedScheme = upgrade.audience().startsWith("https://") ? "wss" : "ws";
        String origin = (expectedScheme.equals("wss") ? "https://" : "http://") + endpoint.getRawAuthority();
        String target = endpoint.getRawPath() + (endpoint.getRawQuery() == null ? "" : "?" + endpoint.getRawQuery());
        if (!expectedScheme.equals(scheme) || !ControlOrigin.isCanonical(origin) || !origin.equals(upgrade.audience())
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null || !target.equals(upgrade.encodedPathAndQuery())
                || !upgrade.action().equals("upgrade") || !upgrade.method().equals("GET") || proofHeaders.isEmpty()) {
            throw ControlJson.invalid("trusted control WebSocket endpoint/proof headers");
        }
        ControlSessionCodec.encode(upgrade);
        if (limits.maxMessageBytes() > ControlFrameCodec.MAX_FRAME_BYTES || limits.maxPendingSends() > 64
                || limits.maxPendingBytes() > 4L * ControlFrameCodec.MAX_FRAME_BYTES || limits.maxReceiveParts() > 4096) {
            throw ControlJson.invalid("control WebSocket hard limits");
        }
        return new JdkControlLink(JdkWebSocketTransport.connect(client, endpoint, "nethernet-control-v1", proofHeaders,
                limits, receiverExecutor, scheduler, wire -> {
                    received.accept(wire); return java.util.concurrent.CompletableFuture.completedFuture(null);
                }));
    }
    @Override public CompletionStage<Void> opened() { return delegate.opened(); }
    @Override public CompletionStage<?> closed() { return delegate.closed(); }
    @Override public CompletionStage<Void> sendText(String wire) { return delegate.sendText(wire); }
    @Override public void close() { delegate.close(); }
    @Override public void abort() { delegate.abort(); }
}
