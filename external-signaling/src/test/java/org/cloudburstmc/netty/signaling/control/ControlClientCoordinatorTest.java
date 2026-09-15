package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signaling.ProviderCrypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ControlClientCoordinatorTest {
    static String resultWire(ControlLifecycleCodec.Receipt receipt) {
        return ControlResultCodec.encode(ControlResultCodec.create(receipt, "{}".getBytes(StandardCharsets.UTF_8)));
    }
    static final String ORIGIN = "https://provider.example";
    static final List<String> CAPS = List.of("addressed", "request-response");
    static final class Time implements ControlClientClock, ControlClientIo.Scheduler {
        long now = 1_000_000; final List<Timer> timers = new ArrayList<>();
        record Timer(long due, Runnable action, boolean[] cancelled) { }
        @Override public long nowMillis() { return now; }
        @Override public Task schedule(Runnable action, long delay) {
            assertTrue(delay >= 0); boolean[] cancelled = {false}; timers.add(new Timer(now + delay, action, cancelled)); return () -> cancelled[0] = true;
        }
        void advance(long millis) {
            now += millis;
            while (true) {
                Timer next = timers.stream().filter(t -> !t.cancelled[0] && t.due <= now).min(Comparator.comparingLong(Timer::due)).orElse(null);
                if (next == null) return; next.cancelled[0] = true; next.action.run();
            }
        }
        long nextDelay() { return timers.stream().filter(t -> !t.cancelled[0]).mapToLong(t -> t.due - now).min().orElseThrow(); }
    }
    static final class Journal implements ControlClientJournal {
        Snapshot value; boolean fail; Runnable afterCommit; final List<Snapshot> writes = new ArrayList<>();
        @Override public Optional<Snapshot> read() { return Optional.ofNullable(value); }
        @Override public void commit(Snapshot next) throws IOException {
            if (fail) throw new IOException("injected durable failure"); value = next; writes.add(next);
            var callback = afterCommit; afterCommit = null; if (callback != null) callback.run();
        }
        @Override public void close() { }
    }
    record Exchange(URI endpoint, ControlSessionCodec.Request request, CompletableFuture<ControlClientIo.HttpReply> reply) { }
    record Operation(URI endpoint, ControlHttpCodec.Request request, byte[] body, CompletableFuture<ControlClientIo.HttpReply> reply) { }
    record AuthorityExchange(URI endpoint, ControlAuthorityCodec.Request request, CompletableFuture<ControlClientIo.HttpReply> reply) { }

    static final class Harness implements ControlClientIo {
        final Time time; final Journal journal; final ControlClientJournal.Snapshot initial;
        final KeyPair provider = ProviderCrypto.generate();
        final ControlFrameCodec.VerificationKey providerKey;
        final Queue<Exchange> requests = new ArrayDeque<>(); final List<Operation> operations = new ArrayList<>();
        final List<FakeLink> links = new ArrayList<>(); final List<CompletableFuture<Void>> synchronizations = new ArrayList<>();
        final Queue<AuthorityExchange> authorityRequests = new ArrayDeque<>();
        final List<Synchronization> synchronizationExchanges = new ArrayList<>();
        final List<CompletionStage<ControlSynchronizationResult>> synchronizationResults = new ArrayList<>();
        Consumer<ControlFrameCodec.Frame> synchronizationFrames = ignored -> { };
        final List<ControlFrameDelivery> frameDeliveries = new ArrayList<>();
        final AtomicInteger ids = new AtomicInteger();
        Supplier<String> identifierSupplier = () -> "client_identifier_" + String.format("%016d", ids.incrementAndGet());
        ControlClientCoordinator client; ControlWriterFence writer; ControlClientJournal.Grant grant;
        boolean writerEnabled, absentSynchronization, keyAvailable = true; int bootstrapCalls, applicationFrames, authorityCalls, httpAuthorityCalls;
        boolean cancelNativeClaims; URI cancelRoute;
        boolean durableOutcomes; final List<CompletableFuture<Void>> outcomeAcks = new ArrayList<>();
        Runnable onOutcomeAck;
        java.util.function.Function<Synchronization, CompletionStage<ControlSynchronizationResult>> actualSynchronization;
        interface OutcomeAck { CompletionStage<Void> apply(ControlLifecycleCodec.Intent intent, byte[] body, ControlLifecycleCodec.Receipt receipt); }
        OutcomeAck actualOutcomeAck;
        boolean autoReady = true, nullSynchronizationResult, failApplicationDispatch;
        ControlStateCodec.AppliedBasis appliedBasis;
        ControlStateCodec.Summary sourceState;
        long sourceRevision, issuedAuthorityExpires;
        final Map<String, ControlLifecycleCodec.Receipt> receipts = new HashMap<>();
        final Map<String, ControlFrameCodec.VerificationKey> additionalKeys = new HashMap<>();
        final String initialTransport;
        Harness() throws Exception { this(new Journal(), new Time(), FileControlClientJournalTest.initial()); }
        Harness(String transport) throws Exception { this(new Journal(), new Time(), FileControlClientJournalTest.initial(), transport); }
        Harness(Journal journal, Time time, ControlClientJournal.Snapshot initial) throws Exception {
            this(journal, time, initial, "websocket");
        }
        Harness(Journal journal, Time time, ControlClientJournal.Snapshot initial, String transport) throws Exception {
            this.journal = journal; this.time = time; this.initial = initial; writer = initial.writer();
            initialTransport = transport;
            appliedBasis = new ControlStateCodec.AppliedBasis(initial.subject().generation(), 0, "draining", "disabled", null, null);
            sourceState = new ControlStateCodec.Summary(0, "draining", ControlStateCodec.appliedBasisDigest(appliedBasis));
            providerKey = new ControlFrameCodec.VerificationKey(ControlFrameCodec.KeyFamily.PROVIDER_CONTROL, "provider_control_test_01", provider.getPublic(), 0, 100_000_000);
            newClient();
        }
        void newClient() throws Exception { newClient(journal); }
        void newClient(ControlClientJournal store) throws Exception {
            var config = new ControlClientCoordinator.Config(ORIGIN, URI.create(ORIGIN + "/control/prepare"), URI.create(ORIGIN + "/control/activate"),
                    URI.create(ORIGIN + "/control/status"), URI.create("wss://provider.example/control/upgrade"), URI.create(ORIGIN + "/control/authority"),
                    Map.of("outcomes", URI.create(ORIGIN + "/signal/outcomes"), "heartbeat", URI.create(ORIGIN + "/signal/heartbeat"), "rotate", URI.create(ORIGIN + "/signal/rotate"),
                            "deregister", URI.create(ORIGIN + "/signal/deregister")),
                    initialTransport, initialTransport.equals("https") ? List.of("request-response") : CAPS, 21_600_000, 30_000, 200, 30_000, cancelRoute);
            client = new ControlClientCoordinator(store, initial, config, this, time, time, () -> 0.5,
                    () -> identifierSupplier.get(), key -> keyAvailable && key.equals(providerKey.keyId()) ? providerKey : additionalKeys.get(key));
        }
        @Override public CompletionStage<HttpReply> bootstrap(URI endpoint, ControlSessionCodec.Request request) {
            bootstrapCalls++;
            if (request.action().equals("prepare") || request.action().equals("activate")) {
                assertNotNull(journal.value.pendingBootstrap());
                assertEquals(ControlSessionCodec.requestIntentDigest(request), ControlSessionCodec.requestIntentDigest(ControlSessionCodec.decodeRequest(journal.value.pendingBootstrap().originalRequest())));
            }
            var reply = new CompletableFuture<HttpReply>(); requests.add(new Exchange(endpoint, request, reply)); return reply;
        }
        @Override public CompletionStage<HttpReply> operation(URI endpoint, ControlHttpCodec.Request request, byte[] body) {
            assertNotNull(journal.value.pending()); assertEquals(journal.value.pending().intent(), request.intent());
            assertArrayEquals(journal.value.pending().bodyBytes(), body);
            var reply = new CompletableFuture<HttpReply>(); operations.add(new Operation(endpoint, request, body.clone(), reply)); return reply;
        }
        @Override public boolean requiresNativeIntentCancellation(ControlLifecycleCodec.Intent intent, byte[] body) { return cancelNativeClaims && intent.operation().equals("heartbeat"); }
        @Override public boolean requiresOutcomeAcknowledgement() { return durableOutcomes; }
        @Override public CompletionStage<Void> acknowledgeCommittedOutcomes(ControlLifecycleCodec.Intent intent, byte[] body, ControlLifecycleCodec.Receipt receipt) {
            assertEquals("committed", journal.value.pending().receipt().disposition());
            assertEquals(intent, journal.value.pending().intent()); assertArrayEquals(body, journal.value.pending().bodyBytes());
            if (actualOutcomeAck != null) return actualOutcomeAck.apply(intent, body, receipt);
            var work = new CompletableFuture<Void>(); outcomeAcks.add(work);
            if (onOutcomeAck != null) onOutcomeAck.run();
            return work;
        }
        @Override public Link openWebSocket(URI endpoint, ControlSessionCodec.Request proof, Consumer<String> received) {
            var link = new FakeLink(proof, received); links.add(link); return link;
        }
        @Override public CompletionStage<HttpReply> authority(URI endpoint, ControlAuthorityCodec.Request request) {
            authorityCalls++; httpAuthorityCalls++; var reply = new CompletableFuture<HttpReply>(); authorityRequests.add(new AuthorityExchange(endpoint, request, reply)); return reply;
        }
        @Override public CompletionStage<ControlSynchronizationResult> synchronize(ControlWriterFence wanted, ControlClientJournal.Grant fixed, ControlAuthorityCodec.Verified authority, Synchronization exchange) {
            assertEquals(writer, wanted); assertEquals(grant, fixed);
            assertNotNull(journal.value.authorityFloor());
            assertEquals(authority.floor(), journal.value.authorityFloor().value());
            if (absentSynchronization) return null;
            synchronizationExchanges.add(exchange);
            if (actualSynchronization != null) {
                var outcome = actualSynchronization.apply(exchange); synchronizationResults.add(outcome); return outcome;
            }
            var result = new CompletableFuture<Void>(); synchronizations.add(result);
            var outcome = result.thenCompose(ignored -> nullSynchronizationResult ? CompletableFuture.<ControlSynchronizationResult>completedFuture(null) : exchange.applied(appliedBasis));
            synchronizationResults.add(outcome); return outcome;
        }
        @Override public void onSynchronizationFrame(ControlFrameDelivery delivery) {
            frameDeliveries.add(delivery); synchronizationFrames.accept(delivery.frame());
        }
        @Override public void onVerifiedFrame(ControlFrameDelivery delivery) {
            delivery.requireCurrent();
            if (failApplicationDispatch) throw new java.util.concurrent.RejectedExecutionException("injected application enqueue failure");
            frameDeliveries.add(delivery); applicationFrames++;
        }

        final class FakeLink implements Link {
            final ControlSessionCodec.Request upgrade; final Consumer<String> receiver;
            final CompletableFuture<Void> opened = CompletableFuture.completedFuture(null), closed = new CompletableFuture<>();
            final List<String> sent = new ArrayList<>(); int closeCalls, abortCalls;
            final List<String> appliedFrames = new ArrayList<>();
            final List<String> authorityWires = new ArrayList<>();
            int readinessFrames;
            long nextProviderSequence = 1;
            CompletableFuture<Void> appliedSend, lifecycleSend, authoritySend;
            FakeLink(ControlSessionCodec.Request request, Consumer<String> receiver) { this.upgrade = request; this.receiver = receiver; }
            @Override public CompletionStage<Void> opened() { return opened; }
            @Override public CompletionStage<?> closed() { return closed; }
            @Override public CompletionStage<Void> sendText(String wire) {
                if (ControlJson.parse(wire, ControlFrameCodec.MAX_FRAME_BYTES).has("kind")) {
                    var request = ControlAuthorityCodec.decodeRequest(wire); authorityWires.add(wire); authorityCalls++;
                    var reply = new CompletableFuture<HttpReply>();
                    authorityRequests.add(new AuthorityExchange(URI.create(ORIGIN + "/control/authority"), request, reply));
                    // This is a provider fixture convenience, not a production HTTP DTO: only raw
                    // successful proof bytes cross the exact fake socket's receive callback.
                    reply.whenComplete((value, failure) -> { if (failure == null && value.status() == 200) receiver.accept(value.body()); });
                    return authoritySend == null ? CompletableFuture.completedFuture(null) : authoritySend;
                }
                var frame = ControlFrameCodec.decode(wire);
                if (frame.type().equals("state.applied")) {
                    appliedFrames.add(wire);
                    if (autoReady) {
                        try { readyReply(ControlStateCodec.decodeAcknowledgement(new String(frame.payloadBytes(), StandardCharsets.UTF_8))); }
                        catch (Exception error) { throw new IllegalStateException(error); }
                    }
                    return appliedSend == null ? CompletableFuture.completedFuture(null) : appliedSend;
                }
                assertNotNull(journal.value.pending());
                var intent = ControlLifecycleCodec.decodeWsRequest(new String(frame.payloadBytes(), StandardCharsets.UTF_8));
                assertEquals(journal.value.pending().intent(), intent.intent()); assertArrayEquals(journal.value.pending().bodyBytes(), intent.bodyBytes());
                sent.add(wire); return lifecycleSend == null ? CompletableFuture.completedFuture(null) : lifecycleSend;
            }
            void readyReply(ControlStateCodec.Acknowledgement ack) throws Exception {
                incomingActual(this, writer, "session.ready", ControlStateCodec.encodeAcknowledgement(ack).getBytes(StandardCharsets.UTF_8), nextProviderSequence);
                readinessFrames++;
            }
            @Override public void close() { closeCalls++; closed.complete(null); }
            @Override public void abort() { abortCalls++; closed.completeExceptionally(new IOException("aborted")); }
            void challenge() throws Exception {
                var request = ControlSessionPayloadCodec.decodeRequest("upgrade", upgrade.payloadBytes());
                byte[] preparedBytes = ControlJson.base64(ControlJson.string(request, "preparedProof"), 16384, false);
                var prepared = ControlSessionPayloadCodec.decodeResponse("prepared", ControlSessionCodec.decodeResponse(new String(preparedBytes, StandardCharsets.UTF_8)).payloadBytes());
                JsonObject result = new JsonObject();
                for (String field : List.of("pendingSessionId", "transport", "capabilities", "clientNonce", "expiresAt", "sessionDurationMillis")) result.add(field, prepared.get(field));
                result.addProperty("connectionId", "physical_connection_" + links.indexOf(this));
                result.addProperty("preparedProofSha256", ControlFrameCodec.payloadDigest(preparedBytes));
                receiver.accept(responseWire(upgrade, "connection-challenge", result));
            }
        }

        String responseWire(ControlSessionCodec.Request request, String kind, JsonObject result) throws Exception {
            byte[] payload = result.toString().getBytes(StandardCharsets.UTF_8);
            long expires = time.now + 30_000;
            if (kind.equals("prepared") || kind.equals("connection-challenge")) expires = Math.min(expires, result.get("expiresAt").getAsLong());
            var response = new ControlSessionCodec.Response(1, kind, request.requestId(), ControlSessionCodec.requestIntentDigest(request), ORIGIN,
                    initial.subject().instanceId(), initial.subject().generation(), time.now, expires, ProviderCrypto.base64(payload), ControlFrameCodec.payloadDigest(payload),
                    new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, providerKey.keyId(), ""));
            return ControlSessionCodec.encode(ControlSessionCodec.sign(response, provider.getPrivate()));
        }
        JsonObject current() {
            JsonObject result = new JsonObject(); result.addProperty("query", "current-writer"); result.add("writer", writer.object()); result.addProperty("writerEnabled", writerEnabled);
            if (grant == null) {
                result.add("capabilities", ControlProof.capabilitiesObject(List.of()));
                for (String field : List.of("activatedAt", "sessionExpiresAt", "authoritySourceCheckedAt", "authorityExpiresAt")) result.add(field, JsonNull.INSTANCE);
            } else addGrant(result);
            return result;
        }
        void addGrant(JsonObject result) {
            result.add("capabilities", ControlProof.capabilitiesObject(grant.capabilities())); result.addProperty("activatedAt", grant.activatedAt());
            result.addProperty("sessionExpiresAt", grant.sessionExpiresAt()); result.addProperty("authoritySourceCheckedAt", grant.authoritySourceCheckedAt()); result.addProperty("authorityExpiresAt", grant.authorityExpiresAt());
        }
        Exchange next(String action) { Exchange next = requests.remove(); assertEquals(action, next.request.action()); return next; }
        void respondStatus() throws Exception {
            Exchange exchange = next("status"); var query = ControlSessionPayloadCodec.decodeRequest("status", exchange.request.payloadBytes());
            JsonObject result;
            if (query.get("query").getAsString().equals("current-writer")) {
                if (!exchange.request.authentication().keyId().equals(writer.keyId())) { exchange.reply.complete(new HttpReply(exchange.endpoint, "POST", exchange.endpoint, 401, "")); return; }
                result = current();
            } else {
                result = query.deepCopy(); var receipt = receipts.get(query.get("intentDigest").getAsString());
                result.add("receipt", receipt == null ? JsonNull.INSTANCE : ControlJson.parse(ControlLifecycleCodec.encodeReceipt(receipt), 4096));
            }
            exchange.reply.complete(new HttpReply(exchange.endpoint, "POST", exchange.endpoint, 200, responseWire(exchange.request, "status", result)));
        }
        void respondPrepare() throws Exception {
            Exchange exchange = next("prepare"); var request = ControlSessionPayloadCodec.decodeRequest("prepare", exchange.request.payloadBytes());
            JsonObject result = new JsonObject();
            for (String field : List.of("transport", "capabilities", "clientNonce", "expectedWriter", "sessionDurationMillis")) result.add(field, request.get(field));
            result.addProperty("pendingSessionId", "pending_session_" + ids.incrementAndGet());
            if (request.get("transport").getAsString().equals("websocket")) result.add("connectionId", JsonNull.INSTANCE);
            else result.addProperty("connectionId", "logical_connection_" + ids.incrementAndGet());
            result.addProperty("intentDigest", ControlSessionCodec.requestIntentDigest(exchange.request)); result.addProperty("preparedAt", time.now);
            result.addProperty("expiresAt", Math.min(time.now + 60000, request.get("intentExpiresAt").getAsLong()));
            exchange.reply.complete(new HttpReply(exchange.endpoint, "POST", exchange.endpoint, 200, responseWire(exchange.request, "prepared", result)));
        }
        JsonObject commitActivation(Exchange exchange) {
            var body = ControlSessionPayloadCodec.decodeRequest("activate", exchange.request.payloadBytes());
            var preparedResponse = ControlSessionCodec.decodeResponse(new String(ControlJson.base64(body.get("preparedProof").getAsString(), 16384, false), StandardCharsets.UTF_8));
            var prepared = ControlSessionPayloadCodec.decodeResponse("prepared", preparedResponse.payloadBytes());
            String connection = prepared.get("connectionId").isJsonNull() ? null : prepared.get("connectionId").getAsString();
            if (connection == null) {
                var response = ControlSessionCodec.decodeResponse(new String(ControlJson.base64(body.get("connectionProof").getAsString(), 16384, false), StandardCharsets.UTF_8));
                connection = ControlSessionPayloadCodec.decodeResponse("connection-challenge", response.payloadBytes()).get("connectionId").getAsString();
            }
            writer = new ControlWriterFence(prepared.get("transport").getAsString(), writer.sessionEpoch() + 1, prepared.get("pendingSessionId").getAsString(), connection, writer.keyId(), writer.machineKeyRevision());
            grant = new ControlClientJournal.Grant(ControlJson.strings(prepared, "capabilities"), time.now, time.now + prepared.get("sessionDurationMillis").getAsLong(), time.now, time.now + 300000);
            writerEnabled = true;
            JsonObject result = new JsonObject(); result.addProperty("intentDigest", ControlSessionCodec.requestIntentDigest(exchange.request)); result.add("writer", writer.object()); addGrant(result); return result;
        }
        void respondActivation() throws Exception {
            Exchange exchange = next("activate"); JsonObject result = commitActivation(exchange);
            exchange.reply.complete(new HttpReply(exchange.endpoint, "POST", exchange.endpoint, 200, responseWire(exchange.request, "activated", result)));
        }
        String authorityWire(AuthorityExchange exchange) throws Exception {
            var request = exchange.request();
            var source = new ControlAuthorityCodec.Source("p0", ++sourceRevision, sourceRevision, time.now, time.now + 300000);
            issuedAuthorityExpires = Math.min(request.authorityNotAfter(), Math.min(source.sourceExpiresAt(), grant.sessionExpiresAt()));
            var response = new ControlAuthorityCodec.Response(1, "authority-response", request.requestId(), ORIGIN, initial.subject().instanceId(), initial.subject().generation(),
                    writer, grant.capabilities(), time.now, Math.min(request.expiresAt(), time.now + 30000), ControlAuthorityCodec.requestDigest(request),
                    source, grant.sessionExpiresAt(), issuedAuthorityExpires, List.of("control.status"), sourceState,
                    new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, providerKey.keyId(), ""));
            return ControlAuthorityCodec.encode(ControlAuthorityCodec.sign(response, provider.getPrivate()));
        }
        void awaitAuthorityRequest() {
            // Only tests asking for eventual progress use this; rate and occupied-send tests inspect timers directly.
            for (int i = 0; authorityRequests.isEmpty() && i < 8; i++) time.advance(time.nextDelay());
            assertFalse(authorityRequests.isEmpty(), "No bounded authority retry made progress");
        }
        void respondAuthority() throws Exception {
            var exchange = authorityRequests.remove();
            exchange.reply.complete(new HttpReply(exchange.endpoint, "POST", exchange.endpoint, 200, authorityWire(exchange)));
        }
        void synchronizedReady() throws Exception {
            if (!authorityRequests.isEmpty()) respondAuthority();
            synchronizations.get(synchronizations.size() - 1).complete(null); assertTrue(client.ready());
        }
        void ready() throws Exception { client.start(); respondStatus(); respondPrepare(); links.get(links.size() - 1).challenge(); respondActivation(); synchronizedReady(); }
        ControlLifecycleCodec.Receipt receipt(String disposition) {
            var intent = journal.value.pending().intent(); boolean committed = disposition.equals("committed");
            return new ControlLifecycleCodec.Receipt(1, ControlLifecycleCodec.intentDigest(intent), intent.operation(), intent.instanceId(), intent.generation(), intent.sequence(), intent.idempotencyKey(),
                    disposition, committed ? time.now : null, committed ? 10L : null, committed ? null : disposition.equals("cancelled") ? "native-application-replaced" : "not_committed");
        }
        void incoming(FakeLink link, ControlWriterFence target, String type, byte[] payload, long sequence) throws Exception {
            incomingActual(link, target, type, payload, sequence + link.readinessFrames);
        }
        void incomingActual(FakeLink link, ControlWriterFence target, String type, byte[] payload, long sequence) throws Exception {
            link.nextProviderSequence = Math.max(link.nextProviderSequence, sequence + 1);
            var frame = new ControlFrameCodec.Frame(1, type, "provider_frame_0000001", sequence, ControlFrameCodec.Direction.PROVIDER_TO_HOST, ORIGIN,
                    initial.subject().instanceId(), initial.subject().generation(), target.sessionId(), target.sessionEpoch(), target.connectionId(), grant.capabilities(),
                    time.now, Math.min(time.now + 30000, issuedAuthorityExpires), ProviderCrypto.base64(payload), ControlFrameCodec.payloadDigest(payload),
                    new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, providerKey.keyId(), ""));
            link.receiver.accept(ControlFrameCodec.encode(ControlFrameCodec.sign(frame, ControlFrameCodec.KeyFamily.PROVIDER_CONTROL, provider.getPrivate())));
        }
    }

    @Test void unsafeApplicationReplayRequiresExplicitReconciliation() throws Exception {
        var h = new Harness(); h.client.start(); h.respondStatus(); h.respondPrepare(); h.links.get(0).challenge(); h.respondActivation(); h.respondAuthority();
        int requests = h.bootstrapCalls;
        h.synchronizations.get(0).completeExceptionally(new ControlClientIo.ReconciliationRequired("unknown old native claim"));
        assertEquals(ControlClientCoordinator.State.UNRESOLVED, h.client.state()); assertFalse(h.client.ready());
        h.time.advance(600000); assertEquals(requests, h.bootstrapCalls); assertTrue(h.operations.isEmpty());
    }
    @Test void queuedApplicationFrameCannotApplyAfterItsOriginalAuthorityChanges() throws Exception {
        for (String change : List.of("deadline", "signing-key", "close", "socket-loss", "replacement", "resync", "refreshed-authority")) {
            var h = new Harness(); h.ready();
            h.incoming(h.links.get(0), h.writer, "connectivity.report", "{}".getBytes(StandardCharsets.UTF_8), 1);
            assertEquals(1, h.frameDeliveries.size(), change);
            var delivery = h.frameDeliveries.get(0);
            assertEquals("connectivity.report", delivery.frame().type());
            switch (change) {
                case "deadline" -> h.time.now += 30_001;
                case "signing-key" -> h.keyAvailable = false;
                case "close" -> h.client.close();
                case "socket-loss" -> h.links.get(0).closed.complete(null);
                case "replacement" -> h.client.replaceTransport("https", List.of("request-response"));
                case "resync" -> h.client.synchronize();
                case "refreshed-authority" -> { h.client.synchronize(); h.synchronizedReady(); }
            }
            var applied = new AtomicInteger();
            Runnable queued = () -> { delivery.requireCurrent(); applied.incrementAndGet(); };
            assertThrows(IllegalStateException.class, queued::run, change);
            assertThrows(IllegalStateException.class, delivery::frame, change);
            assertEquals(0, applied.get(), change);
            h.client.close();
        }
    }

    @Test void queuedSynchronizationFrameBelongsOnlyToItsOriginalSynchronization() throws Exception {
        var h = new Harness(); h.client.start(); h.respondStatus(); h.respondPrepare();
        h.links.get(0).challenge(); h.respondActivation(); h.respondAuthority();
        h.incoming(h.links.get(0), h.writer, "state.desired", "{}".getBytes(StandardCharsets.UTF_8), 1);
        var delivery = h.frameDeliveries.get(0); delivery.requireCurrent();
        h.synchronizedReady();
        assertThrows(IllegalStateException.class, delivery::requireCurrent);
        h.client.synchronize(); h.respondAuthority();
        assertThrows(IllegalStateException.class, delivery::requireCurrent);
        h.client.close();
    }

    @Test void readinessRequiresVerifiedActivationAndExplicitSynchronizationAndExpiresWithoutPolling() throws Exception {
        var h = new Harness(); h.client.start(); h.respondStatus(); h.respondPrepare();
        assertEquals(ControlClientCoordinator.State.STANDBY, h.client.state()); assertFalse(h.client.ready());
        h.links.get(0).challenge(); assertEquals(ControlClientCoordinator.State.ACTIVATING, h.client.state()); assertFalse(h.client.ready());
        h.respondActivation(); assertEquals(ControlClientCoordinator.State.SYNCHRONIZING, h.client.state()); assertFalse(h.client.ready());
        h.synchronizedReady(); int calls = h.bootstrapCalls;
        h.time.advance(300000); assertFalse(h.client.ready()); assertEquals(ControlClientCoordinator.State.AUTHORITY_EXPIRED, h.client.state());
        assertEquals(calls, h.bootstrapCalls); assertEquals(0, h.applicationFrames);
        var missing = new Harness(); missing.absentSynchronization = true;
        missing.client.start(); missing.respondStatus(); missing.respondPrepare(); missing.links.get(0).challenge(); missing.respondActivation();
        missing.respondAuthority();
        assertFalse(missing.client.ready()); assertEquals(ControlClientCoordinator.State.AUTHORITY_EXPIRED, missing.client.state());
    }

    @Test void originalIntentPrecedesSendAndLocalSendCompletionOrUnknownReceiptCannotResolveIt() throws Exception {
        var h = new Harness(); h.ready(); byte[] body = " {\"heartbeat\": \"café\"}\n".getBytes(StandardCharsets.UTF_8);
        byte[] original = body.clone(); var pending = h.client.submit("heartbeat", body, false).toCompletableFuture(); Arrays.fill(body, (byte) 'x');
        assertArrayEquals(original, h.journal.value.pending().bodyBytes()); assertFalse(pending.isDone()); assertEquals(1, h.links.get(0).sent.size());
        h.incoming(h.links.get(0), h.writer, "lifecycle.receipt", resultWire(h.receipt("unknown")).getBytes(StandardCharsets.UTF_8), 1);
        assertNotNull(h.journal.value.pending()); assertFalse(pending.isDone());
        assertThrows(IllegalStateException.class, () -> h.client.submit("heartbeat", new byte[0], false));
        var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        h.client.reconcilePending(); h.respondStatus(); h.respondStatus();
        assertEquals(receipt, pending.join().receipt()); assertNull(h.journal.value.pending());
    }

    @Test void verifiedTerminalNoCommitResolvesReceiptOnlyAndRetainsSequenceBeforeCallbacks() throws Exception {
        for (String disposition : List.of("rejected", "expired")) {
            var h = new Harness(); h.ready();
            var first = h.client.submit("heartbeat", "[]".getBytes(StandardCharsets.UTF_8), true).toCompletableFuture();
            var receipt = h.receipt(disposition); var originalKey = h.journal.value.currentKey();
            List<CompletableFuture<ControlOperationResult>> next = new ArrayList<>();
            first.thenRun(() -> {
                assertNull(h.journal.value.pending()); assertEquals(1, h.journal.value.lastSequence());
                next.add(h.client.submit("heartbeat", "{}".getBytes(StandardCharsets.UTF_8), true).toCompletableFuture());
            });
            var operation = h.operations.get(0);
            // The wire codec requires an empty body for terminal no-commit outcomes.
            assertThrows(IllegalArgumentException.class, () -> ControlResultCodec.create(receipt, "{\"ignored\":true}".getBytes(StandardCharsets.UTF_8)));
            String wire = resultWire(receipt);
            operation.reply.complete(new ControlClientIo.HttpReply(operation.endpoint, "POST", operation.endpoint, 200, wire));
            assertEquals(receipt, first.join().receipt()); assertFalse(first.join().hasBody());
            assertEquals(originalKey, h.journal.value.currentKey()); assertEquals(2, h.journal.value.pending().intent().sequence());
            assertEquals(1, next.size()); assertFalse(next.get(0).isDone());
            h.client.close();
        }
    }

    @Test void rejectedDeregistrationKeepsClientLiveAndMayCloseInCompletionWithoutResuming() throws Exception {
        var h = new Harness(); h.ready();
        var future = h.client.submit("deregister", "[]".getBytes(StandardCharsets.UTF_8), false).toCompletableFuture();
        var receipt = h.receipt("rejected"); int[] writes = {0};
        future.thenRun(() -> {
            assertTrue(h.client.ready()); assertNull(h.journal.value.pending());
            try { h.client.close(); } catch (IOException e) { throw new IllegalStateException(e); }
            writes[0] = h.journal.writes.size(); h.journal.fail = true;
        });
        h.incoming(h.links.get(0), h.writer, "lifecycle.receipt", resultWire(receipt).getBytes(StandardCharsets.UTF_8), 1);
        assertEquals(receipt, future.join().receipt()); assertFalse(future.join().hasBody());
        assertEquals(ControlClientCoordinator.State.CLOSED, h.client.state()); assertEquals(writes[0], h.journal.writes.size());
        assertTrue(h.requests.isEmpty());
    }

    @Test void rejectedReceiptCannotResolveBeforeDurableRemovalAndCandidateIsNeverPromoted() throws Exception {
        var h = new Harness(); h.ready(); var selected = h.journal.value.currentKey();
        var future = h.client.rotateMachineKey().toCompletableFuture(); var original = h.journal.value.pending();
        var receipt = h.receipt("rejected"); h.journal.fail = true;
        h.incoming(h.links.get(0), h.writer, "lifecycle.receipt", resultWire(receipt).getBytes(StandardCharsets.UTF_8), 1);
        assertEquals(ControlClientCoordinator.State.UNRESOLVED, h.client.state());
        assertEquals(original, h.journal.value.pending()); assertEquals(selected, h.journal.value.currentKey());
        assertFalse(future.isDone() && !future.isCompletedExceptionally());
    }

    @Test void rejectedRotationUnderOriginalWriterDiscardsCandidateButDoesNotRotatePhysicalSession() throws Exception {
        var h = new Harness(); h.ready(); var selected = h.journal.value.currentKey(); var writer = h.writer;
        var future = h.client.rotateMachineKey().toCompletableFuture(); var receipt = h.receipt("rejected");
        h.incoming(h.links.get(0), writer, "lifecycle.receipt", resultWire(receipt).getBytes(StandardCharsets.UTF_8), 1);
        assertEquals(receipt, future.join().receipt()); assertFalse(future.join().hasBody());
        assertNull(h.journal.value.pending()); assertEquals(selected, h.journal.value.currentKey()); assertEquals(writer, h.journal.value.writer());
        assertTrue(h.client.ready()); assertEquals(0, h.links.get(0).closeCalls);
        h.client.close();
    }

    @Test void rejectedRotationReconcilesOnlyAfterPositiveOriginalKeyStatusAndSurvivesRestart() throws Exception {
        var h = new Harness(); h.ready(); var selected = h.journal.value.currentKey();
        h.client.rotateMachineKey(); var original = h.journal.value.pending();
        var receipt = h.receipt("rejected"); h.receipts.put(receipt.intentDigest(), receipt);
        h.client.close(); h.newClient(); h.client.start();
        var candidate = h.next("status"); assertEquals(original.candidate().keyId(), candidate.request.authentication().keyId());
        candidate.reply.complete(new ControlClientIo.HttpReply(candidate.endpoint, "POST", candidate.endpoint, 503, "{}"));
        assertEquals(original, h.journal.value.pending()); h.respondStatus(); assertEquals(original, h.journal.value.pending());
        h.respondStatus(); assertNull(h.journal.value.pending()); assertEquals(selected, h.journal.value.currentKey());
        assertEquals(1, h.journal.value.lastSequence());
        h.respondPrepare(); h.links.get(1).challenge(); h.respondActivation(); h.synchronizedReady();
        assertTrue(h.links.get(1).sent.isEmpty()); h.client.close();
    }

    @Test void rejectedReceiptUnderSelectedCandidateCannotContradictItsNoCommitMeaning() throws Exception {
        var h = new Harness(); h.ready(); h.client.rotateMachineKey(); var pending = h.journal.value.pending();
        var receipt = h.receipt("rejected"); h.receipts.put(receipt.intentDigest(), receipt);
        h.writer = new ControlWriterFence(h.writer.transport(), h.writer.sessionEpoch(), h.writer.sessionId(), h.writer.connectionId(),
                pending.candidate().keyId(), h.writer.machineKeyRevision() + 1);
        h.client.reconcilePending(); h.respondStatus(); h.respondStatus();
        assertEquals(ControlClientCoordinator.State.UNRESOLVED, h.client.state());
        assertEquals(pending, h.journal.value.pending()); assertNotEquals(pending.candidate(), h.journal.value.currentKey());
    }

    @Test void rejectedStrongReceiptCompletionMayCloseReplaceOrSubmitWithoutStaleContinuation() throws Exception {
        for (String action : List.of("close", "replace", "submit")) {
            var h = new Harness(); h.ready();
            var future = h.client.submit("heartbeat", new byte[0], true).toCompletableFuture();
            var receipt = h.receipt("rejected"); h.receipts.put(receipt.intentDigest(), receipt);
            int[] writes = {0};
            future.thenRun(() -> {
                if (action.equals("close")) {
                    try { h.client.close(); } catch (IOException e) { throw new IllegalStateException(e); }
                    writes[0] = h.journal.writes.size(); h.journal.fail = true;
                } else if (action.equals("replace")) h.client.replaceTransport("https", List.of("request-response"));
                else h.client.submit("heartbeat", "{}".getBytes(StandardCharsets.UTF_8), true);
            });
            h.client.reconcilePending(); h.respondStatus(); h.respondStatus();
            assertEquals(receipt, future.join().receipt()); assertFalse(future.join().hasBody());
            if (action.equals("close")) {
                assertEquals(ControlClientCoordinator.State.CLOSED, h.client.state()); assertEquals(writes[0], h.journal.writes.size());
                assertTrue(h.requests.isEmpty());
            } else if (action.equals("replace")) {
                assertEquals(1, h.requests.size()); h.respondPrepare(); h.respondActivation(); h.synchronizedReady();
                assertEquals("https", h.writer.transport());
            } else { h.synchronizedReady(); assertEquals(2, h.journal.value.pending().intent().sequence()); assertEquals(2, h.operations.size()); }
            h.client.close();
        }
    }

    @Test void carrierExpiryAndUnknownStatusRetryOriginalIntentWithFreshCarrierInsteadOfTerminalExpiry() throws Exception {
        var h = new Harness(); h.ready();
        var future = h.client.submit("heartbeat", " []\n".getBytes(StandardCharsets.UTF_8), true).toCompletableFuture();
        var original = h.journal.value.pending(); var expired = h.operations.get(0); var late = h.receipt("rejected");
        h.time.advance(30_000); assertFalse(future.isDone()); assertEquals(original, h.journal.value.pending());
        // A rejected reply arriving after its carrier deadline remains untrusted delivery.
        expired.reply.complete(new ControlClientIo.HttpReply(expired.endpoint, "POST", expired.endpoint, 200, resultWire(late)));
        assertEquals(original, h.journal.value.pending());
        h.time.advance(h.time.nextDelay()); h.respondStatus(); h.respondStatus();
        h.respondPrepare(); h.links.get(1).challenge(); h.respondActivation(); h.synchronizedReady();
        assertFalse(future.isDone()); assertEquals(original.intent(), h.journal.value.pending().intent());
        var retried = h.operations.get(1); assertEquals(original.intent(), retried.request.intent());
        assertArrayEquals(original.bodyBytes(), retried.body); assertTrue(retried.request.expiresAt() > expired.request.expiresAt());
        var unknown = h.receipt("unknown"); retried.reply.complete(new ControlClientIo.HttpReply(retried.endpoint, "POST", retried.endpoint, 200, resultWire(unknown)));
        assertFalse(future.isDone()); assertEquals(original.intent(), h.journal.value.pending().intent());
        h.client.close();
    }

    @Test void oneOffHttpsPreservesWebSocketWriterAndPersistentFallbackActivatesNewEpoch() throws Exception {
        var h = new Harness(); h.ready(); var before = h.writer; byte[] body = (" ".repeat(45057)).getBytes(StandardCharsets.UTF_8);
        var future = h.client.submit("heartbeat", body, false).toCompletableFuture(); assertEquals(1, h.operations.size()); assertTrue(h.links.get(0).sent.isEmpty());
        var request = h.operations.get(0); assertEquals(before.connectionId(), request.request.connectionId()); assertEquals("websocket", request.request.writerTransport());
        var receipt = h.receipt("committed"); request.reply.complete(new ControlClientIo.HttpReply(request.endpoint, "POST", request.endpoint, 200, resultWire(receipt)));
        assertEquals(receipt, future.join().receipt()); assertEquals(before, h.client.snapshot().writer());
        h.client.replaceTransport("https", List.of("request-response")); assertFalse(h.client.ready()); h.respondPrepare(); h.respondActivation(); h.synchronizedReady();
        assertEquals(before.sessionEpoch() + 1, h.writer.sessionEpoch()); assertEquals("https", h.writer.transport()); assertEquals(1, h.links.get(0).closeCalls);
    }

    @Test void lostOperationAckSurvivesRestartAndRetriesExactIntentBodyUnderReplacementWriter() throws Exception {
        var h = new Harness(); h.ready(); h.client.submit("heartbeat", " { }\n".getBytes(StandardCharsets.UTF_8), true);
        var original = h.journal.value.pending(); h.client.close(); h.newClient(); h.client.start(); h.respondStatus(); h.respondStatus();
        h.respondPrepare(); h.links.get(1).challenge(); h.respondActivation(); h.synchronizedReady();
        assertEquals(original.intent(), h.journal.value.pending().intent()); assertArrayEquals(original.bodyBytes(), h.journal.value.pending().bodyBytes());
        var sent = ControlFrameCodec.decode(h.links.get(1).sent.get(0));
        assertEquals(original.intent(), ControlLifecycleCodec.decodeWsRequest(new String(sent.payloadBytes(), StandardCharsets.UTF_8)).intent());
        assertEquals(2, sent.sessionEpoch());
    }

    @Test void lostRotationAckUsesDurablyKnownCandidateAndPromotesItOnlyWithReceiptAndStrongCurrentWriter() throws Exception {
        var h = new Harness(); h.ready(); h.client.rotateMachineKey(); var pending = h.journal.value.pending();
        assertNotNull(pending.candidate()); var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        h.writer = new ControlWriterFence(h.writer.transport(), h.writer.sessionEpoch(), h.writer.sessionId(), h.writer.connectionId(), pending.candidate().keyId(), h.writer.machineKeyRevision() + 1);
        h.client.close(); h.newClient(); h.client.start();
        assertEquals(pending.candidate().keyId(), h.requests.peek().request.authentication().keyId());
        h.respondStatus(); assertEquals(pending.candidate(), h.journal.value.pending().candidate()); assertNotEquals(pending.candidate(), h.journal.value.currentKey());
        h.respondStatus(); assertNull(h.journal.value.pending()); assertEquals(pending.candidate(), h.journal.value.currentKey()); assertEquals(2, h.journal.value.writer().machineKeyRevision());
    }

    @Test void genericCandidateUnavailabilityTriesTheOldKeyAndRetriesOnlyAfterPositiveStatus() throws Exception {
        var h = new Harness(); h.ready(); var oldKey = h.journal.value.currentKey();
        var result = h.client.rotateMachineKey().toCompletableFuture(); var original = h.journal.value.pending();
        h.client.reconcilePending(); var candidate = h.next("status");
        assertEquals(original.candidate().keyId(), candidate.request.authentication().keyId());
        candidate.reply.complete(new ControlClientIo.HttpReply(candidate.endpoint, "POST", candidate.endpoint, 503, "{}"));
        assertEquals(1, h.requests.size()); assertEquals(oldKey.keyId(), h.requests.peek().request.authentication().keyId());
        assertEquals(oldKey, h.journal.value.currentKey()); assertEquals(original, h.journal.value.pending());
        h.respondStatus(); h.respondStatus(); h.synchronizedReady();
        assertFalse(result.isDone()); assertEquals(oldKey, h.journal.value.currentKey()); assertEquals(original, h.journal.value.pending());
        assertEquals(1, h.links.size()); assertEquals(2, h.links.get(0).sent.size());
        var retried = ControlLifecycleCodec.decodeWsRequest(new String(ControlFrameCodec.decode(h.links.get(0).sent.get(1)).payloadBytes(), StandardCharsets.UTF_8));
        assertEquals(original.intent(), retried.intent()); assertArrayEquals(original.bodyBytes(), retried.bodyBytes());
    }

    @Test void bothCandidateAndOldKeyUnavailableBackOffWithoutGuessingACommitOrLoopingBetweenKeys() throws Exception {
        var h = new Harness(); h.ready(); h.client.rotateMachineKey(); var original = h.journal.value.pending();
        var oldKey = h.journal.value.currentKey(); h.client.reconcilePending(); int started = h.bootstrapCalls;
        for (String expected : List.of(original.candidate().keyId(), oldKey.keyId())) {
            var query = h.next("status"); assertEquals(expected, query.request.authentication().keyId());
            query.reply.complete(new ControlClientIo.HttpReply(query.endpoint, "POST", query.endpoint, 503, "{}"));
        }
        assertEquals(started + 1, h.bootstrapCalls); assertTrue(h.requests.isEmpty());
        assertEquals(ControlClientCoordinator.State.BACKOFF, h.client.state()); assertFalse(h.client.ready());
        assertEquals(original, h.journal.value.pending()); assertEquals(oldKey, h.journal.value.currentKey());
        h.time.advance(h.time.nextDelay());
        assertEquals(1, h.requests.size()); assertEquals(original.candidate().keyId(), h.requests.peek().request.authentication().keyId());
    }

    @Test void candidateTimeoutMakesOneIndependentOldKeyAttemptAndIgnoresItsLateReply() throws Exception {
        var h = new Harness(); h.ready(); h.client.rotateMachineKey(); var original = h.journal.value.pending();
        h.client.reconcilePending(); var late = h.next("status"); int before = h.bootstrapCalls;
        h.time.advance(30000);
        assertEquals(before + 1, h.bootstrapCalls); assertEquals(1, h.requests.size());
        assertEquals(h.journal.value.currentKey().keyId(), h.requests.peek().request.authentication().keyId());
        late.reply.complete(new ControlClientIo.HttpReply(late.endpoint, "POST", late.endpoint, 503, "{}"));
        assertEquals(before + 1, h.bootstrapCalls); assertEquals(original, h.journal.value.pending());
        h.respondStatus(); h.respondStatus(); assertFalse(h.client.ready()); assertEquals(original, h.journal.value.pending());
    }

    @Test void candidateTransportFailureDoesNotMakeAnUnverifiedOldKeyResponseAuthoritative() throws Exception {
        var h = new Harness(); h.ready(); h.client.rotateMachineKey(); var original = h.journal.value.pending();
        h.client.reconcilePending(); h.next("status").reply.completeExceptionally(new IOException("unavailable"));
        var old = h.next("status");
        old.reply.complete(new ControlClientIo.HttpReply(old.endpoint, "POST", old.endpoint, 200, "{}"));
        assertEquals(ControlClientCoordinator.State.BACKOFF, h.client.state()); assertFalse(h.client.ready());
        assertEquals(original, h.journal.value.pending()); assertTrue(h.requests.isEmpty());
    }

    @Test void committedDeregisterReceiptStopsBeforeCallbacksAndRetainsItsTerminalIntent() throws Exception {
        var h = new Harness(); h.ready(); var link = h.links.get(0);
        var result = h.client.submit("deregister", "{}".getBytes(StandardCharsets.UTF_8), false).toCompletableFuture();
        var receipt = h.receipt("committed"); int calls = h.bootstrapCalls, authorities = h.authorityCalls;
        var callback = result.thenRun(() -> assertThrows(IllegalStateException.class, () -> h.client.replaceTransport("https", List.of("request-response"))));
        h.incoming(link, h.writer, "lifecycle.receipt", resultWire(receipt).getBytes(StandardCharsets.UTF_8), 1);
        callback.join(); assertEquals(receipt, result.join().receipt()); assertFalse(result.join().hasBody());
        assertEquals(ControlClientCoordinator.State.DEREGISTERED, h.client.state()); assertFalse(h.client.ready());
        assertEquals(receipt, h.journal.value.pending().receipt()); assertEquals("deregister", h.journal.value.pending().intent().operation());
        assertEquals(1, link.closeCalls); assertThrows(IllegalStateException.class, h.client::reconcilePending);
        h.time.advance(30_000_000);
        assertEquals(calls, h.bootstrapCalls); assertEquals(authorities, h.authorityCalls); assertTrue(h.requests.isEmpty());
    }

    @Test void lostDeregisterAcknowledgementReconcilesTerminallyWithoutPreparingADisabledWriter() throws Exception {
        var h = new Harness(); h.ready(); h.client.submit("deregister", "{}".getBytes(StandardCharsets.UTF_8), false);
        var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt); h.writerEnabled = false;
        h.client.close(); h.newClient(); int calls = h.bootstrapCalls, authorities = h.authorityCalls;
        h.client.start(); h.respondStatus(); h.respondStatus();
        assertEquals(ControlClientCoordinator.State.DEREGISTERED, h.client.state()); assertEquals(receipt, h.journal.value.pending().receipt());
        assertEquals(calls + 2, h.bootstrapCalls); assertEquals(authorities, h.authorityCalls); assertTrue(h.requests.isEmpty());
        h.time.advance(30_000_000); assertEquals(calls + 2, h.bootstrapCalls);
    }

    @Test void terminalDeregisterReceiptSurvivesFileJournalRestartWithoutAnyNetworkEffect(@TempDir Path directory) throws Exception {
        var h = new Harness(); h.ready();
        h.client.submit("deregister", "{}".getBytes(StandardCharsets.UTF_8), true);
        var receipt = h.receipt("committed"); var operation = h.operations.get(0);
        operation.reply.complete(new ControlClientIo.HttpReply(operation.endpoint, "POST", operation.endpoint, 200, resultWire(receipt)));
        var terminal = h.journal.value; assertEquals(receipt, terminal.pending().receipt()); h.client.close();
        try (var persisted = new FileControlClientJournal(directory)) { persisted.commit(terminal); }
        int calls = h.bootstrapCalls, authorities = h.authorityCalls, sends = h.operations.size(), links = h.links.size();
        try (var reopened = new FileControlClientJournal(directory)) {
            h.newClient(reopened); h.client.start();
            assertEquals(ControlClientCoordinator.State.DEREGISTERED, h.client.state()); assertEquals(terminal, h.client.snapshot());
            assertEquals(receipt, reopened.read().orElseThrow().pending().receipt());
            h.time.advance(30_000_000);
            assertEquals(calls, h.bootstrapCalls); assertEquals(authorities, h.authorityCalls);
            assertEquals(sends, h.operations.size()); assertEquals(links, h.links.size()); assertTrue(h.requests.isEmpty());
            h.client.close();
        }
    }

    @Test void uncommittedDeregisterReceiptDoesNotCreateTerminalState() throws Exception {
        var h = new Harness(); h.ready(); var result = h.client.submit("deregister", "{}".getBytes(StandardCharsets.UTF_8), false).toCompletableFuture();
        var unknown = h.receipt("unknown");
        h.incoming(h.links.get(0), h.writer, "lifecycle.receipt", resultWire(unknown).getBytes(StandardCharsets.UTF_8), 1);
        assertEquals(unknown, h.journal.value.pending().receipt()); assertFalse(result.isDone()); assertTrue(h.client.ready());
        assertThrows(IllegalStateException.class, () -> h.client.submit("heartbeat", new byte[0], false));
    }

    @Test void terminalReceiptJournalFailureNeverAcknowledgesSuccessfulLocalDeregistration() throws Exception {
        var h = new Harness(); h.ready(); var result = h.client.submit("deregister", "{}".getBytes(StandardCharsets.UTF_8), false).toCompletableFuture();
        var receipt = h.receipt("committed"); h.journal.fail = true;
        h.incoming(h.links.get(0), h.writer, "lifecycle.receipt", resultWire(receipt).getBytes(StandardCharsets.UTF_8), 1);
        assertEquals(ControlClientCoordinator.State.UNRESOLVED, h.client.state()); assertTrue(result.isCompletedExceptionally());
        assertNull(h.journal.value.pending().receipt()); assertTrue(h.requests.isEmpty());
    }

    @Test void activationLostAckRestoresOriginalGrantAndReplacesLostPhysicalConnection() throws Exception {
        var h = new Harness(); h.client.start(); h.respondStatus(); h.respondPrepare(); h.links.get(0).challenge();
        Exchange activation = h.next("activate"); h.commitActivation(activation); long expiry = h.grant.sessionExpiresAt();
        assertEquals("activate", ControlSessionCodec.decodeRequest(h.journal.value.pendingBootstrap().originalRequest()).action());
        h.client.close(); h.newClient(); h.client.start(); h.respondStatus();
        assertEquals(1, h.journal.value.writer().sessionEpoch()); assertEquals(expiry, h.journal.value.grant().sessionExpiresAt());
        assertEquals("prepare", h.requests.peek().request.action()); assertFalse(h.client.ready());
    }

    @Test void statusReadBeforeActivationExpiryCannotResolveAmbiguityWhenItsReplyArrivesAfterExpiry() throws Exception {
        var h = new Harness(); h.client.start(); h.respondStatus(); h.respondPrepare(); h.links.get(0).challenge();
        Exchange activation = h.next("activate"); var original = h.journal.value.pendingBootstrap();
        h.time.advance(25000); h.client.reconcilePending(); Exchange staleStatus = h.next("status");
        String oldWriterReply = h.responseWire(staleStatus.request, "status", h.current());
        h.time.advance(3000); h.commitActivation(activation); // Competing commit after the read, still before proof expiry.
        h.time.advance(7000);
        staleStatus.reply.complete(new ControlClientIo.HttpReply(staleStatus.endpoint, "POST", staleStatus.endpoint, 200, oldWriterReply));
        assertEquals(original, h.journal.value.pendingBootstrap()); assertTrue(h.requests.isEmpty()); assertFalse(h.client.ready());
        h.time.advance(24999); assertTrue(h.requests.isEmpty()); h.time.advance(1);
        assertTrue(h.requests.peek().request.sentAt() >= 1_060_000); h.respondStatus();
        assertEquals(1, h.journal.value.writer().sessionEpoch());
        // The still-live candidate can now synchronize the exact committed physical connection.
        assertEquals(ControlClientCoordinator.State.SYNCHRONIZING, h.client.state());
    }

    @Test void liveMachineRotationResynchronizesSameEpochSocketAndSequenceWithoutTouchingGameplay() throws Exception {
        var h = new Harness(); h.ready(); var physical = h.writer; var link = h.links.get(0);
        var completed = h.client.rotateMachineKey().toCompletableFuture(); var pending = h.journal.value.pending();
        var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        h.writer = new ControlWriterFence(physical.transport(), physical.sessionEpoch(), physical.sessionId(), physical.connectionId(), pending.candidate().keyId(), physical.machineKeyRevision() + 1);
        h.incoming(link, physical, "lifecycle.receipt", resultWire(receipt).getBytes(StandardCharsets.UTF_8), 1);
        assertFalse(h.client.ready()); h.respondStatus(); h.respondStatus(); h.synchronizedReady(); assertEquals(receipt, completed.join().receipt());
        assertEquals(physical.sessionEpoch(), h.client.snapshot().writer().sessionEpoch()); assertEquals(1, h.links.size()); assertEquals(0, link.closeCalls + link.abortCalls);
        h.client.submit("heartbeat", new byte[0], false); var next = ControlFrameCodec.decode(link.sent.get(1));
        assertEquals(2 + link.appliedFrames.size(), next.sequence()); assertEquals(pending.candidate().keyId(), next.authentication().keyId());
    }

    @Test void scheduledRotationIsBeforeFixedGrantExpiryAndOldControlCallbacksStayFenced() throws Exception {
        var h = new Harness(); h.ready(); long fixedExpiry = h.grant.sessionExpiresAt();
        // Equal configured jitter chooses a 180s lead, independently of the five-minute source expiry.
        h.time.advance(fixedExpiry - h.time.now - 180000);
        assertEquals(ControlClientCoordinator.State.PREPARING, h.client.state());
        assertEquals("prepare", h.requests.peek().request.action()); assertTrue(h.time.now < fixedExpiry);
        assertEquals(0, h.links.get(0).closeCalls + h.links.get(0).abortCalls);
    }

    @Test void replacedPhysicalCallbacksCannotDispatchAndFirstReconnectIsJittered() throws Exception {
        var h = new Harness(); h.ready(); var old = h.links.get(0); var previous = h.writer;
        h.client.replaceTransport("websocket", CAPS); h.respondPrepare(); h.links.get(1).challenge(); h.respondActivation(); h.synchronizedReady();
        h.incoming(old, previous, "connectivity.report", "{}".getBytes(StandardCharsets.UTF_8), 1); assertEquals(0, h.applicationFrames); assertTrue(h.client.ready());
        h.links.get(1).abort(); assertEquals(ControlClientCoordinator.State.BACKOFF, h.client.state()); assertEquals(150, h.time.nextDelay());
        h.time.advance(149); assertTrue(h.requests.isEmpty()); h.time.advance(1); assertEquals("status", h.requests.peek().request.action());
    }

    @Test void commitFailureCannotSendAndRedirectedHttpsReceiptCannotClearIntent() throws Exception {
        var h = new Harness(); h.ready(); h.journal.fail = true;
        assertThrows(IllegalStateException.class, () -> h.client.submit("heartbeat", new byte[0], false)); assertTrue(h.links.get(0).sent.isEmpty());
        assertEquals(ControlClientCoordinator.State.UNRESOLVED, h.client.state());
        assertEquals(1, h.links.get(0).abortCalls);
        h.incoming(h.links.get(0), h.writer, "session.reconnect", "{}".getBytes(StandardCharsets.UTF_8), 1);
        assertEquals(ControlClientCoordinator.State.UNRESOLVED, h.client.state()); assertTrue(h.requests.isEmpty());
        var redirected = new Harness(); redirected.ready(); var future = redirected.client.submit("heartbeat", new byte[0], true).toCompletableFuture();
        var operation = redirected.operations.get(0); operation.reply.complete(new ControlClientIo.HttpReply(operation.endpoint, "POST", URI.create("https://other.example/receipt"), 200,
                resultWire(redirected.receipt("committed"))));
        assertNotNull(redirected.journal.value.pending()); assertFalse(future.isDone()); assertFalse(redirected.client.ready());
    }

    @Test void reconciledReceiptCompletionMayCloseWithoutResumingOldStatusContinuation() throws Exception {
        var h = new Harness(); h.ready();
        var result = h.client.submit("heartbeat", new byte[0], true).toCompletableFuture();
        var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        int[] writesAtClose = {0};
        result.thenRun(() -> {
            try { h.client.close(); } catch (IOException e) { throw new IllegalStateException(e); }
            writesAtClose[0] = h.journal.writes.size();
            h.journal.fail = true; // A real file journal rejects writes after close.
        });
        h.client.reconcilePending(); h.respondStatus(); h.respondStatus();
        assertEquals(receipt, result.join().receipt());
        assertEquals(ControlClientCoordinator.State.CLOSED, h.client.state());
        assertEquals(writesAtClose[0], h.journal.writes.size());
        assertTrue(h.requests.isEmpty());
    }

    @Test void reconciledReceiptCompletionMayReplaceTransportWithOnlyOnePrepare() throws Exception {
        var h = new Harness(); h.ready();
        var result = h.client.submit("heartbeat", new byte[0], true).toCompletableFuture();
        var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        result.thenRun(() -> h.client.replaceTransport("https", List.of("request-response")));
        h.client.reconcilePending(); h.respondStatus(); h.respondStatus();
        assertEquals(receipt, result.join().receipt());
        assertEquals(1, h.requests.size());
        h.respondPrepare(); h.respondActivation(); h.synchronizedReady();
        assertEquals("https", h.writer.transport());
    }

    @Test void reconciledReceiptCompletionMaySubmitTheNextIntentWithoutLosingItsFuture() throws Exception {
        var h = new Harness(); h.ready();
        var first = h.client.submit("heartbeat", new byte[0], true).toCompletableFuture();
        var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        List<CompletableFuture<ControlOperationResult>> next = new ArrayList<>();
        first.thenRun(() -> next.add(h.client.submit("heartbeat", new byte[]{42}, true).toCompletableFuture()));
        h.client.reconcilePending(); h.respondStatus(); h.respondStatus(); h.synchronizedReady();
        assertEquals(1, next.size()); assertFalse(next.get(0).isDone());
        assertEquals(2, h.journal.value.pending().intent().sequence());
        var operation = h.operations.get(1); var secondReceipt = h.receipt("committed");
        operation.reply.complete(new ControlClientIo.HttpReply(operation.endpoint, "POST", operation.endpoint, 200, resultWire(secondReceipt)));
        assertEquals(secondReceipt, next.get(0).join().receipt());
    }

    @Test void reconcilingAnUnactivatedPrepareClosesEachSupersededStandbyLink() throws Exception {
        var h = new Harness(); h.client.start(); h.respondStatus(); h.respondPrepare();
        var original = h.journal.value.pendingBootstrap();
        for (int i = 0; i < 3; i++) {
            var previous = h.links.get(i);
            h.client.reconcilePending(); h.respondStatus(); h.respondPrepare();
            assertEquals(1, previous.abortCalls);
            assertEquals(original, h.journal.value.pendingBootstrap());
            previous.challenge(); // A superseded connection cannot activate its old candidate.
            assertTrue(h.requests.isEmpty());
            assertEquals(ControlClientCoordinator.State.STANDBY, h.client.state());
        }
        h.links.get(3).challenge(); h.respondActivation(); h.synchronizedReady();
    }

    @Test void failureToPersistAReceivedReceiptCompletesCallerExceptionallyAndKeepsTheBarrier() throws Exception {
        var h = new Harness(); h.ready();
        var result = h.client.submit("heartbeat", new byte[0], true).toCompletableFuture();
        var operation = h.operations.get(0); var receipt = h.receipt("committed");
        h.journal.fail = true;
        operation.reply.complete(new ControlClientIo.HttpReply(operation.endpoint, "POST", operation.endpoint, 200, resultWire(receipt)));
        assertEquals(ControlClientCoordinator.State.UNRESOLVED, h.client.state());
        assertNotNull(h.journal.value.pending());
        assertTrue(result.isCompletedExceptionally());
        assertTrue(h.requests.isEmpty());
    }

    @Test void sharedClockContinuesAgingAfterWallRollbackAndDoesNotLoseSubmillisecondElapsedTime() {
        long[] wall = {1000}, nanos = {0}; var clock = ControlClientClock.monotonic(() -> wall[0], () -> nanos[0]);
        assertEquals(1000, clock.nowMillis()); nanos[0] += 500_000; assertEquals(1000, clock.nowMillis()); nanos[0] += 500_000; assertEquals(1001, clock.nowMillis());
        wall[0] = 10_000; assertEquals(10_000, clock.nowMillis()); wall[0] = 0; nanos[0] += 300_000_000_000L; assertEquals(310_000, clock.nowMillis());
        nanos[0] -= 1_000_000_000; assertEquals(310_000, clock.nowMillis());
    }
}
