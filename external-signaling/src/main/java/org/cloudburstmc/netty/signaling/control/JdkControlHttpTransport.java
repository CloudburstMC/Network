package org.cloudburstmc.netty.signaling.control;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bounded JDK17 bootstrap/authority/lifecycle I/O. It does not verify proofs, retry, synchronize or select writers.
 * Returned stages are read-only views; canceling a derived future leaves the request's fixed timeout intact.
 * Use close to cancel this transport. JDK cancellation may release physical resources asynchronously.
 */
public final class JdkControlHttpTransport implements AutoCloseable {
    private final HttpClient client;
    private final ScheduledExecutorService scheduler;
    private final ControlClientClock clock;
    private final int maximum;
    private final long timeoutMillis;
    private final Set<Exchange> active = new HashSet<>();
    private volatile boolean closed;

    /** The caller owns the shared client/executor and normal platform TLS trust. Redirects must be disabled. */
    public JdkControlHttpTransport(HttpClient client, ScheduledExecutorService scheduler, ControlClientClock clock,
                                   int maxConcurrentRequests, long timeoutMillis) {
        this.client = Objects.requireNonNull(client); this.scheduler = Objects.requireNonNull(scheduler);
        this.clock = Objects.requireNonNull(clock);
        if (client.followRedirects() != HttpClient.Redirect.NEVER || client.authenticator().isPresent() || client.cookieHandler().isPresent()
                || maxConcurrentRequests < 1 || maxConcurrentRequests > 16 || timeoutMillis < 1 || timeoutMillis > 30_000) {
            throw ControlJson.invalid("control HTTP configuration");
        }
        this.maximum = maxConcurrentRequests; this.timeoutMillis = timeoutMillis;
    }

    public CompletionStage<ControlClientIo.HttpReply> bootstrap(URI endpoint, ControlSessionCodec.Request request) {
        if (request.action().equals("upgrade")) throw ControlJson.invalid("WebSocket proof cannot use bootstrap POST");
        return post(endpoint, request.audience(), request.method(), request.encodedPathAndQuery(), request.expiresAt(),
                ControlSessionCodec.encode(request), ControlSessionCodec.MAX_ENVELOPE_BYTES);
    }

    public CompletionStage<ControlClientIo.HttpReply> authority(URI endpoint, ControlAuthorityCodec.Request request) {
        return post(endpoint, request.audience(), request.method(), request.encodedPathAndQuery(), request.expiresAt(),
                ControlAuthorityCodec.encode(request), ControlAuthorityCodec.MAX_ENVELOPE_BYTES);
    }

    /** Original signed proof in its canonical header; original operation bytes stay in the HTTP body. */
    public CompletionStage<ControlClientIo.HttpReply> operation(URI endpoint, ControlHttpCodec.Request request, byte[] originalBody) {
        Objects.requireNonNull(originalBody);
        if (originalBody.length > ControlLifecycleCodec.MAX_HTTP_BODY_BYTES) throw ControlJson.invalid("control HTTP body size");
        byte[] body = originalBody.clone();
        ControlLifecycleCodec.verifyBody(request.intent(), body);
        String proof = org.cloudburstmc.netty.signaling.ProviderCrypto.base64(ControlHttpCodec.encode(request).getBytes(StandardCharsets.UTF_8));
        return post(endpoint, request.audience(), request.method(), request.encodedPathAndQuery(), request.expiresAt(),
                body, proof, ControlResultCodec.MAX_ENVELOPE_BYTES);
    }

    private CompletionStage<ControlClientIo.HttpReply> post(URI endpoint, String audience, String method, String target,
                                                           long expiresAt, String wire, int maxResponseBytes) {
        return post(endpoint, audience, method, target, expiresAt, wire.getBytes(StandardCharsets.UTF_8), null, maxResponseBytes);
    }

