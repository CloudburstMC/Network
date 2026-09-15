package org.cloudburstmc.netty.signaling;

import org.cloudburstmc.netty.signaling.control.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual application install/ACK guard and actual JDK queue; native and provider effects are fixtures. */
class ControlledDiagnosticQueuedSendTest {
    @Test void installedAckSurvivesQueueOnlyWhileItsOriginalNativeCaptureIsLive(@TempDir Path path) throws Exception {
        for (String change : List.of("none", "withdrawal", "expiry")) {
            var scheduler = new ScheduledThreadPoolExecutor(1); scheduler.setRemoveOnCancelPolicy(true);
            try (var h = new ControlledDiagnosticApplicationTest.Harness(path.resolve(change))) {
                h.ready(); h.now.set(h.document.expiresAt() - 1000);
                var socket = new Socket(); var bounds = new JdkWebSocketTransport.Limits(65536, 8, 4, 262144,
                        Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3));
                var transport = JdkQueuedSendFixture.connect(socket, bounds, scheduler);
                try {
                    transport.opened().toCompletableFuture().join();
                    var previous = transport.sendText("previous actual send still pending");
                    var delegate = h.exchange(); var constructed = new ArrayList<byte[]>();
                    var exchange = new ControlClientIo.Synchronization() {
                        public long deadlineMillis() { return delegate.deadlineMillis(); }
                        public Optional<byte[]> pendingHeartbeat() { return delegate.pendingHeartbeat(); }
                        public void requireCurrent() { delegate.requireCurrent(); }
                        public CompletionStage<ControlOperationResult> heartbeat(byte[] bytes) { throw new AssertionError("unguarded heartbeat"); }
                        public CompletionStage<ControlOperationResult> heartbeat(byte[] bytes, Runnable current) {
                            constructed.add(bytes.clone());
                            return transport.sendText(new String(bytes, StandardCharsets.UTF_8), current)
                                    .thenCompose(ignored -> delegate.heartbeat(bytes, current));
                        }
                        public CompletionStage<ControlSynchronizationResult> applied(ControlStateCodec.AppliedBasis basis) { throw new AssertionError("unguarded application"); }
                        public CompletionStage<ControlSynchronizationResult> applied(ControlStateCodec.AppliedBasis basis, Runnable current) { return delegate.applied(basis, current); }
                    };
                    var synchronization = h.app.synchronize(exchange).toCompletableFuture();
                    assertEquals(1, constructed.size()); assertEquals(1, socket.sent.size()); assertFalse(synchronization.isDone());
                    var body = ControlledProviderJson.parse(new String(constructed.get(0), StandardCharsets.UTF_8), 65536);
                    var acknowledgement = ControlDiagnosticHeartbeatCodec.decodeRequest(body.get("diagnosticAdmission").toString()).installed();
                    assertNotNull(acknowledgement); assertEquals(h.document.binding(), acknowledgement.binding());
                    // Isolate the diagnostic claim from other native claims when checking retained-intent policy.
                    var onlyDiagnostic = new com.google.gson.JsonObject(); onlyDiagnostic.addProperty("acceptingPlayers", false);
                    onlyDiagnostic.add("diagnosticAdmission", body.get("diagnosticAdmission").deepCopy());
                    byte[] diagnosticBytes = onlyDiagnostic.toString().getBytes(StandardCharsets.UTF_8);
                    var intent = ControlLifecycleCodec.intent(ControlledNativeOwnerApplicationTest.ORIGIN, "heartbeat", "fixture_host", 1, 99, "retained_diagnostic_ack", diagnosticBytes);
                    assertTrue(ControlledProviderApplication.requiresNativeCancellation(intent, diagnosticBytes));
                    if (change.equals("withdrawal")) h.nativeHost.withdrawDiagnosticPolicy(h.nativeHost.diagnostic).toCompletableFuture().join();
                    else if (change.equals("expiry")) h.now.set(h.document.expiresAt());
                    assertTrue(h.now.get() < exchange.deadlineMillis(), "installation expiry is independent of the original control deadline");
                    socket.finish(); previous.toCompletableFuture().join();
                    if (change.equals("none")) {
                        assertEquals(2, socket.sent.size()); assertArrayEquals(constructed.get(0), socket.sent.get(1).getBytes(StandardCharsets.UTF_8));
                        for (int round = 0; !socket.pending.isEmpty() && round < 6; round++) socket.finish();
                        synchronization.get(3, TimeUnit.SECONDS); assertNotNull(delegate.parent.delegate.applied);
                        assertFalse(socket.aborted); assertTrue(h.nativeHost.delegate.enabled);
                    } else {
                        assertEquals(1, socket.sent.size(), "No queued installed ACK may reach the socket");
                        assertThrows(ExecutionException.class, () -> synchronization.get(3, TimeUnit.SECONDS));
                        assertTrue(socket.aborted); assertTrue(socket.pending.isEmpty());
                        assertTrue(delegate.parent.delegate.bodies.isEmpty()); assertFalse(h.nativeHost.delegate.enabled);
                    }
                } finally { transport.abort(); }
                assertTrue(scheduler.getQueue().isEmpty());
            } finally { scheduler.shutdownNow(); assertTrue(scheduler.awaitTermination(3, TimeUnit.SECONDS)); }
        }
    }

    private static final class Socket implements WebSocket, WebSocket.Builder {
        final List<String> sent = new ArrayList<>(); final ArrayDeque<CompletableFuture<WebSocket>> pending = new ArrayDeque<>();
        boolean aborted;
        void finish() { pending.remove().complete(this); }
        public CompletableFuture<WebSocket> sendText(CharSequence text, boolean last) {
            assertTrue(last); assertTrue(pending.isEmpty()); assertFalse(aborted); sent.add(text.toString());
            var result = new CompletableFuture<WebSocket>(); pending.add(result); return result;
        }
        public CompletableFuture<WebSocket> buildAsync(URI uri, Listener listener) { listener.onOpen(this); return CompletableFuture.completedFuture(this); }
        public Builder header(String name, String value) { return this; }
        public Builder connectTimeout(Duration timeout) { return this; }
        public Builder subprotocols(String first, String... remaining) { return this; }
        public String getSubprotocol() { return "fixture"; }
        public void abort() { aborted = true; }
        public void request(long count) { }
        public boolean isInputClosed() { return aborted; }
        public boolean isOutputClosed() { return aborted; }
        public CompletableFuture<WebSocket> sendClose(int code, String reason) { return CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { throw new AssertionError(); }
        public CompletableFuture<WebSocket> sendPing(ByteBuffer data) { throw new AssertionError(); }
        public CompletableFuture<WebSocket> sendPong(ByteBuffer data) { throw new AssertionError(); }
    }
}
