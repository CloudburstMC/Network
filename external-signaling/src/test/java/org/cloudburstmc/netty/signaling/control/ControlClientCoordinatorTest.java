package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signaling.ProviderCrypto;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ControlClientCoordinatorTest {
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
        final AtomicInteger ids = new AtomicInteger();
        ControlClientCoordinator client; ControlWriterFence writer; ControlClientJournal.Grant grant;
        boolean writerEnabled, absentSynchronization, keyAvailable = true; int bootstrapCalls, applicationFrames, authorityCalls;
        long sourceRevision, issuedAuthorityExpires;
        final Map<String, ControlLifecycleCodec.Receipt> receipts = new HashMap<>();
        Harness() throws Exception { this(new Journal(), new Time(), FileControlClientJournalTest.initial()); }
        Harness(Journal journal, Time time, ControlClientJournal.Snapshot initial) throws Exception {
            this.journal = journal; this.time = time; this.initial = initial; writer = initial.writer();
            providerKey = new ControlFrameCodec.VerificationKey(ControlFrameCodec.KeyFamily.PROVIDER_CONTROL, "provider_control_test_01", provider.getPublic(), 0, 100_000_000);
            newClient();
        }
        void newClient() throws Exception {
            var config = new ControlClientCoordinator.Config(ORIGIN, URI.create(ORIGIN + "/control/prepare"), URI.create(ORIGIN + "/control/activate"),
                    URI.create(ORIGIN + "/control/status"), URI.create("wss://provider.example/control/upgrade"), URI.create(ORIGIN + "/control/authority"),
                    Map.of("heartbeat", URI.create(ORIGIN + "/signal/heartbeat"), "rotate", URI.create(ORIGIN + "/signal/rotate")),
                    "websocket", CAPS, 21_600_000, 30_000, 200, 30_000);
            client = new ControlClientCoordinator(journal, initial, config, this, time, time, () -> 0.5,
                    () -> "client_identifier_" + String.format("%016d", ids.incrementAndGet()), key -> keyAvailable && key.equals(providerKey.keyId()) ? providerKey : null);
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
        @Override public Link openWebSocket(URI endpoint, ControlSessionCodec.Request proof, Consumer<String> received) {
            var link = new FakeLink(proof, received); links.add(link); return link;
        }
        @Override public CompletionStage<HttpReply> authority(URI endpoint, ControlAuthorityCodec.Request request) {
            authorityCalls++; var reply = new CompletableFuture<HttpReply>(); authorityRequests.add(new AuthorityExchange(endpoint, request, reply)); return reply;
        }
        @Override public CompletionStage<Void> synchronize(ControlWriterFence wanted, ControlClientJournal.Grant fixed, ControlAuthorityCodec.Verified authority) {
            assertEquals(writer, wanted); assertEquals(grant, fixed);
            assertNotNull(journal.value.authorityFloor());
            assertEquals(authority.floor(), journal.value.authorityFloor().value());
            if (absentSynchronization) return null;
            var result = new CompletableFuture<Void>(); synchronizations.add(result); return result;
        }
        @Override public void onSynchronizationFrame(ControlFrameCodec.Frame frame) { }
        @Override public void onVerifiedFrame(ControlFrameCodec.Frame frame) { applicationFrames++; }

        final class FakeLink implements Link {
            final ControlSessionCodec.Request upgrade; final Consumer<String> receiver;
            final CompletableFuture<Void> opened = CompletableFuture.completedFuture(null), closed = new CompletableFuture<>();
            final List<String> sent = new ArrayList<>(); int closeCalls, abortCalls;
            FakeLink(ControlSessionCodec.Request request, Consumer<String> receiver) { this.upgrade = request; this.receiver = receiver; }
            @Override public CompletionStage<Void> opened() { return opened; }
            @Override public CompletionStage<?> closed() { return closed; }
            @Override public CompletionStage<Void> sendText(String wire) {
                var frame = ControlFrameCodec.decode(wire); assertNotNull(journal.value.pending());
                var intent = ControlLifecycleCodec.decodeWsRequest(new String(frame.payloadBytes(), StandardCharsets.UTF_8));
                assertEquals(journal.value.pending().intent(), intent.intent()); assertArrayEquals(journal.value.pending().bodyBytes(), intent.bodyBytes());
                sent.add(wire); return CompletableFuture.completedFuture(null);
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
                    source, grant.sessionExpiresAt(), issuedAuthorityExpires, List.of("control.status"),
                    new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, providerKey.keyId(), ""));
            return ControlAuthorityCodec.encode(ControlAuthorityCodec.sign(response, provider.getPrivate()));
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
                    disposition, committed ? time.now : null, committed ? 10L : null, committed ? null : "not_committed");
        }
        void incoming(FakeLink link, ControlWriterFence target, String type, byte[] payload, long sequence) throws Exception {
            var frame = new ControlFrameCodec.Frame(1, type, "provider_frame_0000001", sequence, ControlFrameCodec.Direction.PROVIDER_TO_HOST, ORIGIN,
                    initial.subject().instanceId(), initial.subject().generation(), target.sessionId(), target.sessionEpoch(), target.connectionId(), grant.capabilities(),
                    time.now, Math.min(time.now + 30000, issuedAuthorityExpires), ProviderCrypto.base64(payload), ControlFrameCodec.payloadDigest(payload),
                    new ControlFrameCodec.Authentication(ControlFrameCodec.SCHEME, providerKey.keyId(), ""));
            link.receiver.accept(ControlFrameCodec.encode(ControlFrameCodec.sign(frame, ControlFrameCodec.KeyFamily.PROVIDER_CONTROL, provider.getPrivate())));
        }
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
        h.incoming(h.links.get(0), h.writer, "lifecycle.receipt", ControlLifecycleCodec.encodeReceipt(h.receipt("unknown")).getBytes(StandardCharsets.UTF_8), 1);
        assertNotNull(h.journal.value.pending()); assertFalse(pending.isDone());
        assertThrows(IllegalStateException.class, () -> h.client.submit("heartbeat", new byte[0], false));
        var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        h.client.reconcilePending(); h.respondStatus(); h.respondStatus();
        assertEquals(receipt, pending.join()); assertNull(h.journal.value.pending());
    }

    @Test void oneOffHttpsPreservesWebSocketWriterAndPersistentFallbackActivatesNewEpoch() throws Exception {
        var h = new Harness(); h.ready(); var before = h.writer; byte[] body = (" ".repeat(45057)).getBytes(StandardCharsets.UTF_8);
        var future = h.client.submit("heartbeat", body, false).toCompletableFuture(); assertEquals(1, h.operations.size()); assertTrue(h.links.get(0).sent.isEmpty());
        var request = h.operations.get(0); assertEquals(before.connectionId(), request.request.connectionId()); assertEquals("websocket", request.request.writerTransport());
        var receipt = h.receipt("committed"); request.reply.complete(new ControlClientIo.HttpReply(request.endpoint, "POST", request.endpoint, 200, ControlLifecycleCodec.encodeReceipt(receipt)));
        assertEquals(receipt, future.join()); assertEquals(before, h.client.snapshot().writer());
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
        h.incoming(link, physical, "lifecycle.receipt", ControlLifecycleCodec.encodeReceipt(receipt).getBytes(StandardCharsets.UTF_8), 1);
        assertFalse(h.client.ready()); h.respondStatus(); h.respondStatus(); h.synchronizedReady(); assertEquals(receipt, completed.join());
        assertEquals(physical.sessionEpoch(), h.client.snapshot().writer().sessionEpoch()); assertEquals(1, h.links.size()); assertEquals(0, link.closeCalls + link.abortCalls);
        h.client.submit("heartbeat", new byte[0], false); var next = ControlFrameCodec.decode(link.sent.get(1));
        assertEquals(2, next.sequence()); assertEquals(pending.candidate().keyId(), next.authentication().keyId());
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
                ControlLifecycleCodec.encodeReceipt(redirected.receipt("committed"))));
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
        assertEquals(receipt, result.join());
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
        assertEquals(receipt, result.join());
        assertEquals(1, h.requests.size());
        h.respondPrepare(); h.respondActivation(); h.synchronizedReady();
        assertEquals("https", h.writer.transport());
    }

    @Test void reconciledReceiptCompletionMaySubmitTheNextIntentWithoutLosingItsFuture() throws Exception {
        var h = new Harness(); h.ready();
        var first = h.client.submit("heartbeat", new byte[0], true).toCompletableFuture();
        var receipt = h.receipt("committed"); h.receipts.put(receipt.intentDigest(), receipt);
        List<CompletableFuture<ControlLifecycleCodec.Receipt>> next = new ArrayList<>();
        first.thenRun(() -> next.add(h.client.submit("heartbeat", new byte[]{42}, true).toCompletableFuture()));
        h.client.reconcilePending(); h.respondStatus(); h.respondStatus(); h.synchronizedReady();
        assertEquals(1, next.size()); assertFalse(next.get(0).isDone());
        assertEquals(2, h.journal.value.pending().intent().sequence());
        var operation = h.operations.get(1); var secondReceipt = h.receipt("committed");
        operation.reply.complete(new ControlClientIo.HttpReply(operation.endpoint, "POST", operation.endpoint, 200, ControlLifecycleCodec.encodeReceipt(secondReceipt)));
        assertEquals(secondReceipt, next.get(0).join());
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
        operation.reply.complete(new ControlClientIo.HttpReply(operation.endpoint, "POST", operation.endpoint, 200, ControlLifecycleCodec.encodeReceipt(receipt)));
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
