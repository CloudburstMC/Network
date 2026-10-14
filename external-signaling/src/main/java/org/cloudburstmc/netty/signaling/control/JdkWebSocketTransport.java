package org.cloudburstmc.netty.signaling.control;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * One bounded text WebSocket connection. Authentication, endpoint trust, application acknowledgments,
 * reconnect and session authority belong to the caller. A successful send means local send completion,
 * not acknowledgment that the remote application received or accepted the message.
 * The client, executor and scheduler are borrowed and must outlive this connection; none is shut down here.
 * The receiver executor must dispatch asynchronously and its handler must return promptly. Only one
 * complete message is delivered at a time, and the next receive is requested after its stage completes.
 * Cancelling a returned stage does not cancel the connection; use {@link #abort()} to do that.
 */
public final class JdkWebSocketTransport implements AutoCloseable {
    /** Limits apply to UTF-8 bytes, pending sends including the active send, and JDK receive callbacks. */
    public record Limits(
            int maxMessageBytes,
            int maxReceiveParts,
            int maxPendingSends,
            long maxPendingBytes,
            Duration connectTimeout,
            Duration receiveTimeout,
            Duration sendTimeout,
            Duration closeTimeout) {
        public Limits {
            if (maxMessageBytes < 1
                    || maxReceiveParts < 1
                    || maxPendingSends < 1
                    || maxPendingBytes < 1) {
                throw new IllegalArgumentException("WebSocket limits must be positive");
            }
            for (Duration timeout :
                    List.of(connectTimeout, receiveTimeout, sendTimeout, closeTimeout)) {
                if (timeout.isNegative() || timeout.isZero()) {
                    throw new IllegalArgumentException("WebSocket timeouts must be positive");
                }
                timeout.toNanos(); // Reject durations the scheduler cannot represent.
            }
        }
    }

    public record Close(int statusCode, String reason) {}

    private static final CompletionStage<Void> RECEIVED = CompletableFuture.completedFuture(null);
    private final Object lock = new Object();
    private final Limits limits;
    private final String subprotocol;
    private final Executor receiverExecutor;
    private final ScheduledExecutorService scheduler;
    private final Function<String, ? extends CompletionStage<?>> receiver;
    private final CompletableFuture<Void> opened = new CompletableFuture<>();
    private final CompletableFuture<Close> closed = new CompletableFuture<>();
    private final ArrayDeque<Send> sends = new ArrayDeque<>();
    private final Listener listener = new Listener();
    private CompletableFuture<WebSocket> connecting;
    private WebSocket socket;
    private Send activeSend;
    private long pendingBytes;
    private StringBuilder incoming;
    private int receiveParts;
    private long receiveGeneration;
    private ScheduledFuture<?> receiveTimer;
    private ScheduledFuture<?> sendTimer;
    private ScheduledFuture<?> closeTimer;
    private Close closeRequest;
    private boolean closeSent;
    private boolean connected;
    private boolean terminal;

    private JdkWebSocketTransport(
            Limits limits,
            String subprotocol,
            Executor receiverExecutor,
            ScheduledExecutorService scheduler,
            Function<String, ? extends CompletionStage<?>> receiver) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.subprotocol = Objects.requireNonNull(subprotocol, "subprotocol");
        this.receiverExecutor = Objects.requireNonNull(receiverExecutor, "receiverExecutor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.receiver = Objects.requireNonNull(receiver, "receiver");
    }

    /**
     * Starts an asynchronous upgrade. Production callers must supply a trusted WSS endpoint and normal
     * TLS verification. Plain WS is supported for local transports/tests, never selected as a fallback.
     * An empty subprotocol requests none; otherwise the server must select exactly the requested value.
     */
    public static JdkWebSocketTransport connect(
            HttpClient client,
            URI endpoint,
            String subprotocol,
            Map<String, String> headers,
            Limits limits,
            Executor receiverExecutor,
            ScheduledExecutorService scheduler,
            Function<String, ? extends CompletionStage<?>> receiver) {
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Control WebSocket clients must disable redirects");
        }
        return connect(
                client.newWebSocketBuilder(),
                endpoint,
                subprotocol,
                headers,
                limits,
                receiverExecutor,
                scheduler,
                receiver);
    }

    // Builder injection also lets tests hold sends pending deterministically without relying on TCP
    // buffers.
    static JdkWebSocketTransport connect(
            WebSocket.Builder builder,
            URI endpoint,
            String subprotocol,
            Map<String, String> headers,
            Limits limits,
            Executor receiverExecutor,
            ScheduledExecutorService scheduler,
            Function<String, ? extends CompletionStage<?>> receiver) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!("wss".equalsIgnoreCase(endpoint.getScheme())
                        || "ws".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Expected a WebSocket endpoint without user-info or fragment");
        }
        JdkWebSocketTransport transport =
                new JdkWebSocketTransport(
                        limits, subprotocol, receiverExecutor, scheduler, receiver);
        builder.connectTimeout(limits.connectTimeout());
        Map.copyOf(headers).forEach(builder::header);
        if (!subprotocol.isEmpty()) {
            builder.subprotocols(subprotocol);
        }
        try {
            CompletableFuture<WebSocket> future = builder.buildAsync(endpoint, transport.listener);
            synchronized (transport.lock) {
                transport.connecting = future;
                if (transport.terminal) {
                    future.cancel(true);
                }
            }
            future.whenComplete(
                    (webSocket, failure) -> {
                        if (failure != null) {
                            transport.fail(failure);
                        }
                    });
        } catch (RuntimeException failure) {
            transport.fail(failure);
        }
        return transport;
    }

    public CompletionStage<Void> opened() {
        return this.opened.minimalCompletionStage();
    }

    /** Completes on peer close; errors, abort and timeout complete exceptionally. */
    public CompletionStage<Close> closed() {
        return this.closed.minimalCompletionStage();
    }

    /** Sends one complete immutable message, failing immediately when capacity is exhausted. */
    public CompletionStage<Void> sendText(String text) {
        return this.sendText(text, () -> {});
    }

    /** Carries the original nonblocking authority guard through the bounded send queue. */
    public CompletionStage<Void> sendText(String text, Runnable requireCurrent) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(requireCurrent, "requireCurrent");
        long bytes = utf8Bytes(text);
        Send send = new Send(text, bytes, requireCurrent);
        synchronized (this.lock) {
            if (this.terminal || this.closeRequest != null || !this.connected) {
                return CompletableFuture.failedFuture(new IOException("WebSocket is not open"));
            }
            if (bytes > this.limits.maxMessageBytes()) {
                return CompletableFuture.failedFuture(
                        new IOException("WebSocket message exceeds byte limit"));
            }
            if (this.sends.size() + (this.activeSend == null ? 0 : 1)
                            >= this.limits.maxPendingSends()
                    || bytes > this.limits.maxPendingBytes() - this.pendingBytes) {
                return CompletableFuture.failedFuture(
                        new IOException("WebSocket send capacity exhausted"));
            }
            this.sends.add(send);
            this.pendingBytes += bytes;
        }
        this.pump();
        return send.result.minimalCompletionStage();
    }

    /** Drains accepted sends then sends normal closure, with a deadline covering the entire drain. */
    public CompletionStage<Close> closeGracefully() {
        try {
            synchronized (this.lock) {
                if (this.terminal || this.closeRequest != null) {
                    return this.closed();
                }
                this.closeRequest = new Close(WebSocket.NORMAL_CLOSURE, "");
                this.closeTimer =
                        this.scheduler.schedule(
                                () -> this.fail(new TimeoutException("WebSocket close timed out")),
                                this.limits.closeTimeout().toNanos(),
                                TimeUnit.NANOSECONDS);
            }
            this.pump();
        } catch (RuntimeException failure) {
            this.fail(failure);
        }
        return this.closed();
    }

    /** Initiates bounded graceful closure without blocking the calling thread. */
    @Override
    public void close() {
        this.closeGracefully();
    }

    public void abort() {
        this.fail(new IOException("WebSocket aborted"));
    }

    private void pump() {
        WebSocket webSocket;
        Send send;
        Close closing;
        try {
            synchronized (this.lock) {
                if (this.terminal
                        || this.socket == null
                        || this.activeSend != null
                        || this.closeSent) {
                    return;
                }
                webSocket = this.socket;
                send = this.sends.poll();
                closing = send == null ? this.closeRequest : null;
                if (send != null) {
                    this.activeSend = send;
                    this.sendTimer =
                            this.scheduler.schedule(
                                    () -> this.sendTimedOut(send),
                                    this.limits.sendTimeout().toNanos(),
                                    TimeUnit.NANOSECONDS);
                } else if (closing != null) {
                    this.closeSent = true;
                } else {
                    return;
                }
            }
            if (send != null) {
                // The coordinator may acquire its own monitor or reenter abort from this guard.
                // Never invoke it under lock; check transport ownership again after it returns.
                send.requireCurrent.run();
                synchronized (this.lock) {
                    if (this.terminal || this.activeSend != send || this.socket != webSocket) {
                        return;
                    }
                }
                webSocket
                        .sendText(send.text, true)
                        .whenComplete(
                                (ignored, failure) -> {
                                    if (failure != null) {
                                        this.fail(failure);
                                        return;
                                    }
                                    synchronized (this.lock) {
                                        if (this.terminal || this.activeSend != send) {
                                            return;
                                        }
                                        cancel(this.sendTimer);
                                        this.sendTimer = null;
                                        this.activeSend = null;
                                        this.pendingBytes -= send.bytes;
                                    }
                                    send.result.complete(null);
                                    this.pump();
                                });
            } else {
                webSocket
                        .sendClose(closing.statusCode(), closing.reason())
                        .whenComplete(
                                (ignored, failure) -> {
                                    if (failure != null) {
                                        this.fail(failure);
                                    }
                                });
            }
        } catch (RuntimeException failure) {
            this.fail(failure);
        }
    }

    private void sendTimedOut(Send expected) {
        this.terminate(null, new TimeoutException("WebSocket send timed out"), expected, -1);
    }

    private void fail(Throwable failure) {
        this.terminate(null, failure, null, -1);
    }

    private void terminate(
            Close close, Throwable failure, Send expectedSend, long expectedReceive) {
        List<Send> pending;
        WebSocket webSocket;
        CompletableFuture<WebSocket> handshake;
        synchronized (this.lock) {
            if (this.terminal
                    || (expectedSend != null && this.activeSend != expectedSend)
                    || (expectedReceive >= 0
                            && (this.receiveTimer == null
                                    || this.receiveGeneration != expectedReceive))) {
                return;
            }
            this.terminal = true;
            cancel(this.receiveTimer);
            cancel(this.sendTimer);
            cancel(this.closeTimer);
            this.closeTimer = null;
            this.sendTimer = null;
            this.receiveTimer = null;
            this.incoming = null;
            pending = new ArrayList<>(this.sends);
            this.sends.clear();
            if (this.activeSend != null) {
                pending.add(this.activeSend);
                this.activeSend = null;
            }
            this.pendingBytes = 0;
            webSocket = this.socket;
            handshake = this.connecting;
        }
        if (failure != null && webSocket != null) {
            webSocket.abort();
        }
        if (handshake != null && !handshake.isDone()) {
            handshake.cancel(true);
        }
        Throwable sendFailure =
                failure == null ? new IOException("WebSocket peer closed") : failure;
        pending.forEach(send -> send.result.completeExceptionally(sendFailure));
        this.opened.completeExceptionally(sendFailure);
        if (failure == null) {
            this.closed.complete(close);
        } else {
            this.closed.completeExceptionally(failure);
        }
    }

    private void requestNext(WebSocket webSocket) {
        synchronized (this.lock) {
            if (this.terminal) {
                return;
            }
        }
        webSocket.request(1);
    }

    private void deliver(WebSocket webSocket, String text, long generation) {
        try {
            this.receiverExecutor.execute(
                    () -> {
                        synchronized (this.lock) {
                            if (this.terminal) {
                                return;
                            }
                        }
                        try {
                            Objects.requireNonNull(this.receiver.apply(text), "receiver stage")
                                    .whenComplete(
                                            (ignored, failure) -> {
                                                if (failure != null) {
                                                    this.fail(failure);
                                                    return;
                                                }
                                                synchronized (this.lock) {
                                                    if (this.terminal
                                                            || this.receiveGeneration
                                                                    != generation) {
                                                        return;
                                                    }
                                                    cancel(this.receiveTimer);
                                                    this.receiveTimer = null;
                                                }
                                                this.requestNext(webSocket);
                                            });
                        } catch (RuntimeException failure) {
                            this.fail(failure);
                        }
                    });
        } catch (RuntimeException failure) {
            this.fail(failure);
        }
    }

    private static void cancel(ScheduledFuture<?> timer) {
        if (timer != null) {
            timer.cancel(false);
        }
    }

    private static long utf8Bytes(CharSequence value) {
        long bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                i++;
                if (i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("Text contains an unpaired surrogate");
                }
                bytes += 4;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("Text contains an unpaired surrogate");
            } else {
                bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
            }
        }
        return bytes;
    }

    private static final class Send {
        final String text;
        final long bytes;
        final Runnable requireCurrent;
        final CompletableFuture<Void> result = new CompletableFuture<>();

        Send(String text, long bytes, Runnable requireCurrent) {
            this.text = text;
            this.bytes = bytes;
            this.requireCurrent = requireCurrent;
        }
    }

    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            synchronized (lock) {
                if (terminal) {
                    webSocket.abort();
                    return;
                }
                socket = webSocket;
            }
            if (!subprotocol.equals(webSocket.getSubprotocol())) {
                fail(new IOException("WebSocket subprotocol was not negotiated"));
                return;
            }
            synchronized (lock) {
                if (terminal) {
                    return;
                }
                connected = true;
            }
            requestNext(webSocket);
            opened.complete(null);
            pump(); // A close may have been requested during the upgrade.
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                String complete = null;
                long generation;
                synchronized (lock) {
                    if (terminal) {
                        return RECEIVED;
                    }
                    if (incoming == null) {
                        incoming = new StringBuilder();
                        receiveParts = 0;
                        receiveGeneration++;
                        long expected = receiveGeneration;
                        // This also bounds time waiting for the application to accept the complete
                        // message.
                        receiveTimer =
                                scheduler.schedule(
                                        () ->
                                                terminate(
                                                        null,
                                                        new TimeoutException(
                                                                "WebSocket receive timed out"),
                                                        null,
                                                        expected),
                                        limits.receiveTimeout().toNanos(),
                                        TimeUnit.NANOSECONDS);
                    }
                    receiveParts++;
                    if (receiveParts > limits.maxReceiveParts()
                            || data.length() > limits.maxMessageBytes() - incoming.length()) {
                        throw new IOException("WebSocket receive assembly exceeds limit");
                    }
                    incoming.append(
                            data); // Own the characters before the JDK may reclaim its buffer.
                    generation = receiveGeneration;
                    if (last) {
                        if (utf8Bytes(incoming) > limits.maxMessageBytes()) {
                            throw new IOException("WebSocket received message exceeds byte limit");
                        }
                        complete = incoming.toString();
                        incoming = null;
                    }
                }
                if (complete == null) {
                    requestNext(
                            webSocket); // Demand counts fragments and control callbacks, not
                                        // messages.
                } else {
                    deliver(webSocket, complete, generation);
                }
            } catch (Exception failure) {
                fail(failure);
            }
            return RECEIVED;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            fail(new IOException("Binary WebSocket messages are unsupported"));
            return RECEIVED;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            requestNext(
                    webSocket); // The JDK sends the reciprocal pong; no buffer is retained here.
            return RECEIVED;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            requestNext(webSocket);
            return RECEIVED;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            terminate(new Close(statusCode, reason), null, null, -1);
            return RECEIVED; // Let the JDK reciprocate closure if output is still open.
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(error);
        }
    }
}