    private CompletionStage<ControlClientIo.HttpReply> post(URI endpoint, String audience, String method, String target,
                                                           long expiresAt, byte[] body, String proof, int maxResponseBytes) {
        Objects.requireNonNull(endpoint);
        String origin = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
        String actualTarget = endpoint.getRawPath() + (endpoint.getRawQuery() == null ? "" : "?" + endpoint.getRawQuery());
        if (!ControlOrigin.isCanonical(origin) || !origin.equals(audience) || !"POST".equals(method)
                || endpoint.getRawUserInfo() != null || endpoint.getRawFragment() != null || !actualTarget.equals(target)) {
            throw ControlJson.invalid("trusted control HTTP endpoint");
        }
        long now = clock.nowMillis();
        if (now >= expiresAt) return CompletableFuture.failedFuture(new HttpTimeoutException("Control proof expired before send"));
        long duration = Math.min(timeoutMillis, expiresAt - now), deadline = now + duration;
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(duration))
                .header("Content-Type", "application/json; charset=utf-8").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (proof != null) builder.header(ControlHttpCodec.PROOF_HEADER, proof);
        HttpRequest request = builder.build();
        Exchange exchange = new Exchange(request, deadline, maxResponseBytes);
        synchronized (active) {
            if (closed || active.size() >= maximum) return CompletableFuture.failedFuture(new IOException("Control HTTP capacity unavailable"));
            active.add(exchange);
        }
        exchange.start();
        return exchange.result.minimalCompletionStage();
    }

    private final class Exchange {
        final HttpRequest request;
        final long deadline;
        final int maxBytes;
        final CompletableFuture<ControlClientIo.HttpReply> result = new CompletableFuture<>();
        volatile CompletableFuture<HttpResponse<String>> pending;
        volatile ScheduledFuture<?> timer;
        volatile BoundedBody body;
        // Guarded by active. A chosen verdict is final, but its callbacks run only after cleanup.
        boolean decided, published;
        ControlClientIo.HttpReply reply;
        Throwable error;
        Exchange(HttpRequest request, long deadline, int maxBytes) {
            this.request = request; this.deadline = deadline; this.maxBytes = maxBytes;
        }
        void start() {
            try {
                if (closed || isDecided()) { cancel(new IOException("Control HTTP closed")); release(); return; }
                long remaining = deadline - clock.nowMillis();
                if (remaining <= 0) { cancel(new HttpTimeoutException("Control HTTP delivery deadline")); release(); return; }
                timer = scheduler.schedule(() -> cancel(new HttpTimeoutException("Control HTTP delivery deadline")), remaining, TimeUnit.MILLISECONDS);
                if (clock.nowMillis() >= deadline || closed || isDecided()) {
                    cancel(new HttpTimeoutException("Control HTTP delivery deadline")); cleanup(); release(); return;
                }
                pending = client.sendAsync(request, info -> {
                    BoundedBody received = new BoundedBody(maxBytes); body = received;
                    if (isDecided() || closed) received.fail(new IOException("Control HTTP closed"));
                    String type = info.headers().firstValue("Content-Type").orElse("");
                    if (!type.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")
                            || !info.headers().allValues("Content-Encoding").isEmpty()) received.fail(new IOException("Unexpected control HTTP content type or encoding"));
                    var lengths = info.headers().allValues("Content-Length");
                    if (lengths.size() > 1 || (lengths.size() == 1 && (!lengths.get(0).matches("0|[1-9][0-9]{0,8}")
                            || Long.parseLong(lengths.get(0)) > maxBytes))) received.fail(new IOException("Control HTTP body exceeds limit"));
                    return received;
                });
                pending.whenComplete(this::complete);
                // close/timeout can win before sendAsync returns or before the body handler attaches.
                if (isDecided()) cleanup();
            } catch (Throwable failure) { cancel(failure); release(); }
        }
        boolean isDecided() { synchronized (active) { return decided; } }
        void complete(HttpResponse<String> response, Throwable failure) {
            ControlClientIo.HttpReply received = null;
            try {
                if (failure == null) {
                    if (!response.uri().equals(request.uri()) || !response.request().uri().equals(request.uri())
                            || !response.request().method().equals("POST") || response.previousResponse().isPresent()) {
                        failure = new IOException("Unexpected control HTTP response provenance");
                    } else received = new ControlClientIo.HttpReply(request.uri(), "POST", response.uri(), response.statusCode(), response.body());
                }
            } catch (Throwable invalid) { failure = invalid; }
            synchronized (active) {
                // Release capacity and select delivery atomically with close. No public callbacks occur here.
                active.remove(this);
                if (decided) return;
                try {
                    if (closed || clock.nowMillis() >= deadline) failure = new HttpTimeoutException("Control HTTP delivery deadline");
                } catch (Throwable invalid) { failure = invalid; }
                decided = true; reply = received; error = failure;
            }
            if (error != null) cleanup(); else if (timer != null) timer.cancel(false);
            publish();
        }
        boolean chooseFailure(Throwable failure) {
            synchronized (active) {
                if (decided) return false;
                decided = true; error = failure; return true;
            }
        }
        void cancel(Throwable failure) {
            if (!chooseFailure(failure)) return;
            try { cleanup(); } finally { publish(); }
        }
        void cleanup() {
            Throwable failure;
            synchronized (active) { if (!decided || error == null) return; failure = error; }
            if (timer != null) timer.cancel(false);
            // Select the verdict before these callbacks, and cancel before exposing it to callers.
            // JDK cancellation is best effort; physical socket teardown may finish asynchronously.
            try { if (pending != null) pending.cancel(true); }
            finally { if (body != null) body.fail(failure); }
        }
        void publish() {
            ControlClientIo.HttpReply delivered; Throwable failure;
            synchronized (active) {
                if (!decided || published) return;
                published = true; delivered = reply; failure = error;
            }
            if (failure == null) result.complete(delivered); else result.completeExceptionally(failure);
        }
        private void release() {
            if (timer != null) timer.cancel(false);
            synchronized (active) { active.remove(this); }
        }
    }

    /** A small fixed buffer; response chunks are copied synchronously and never enqueued by this adapter. */
    static final class BoundedBody implements HttpResponse.BodySubscriber<String> {
        private final byte[] bytes;
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int size;
        private boolean ended;
        BoundedBody(int maximum) { bytes = new byte[maximum]; }
        @Override public CompletionStage<String> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            boolean reject;
            synchronized (this) { reject = subscription != null || ended; if (!reject) subscription = value; }
            if (reject) value.cancel(); else value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            Flow.Subscription next;
            try {
                synchronized (this) {
                    if (ended) return;
                    for (ByteBuffer buffer : buffers) {
                        int count = buffer.remaining();
                        if (count > bytes.length - size) throw new IOException("Control HTTP body exceeds limit");
                        buffer.get(bytes, size, count); size += count;
                    }
                    next = subscription;
                }
                next.request(1);
            } catch (Throwable failure) { fail(failure); }
        }
        @Override public void onError(Throwable error) { fail(error); }
        @Override public void onComplete() {
            String text = null; Throwable failure = null; Flow.Subscription current;
            synchronized (this) {
                if (ended) return;
                try {
                    if (size == 0) throw new IOException("Empty control HTTP body");
                    text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, size)).toString();
                } catch (Throwable invalid) { failure = invalid; }
                ended = true; current = subscription;
            }
            if (failure == null) result.complete(text); else completeFailure(failure, current);
        }
        void fail(Throwable failure) {
            Flow.Subscription current;
            synchronized (this) { if (ended) return; ended = true; current = subscription; }
            completeFailure(failure, current);
        }
        private void completeFailure(Throwable failure, Flow.Subscription current) {
            // Cancellation must precede completion: completion callbacks may block or reenter.
            // Neither callback runs while holding the body lock. Reentrant cancellation observes ended.
            try { if (current != null) current.cancel(); }
            finally { result.completeExceptionally(failure); }
        }
    }

    /**
     * Stops this transport's requests only. The caller retains its shared HttpClient and executor.
     * Returned stages are read-only views; canceling a derived future does not cancel a request.
     */
    @Override public void close() {
        List<Exchange> requests;
        synchronized (active) {
            closed = true; requests = List.copyOf(active);
            for (Exchange exchange : requests) exchange.chooseFailure(new IOException("Control HTTP closed"));
        }
        // Claim all failures first, then cancel every request before any public callback can block close.
        for (Exchange exchange : requests) exchange.cleanup();
        for (Exchange exchange : requests) exchange.publish();
    }
}
