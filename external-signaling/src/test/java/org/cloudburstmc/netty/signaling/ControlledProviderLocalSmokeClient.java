package org.cloudburstmc.netty.signaling;

import com.google.gson.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import org.cloudburstmc.netty.signaling.admission.*;
import org.cloudburstmc.netty.signaling.control.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.concurrent.*;

/** Private localhost fixture using the actual ProviderClient and native controlled listener. Never a gameplay test. */
public final class ControlledProviderLocalSmokeClient {
    private final JsonObject config;
    private final boolean runtimeCheck, rotationCheck, candidateCheck, ownerCheck, diagnosticCheck;
    private final long deadline;
    private final List<Host> hosts = new ArrayList<>();
    private final DefaultEventLoopGroup group = new DefaultEventLoopGroup(2);
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> { var thread = new Thread(r, "controlled-smoke-stop"); thread.setDaemon(true); return thread; });
    private Path trustStore;
    private final class Host {
        final String id, mode; final Path directory; final NativeProviderTransport nativeTransport; final ProviderClient client;
        final NativeCandidateSnapshot originalCandidates;
        final CompletableFuture<JsonObject> started; String writerSeen, basisSeen; boolean ready;
        CompletableFuture<JsonObject> candidateReadiness; int candidateStage; boolean ownerRemapped; int playerChildren; boolean diagnosticComplete;
        CompletableFuture<Long> runtimeReadiness; boolean runtimeReady; long allReadyAtNanos, initialSessionEpoch, nextRuntimeReadinessAtNanos;
        int runtimeRefreshAttempts;
        Host(JsonObject value) throws Exception {
            id = string(value, "hostId"); mode = string(value, "transport");
            if (!id.matches("[A-Za-z0-9_-]{1,128}") || !Set.of("https", "websocket").contains(mode)) throw new IllegalArgumentException("Invalid fixture host");
            directory = Path.of(string(config, "journalRoot")).resolve(id);
            var seed = value.getAsJsonObject("reportingSeed");
            var reporting = new ProviderControlConfiguration.ReportingSeed(number(seed, "generation"), number(seed, "appliedRevision"), string(seed, "reportedState"));
            var route = config.getAsJsonObject("routes"); var operations = new HashMap<String, URI>();
            route.getAsJsonObject("operations").entrySet().forEach(entry -> operations.put(entry.getKey(), URI.create(entry.getValue().getAsString())));
            var control = new ProviderControlConfiguration(new ControlClientCoordinator.Config(string(config, "origin"), uri(route, "prepare"), uri(route, "activate"), uri(route, "status"),
                    uri(route, "upgrade"), uri(route, "authority"), operations, mode, List.of("request-response"), 600000, 30000, 200, 10000, route.has("cancelIntent") ? uri(route, "cancelIntent") : null),
                    List.of(new ControlFrameCodec.VerificationKey(ControlFrameCodec.KeyFamily.PROVIDER_CONTROL, string(config, "providerKeyId"), ProviderCrypto.publicKey(config.getAsJsonObject("providerPublicKeyJwk")),
                            number(config, "validFrom"), number(config, "validUntil"))), reporting, ownerCheck || diagnosticCheck ? ProviderControlConfiguration.NativeOwnership.ISSUED : ProviderControlConfiguration.NativeOwnership.DISABLED,
                    ProviderControlConfiguration.CandidatePublication.DISABLED, diagnosticCheck ? ProviderControlConfiguration.Diagnostics.ENABLED : ProviderControlConfiguration.Diagnostics.DISABLED);
            var nativeConfig = config.getAsJsonObject("native"); String address = string(nativeConfig, "bindAddress");
            if (!Set.of("127.0.0.1", "::1", "::").contains(address)) throw new IllegalArgumentException("Only isolated loopback native fixture binds are allowed");
            int port = Math.toIntExact(number(value, "udpPort")); if (port < 1 || port > 65535) throw new IllegalArgumentException("Fixed UDP fixture port required");
            var bootstrap = new ServerBootstrap().group(group).childHandler(new ChannelInitializer<AdmittedNetherNetChildChannel>() {
                @Override protected void initChannel(AdmittedNetherNetChildChannel channel) { playerChildren++; channel.close(); }
            });
            originalCandidates = NativeCandidateSnapshot.hosts(address.equals("::")
                    ? List.of(new InetSocketAddress("127.0.0.1", port), new InetSocketAddress("::1", port)) : List.of(new InetSocketAddress(address, port)));
            var bind = new InetSocketAddress(InetAddress.getByName(address), port);
            var certificate = Path.of(string(nativeConfig, "certificate")); var privateKey = Path.of(string(nativeConfig, "privateKey"));
            nativeTransport = (candidateCheck || ownerCheck || diagnosticCheck
                    ? NativeProviderTransport.openControlledVersion2(bootstrap, bind, originalCandidates, certificate, privateKey, AdmissionGate.Limits.defaults())
                    : NativeProviderTransport.openControlled(bootstrap, bind, () -> originalCandidates.candidates().stream().map(NativeCandidateSnapshot.Candidate::endpoint).toList(),
                        certificate, privateKey, AdmissionGate.Limits.defaults()))
                    .toCompletableFuture().get(8, TimeUnit.SECONDS);
            ProviderStateStore store = null;
            try {
                if (nativeTransport.channel().isServing()) throw new IllegalStateException("Native listener started with admission enabled");
                store = new ProviderStateStore(directory);
                if (store.read().isEmpty()) {
                    var state = new JsonObject(); state.addProperty("provider", string(config, "origin")); state.addProperty("sequence", number(value, "sequence"));
                    state.addProperty("privateKey", string(value, "privateKeyPkcs8")); state.add("publicKeyJwk", value.get("publicKeyJwk").deepCopy()); state.add("ticketKeys", new JsonArray());
                    var registration = new JsonObject(); registration.addProperty("provider", string(config, "origin")); registration.addProperty("profile", "nxs-admission-v1");
                    registration.addProperty("instanceId", id); registration.addProperty("registrationId", string(value, "registrationId")); registration.addProperty("keyId", string(value, "keyId"));
                    registration.addProperty("leaseGeneration", reporting.generation()); state.add("registration", registration); store.write(state);
                }
                client = new ProviderClient(new ProviderClient.Configuration(URI.create(string(config, "origin")), "nxs-admission-v1", "Local controlled fixture",
                        ProviderClient.NEW_SERVICE, ProviderClient.ANONYMOUS_PROOF_OF_WORK, null, null, null, Map.of(), control), store, nativeTransport, () -> null,
                        () -> new ProviderClient.Health(true, true, 10, 0, "fixture", "fixture"), ignored -> { });
                emit("starting", id, mode, null); started = client.start();
            } catch (Throwable failure) {
                nativeTransport.close().toCompletableFuture().get(8, TimeUnit.SECONDS); if (store != null) store.close(); throw failure;
            }
        }
        void observe() throws Exception {
            Path journal = directory.resolve("control-session/provider-state.json");
            if (Files.isRegularFile(journal)) {
                var value = ControlledProviderJson.parse(Files.readString(journal), 196608);
                if (value.has("grant")) {
                    var writer = value.getAsJsonObject("writer"); String key = writer.toString();
                    if (!key.equals(writerSeen)) { writerSeen = key; var event = new JsonObject(); event.addProperty("sessionEpoch", number(writer, "sessionEpoch")); emit("writer_observed", id, mode, event); }
                }
            }
            Path application = directory.resolve("provider-state.json");
            if (Files.isRegularFile(application)) {
                var root = ControlledProviderJson.parse(Files.readString(application), 262144); var value = root.getAsJsonObject("controlApplication");
                if (value != null && value.has("basis")) {
                    var basis = ControlStateCodec.decodeAppliedBasis(string(value, "basis")); String digest = ControlStateCodec.appliedBasisDigest(basis);
                    if (!digest.equals(basisSeen)) { basisSeen = digest; var event = new JsonObject(); event.addProperty("basisSha256", digest); event.addProperty("desiredRevision", basis.desiredRevision()); emit("application_saved", id, mode, event); }
                }
            }
            if (!ready && started.isDone()) {
                started.get(1, TimeUnit.SECONDS);
                var event = readyFields("startup"); initialSessionEpoch = number(event, "sessionEpoch");
                emit("ready", id, mode, event); ready = true;
            }
            if (candidateReadiness != null && candidateReadiness.isDone()) {
                candidateReadiness.getNow(null); // A failed refresh is fatal, not a successful phase observation.
                var event = readyFields("candidate replacement");
                int expected = candidateStage == 1 ? 0 : originalCandidates.candidates().size();
                if (number(event, "candidateCount") != expected || number(event, "profileVersion") != 2
                        || number(event, "sessionEpoch") != initialSessionEpoch)
                    throw new IllegalStateException("Candidate readiness did not retain the expected profile and writer");
                emit(candidateStage == 1 ? "withdrawn_ready" : "restored_ready", id, mode, event);
                candidateStage++; candidateReadiness = null;
            }
            if (!runtimeReady && runtimeReadiness != null && runtimeReadiness.isDone()) {
                // Observe the actual returned result without stalling journal/native observation while it is pending.
                long completedEpoch = runtimeReadiness.getNow(null);
                boolean requiresRotation = rotationCheck && mode.equals("websocket");
                if (requiresRotation && (completedEpoch <= initialSessionEpoch || sessionEpoch() != completedEpoch)) {
                    repeatRuntimeReadiness(); return;
                }
                var event = readyFields("runtime readiness");
                // An old READY completion must not become evidence for a writer installed after that completion.
                if (requiresRotation && number(event, "sessionEpoch") != completedEpoch) {
                    repeatRuntimeReadiness(); return;
                }
                event.addProperty("elapsedSinceInitialReadyMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - allReadyAtNanos));
                if (rotationCheck) event.addProperty("runtimeRefreshAttempts", runtimeRefreshAttempts);
                emit("runtime_ready", id, mode, event); runtimeReady = true;
            }
        }
        void remapPendingOwner() throws Exception {
            if (!ownerCheck || ownerRemapped || ready) throw new IllegalStateException("Unexpected owner remap");
            var root = ControlledProviderJson.parse(Files.readString(directory.resolve("provider-state.json")), 262144);
            var journal = ControlledProviderJson.parse(Files.readString(directory.resolve("control-session/provider-state.json")), 196608);
            var pending = journal.getAsJsonObject("pending");
            if (pending == null || !pending.has("receipt") || !"committed".equals(ControlLifecycleCodec.decodeReceipt(string(pending, "receipt")).disposition())
                    || root.getAsJsonObject("controlApplication").has("nativeOwnerReceipt") || nativeTransport.channel().isServing())
                throw new IllegalStateException("Owner remap requires durable committed journal receipt before root owner save or native enable");
            var replacement = NativeCandidateSnapshot.hosts(originalCandidates.candidates().stream().map(candidate -> {
                var endpoint = candidate.endpoint();
                return new InetSocketAddress(endpoint.getAddress(), endpoint.getPort() == 65535 ? 65534 : endpoint.getPort() + 1);
            }).toList());
            if (!nativeTransport.replaceCandidates(replacement)) throw new IllegalStateException("Owner remap did not replace material");
            ownerRemapped = true;
            var event = new JsonObject(); event.addProperty("nativeServing", false); event.addProperty("committedReceiptRetained", true);
            event.addProperty("ownerSaved", false); event.addProperty("nativeIncarnation", nativeTransport.captureNativeIdentity().incarnation());
            event.addProperty("intentDigest", ControlLifecycleCodec.decodeReceipt(string(pending, "receipt")).intentDigest());
            emit("owner_remapped", id, mode, event);
        }
        void changeCandidates(boolean withdraw) {
            if (!candidateCheck || !ready || candidateReadiness != null || candidateStage != (withdraw ? 0 : 2))
                throw new IllegalStateException("Unexpected candidate transition command");
            if (!nativeTransport.replaceCandidates(withdraw ? NativeCandidateSnapshot.hosts(List.of()) : originalCandidates))
                throw new IllegalStateException("Candidate transition did not change material");
            candidateStage++;
            candidateReadiness = client.readiness();
        }
        void startRuntimeReadiness() {
            runtimeRefreshAttempts++;
            // Capture the writer at the actual READY completion, before a later replacement can alter the journal.
            runtimeReadiness = client.readiness().thenApply(ignored -> {
                try { return sessionEpoch(); }
                catch (Exception failure) { throw new CompletionException(failure); }
            });
        }
        void repeatRuntimeReadiness() {
            if (runtimeRefreshAttempts >= 12) throw new IllegalStateException("Socket did not rotate within the bounded successful readiness refreshes");
            runtimeReadiness = null;
            nextRuntimeReadinessAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        }
        long sessionEpoch() throws Exception {
            var journal = ControlledProviderJson.parse(Files.readString(directory.resolve("control-session/provider-state.json")), 196608);
            if (!journal.has("grant")) throw new IllegalStateException("Completed readiness has no durable writer grant");
            return number(journal.getAsJsonObject("writer"), "sessionEpoch");
        }
        JsonObject readyFields(String phase) throws Exception {
            var root = ControlledProviderJson.parse(Files.readString(directory.resolve("provider-state.json")), 262144); var applied = root.getAsJsonObject("controlApplication");
            var basis = ControlStateCodec.decodeAppliedBasis(string(applied, "basis"));
            boolean serving = nativeTransport.channel().isServing();
            var actual = nativeTransport.hostProfile().toCompletableFuture().get(3, TimeUnit.SECONDS);
            boolean profileMatches = basis.state().equals("serving") && actual.equals(applied.getAsJsonObject("profile"));
            if (!serving || !profileMatches) throw new IllegalStateException("Actual native application did not match completed ProviderClient " + phase);
            var event = new JsonObject(); event.addProperty("nativeServing", true); event.addProperty("nativeProfileMatches", true); event.addProperty("gameplay", false);
            event.addProperty("basisSha256", ControlStateCodec.appliedBasisDigest(basis)); event.addProperty("sessionEpoch", sessionEpoch());
            event.addProperty("candidateCount", actual.getAsJsonArray("candidates").size());
            event.addProperty("profileVersion", actual.has("version") ? number(actual, "version") : 0);
            if (ownerCheck) {
                var marker = applied.getAsJsonObject("nativeOwnerReceipt");
                if (marker == null) throw new IllegalStateException("READY has no durable owner receipt");
                var owner = CandidateLeaseCodec.decodeNativeOwner(marker.get("owner").toString());
                if (!owner.nativeIncarnation().equals(nativeTransport.captureNativeIdentity().incarnation()))
                    throw new IllegalStateException("READY owner does not match actual native lifetime");
                event.add("nativeOwnerReceipt", marker.deepCopy());
            }
            return event;
        }
        void diagnostic() throws Exception {
            if (!diagnosticCheck || !ready || diagnosticComplete) throw new IllegalStateException("Unexpected diagnostic command");
            client.readiness().get(15, TimeUnit.SECONDS);
            var root = ControlledProviderJson.parse(Files.readString(directory.resolve("provider-state.json")), 262144);
            var application = root.getAsJsonObject("controlApplication");
            var document = ControlDiagnosticInstallationCodec.decodeInstallation(application.get("diagnosticInstallation").toString());
            ControlDiagnosticInstallationCodec.verifyInstallation(document);
            var capture = nativeTransport.captureDiagnosticInstallation().orElseThrow(); capture.requireCurrent();
            if (!capture.binding().installationSha256().equals(document.binding().installationSha256())) throw new IllegalStateException("Native diagnostic binding differs");
            var nativeConfig = config.getAsJsonObject("native");
            var identity = NativeHostIdentity.load(Path.of(string(nativeConfig, "certificate")), Path.of(string(nativeConfig, "privateKey")));
            var catalog = document.answerCatalog();
            var answerCatalog = new org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAnswerCodec.Catalog(catalog.providerOrigin(), catalog.notBefore(), catalog.expiresAt(),
                    catalog.keys().stream().map(key -> new org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAnswerCodec.VerificationKey(
                            key.family(), key.keyId(), key.publicPointHex(), key.validFrom(), key.validUntil())).toList());
            var signer = new org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAnswerCodec.Signer("provider-diagnostic", "test-answer",
                    ProviderCrypto.privateKey(string(config, "diagnosticAnswerPrivateKeyPkcs8")));
            var result = DiagnosticApplicationPeerFixture.admittedAcrossRebind(nativeTransport, identity,
                    ControlledDiagnosticApplication.nativePolicy(document), signer, answerCatalog, capture::requireCurrent);
            capture.requireCurrent();
            if (playerChildren != 0 || nativeTransport.channel().creationAttempts() != 0 || !nativeTransport.pollEvents(32).isEmpty())
                throw new IllegalStateException("Diagnostic created player activity");
            var event = new JsonObject();
            event.add("installation", JsonParser.parseString(ControlDiagnosticInstallationCodec.encodeAcknowledgement(
                    new ControlDiagnosticInstallationCodec.Acknowledgement(document.binding()))));
            event.addProperty("policyExpiresAt", document.expiresAt()); event.addProperty("attemptExpiresAt", result.expiresAt());
            event.addProperty("attemptId", result.attemptId()); event.addProperty("offerDigestHex", result.offerDigestHex());
            event.addProperty("clientFingerprintHex", result.clientFingerprintHex()); event.addProperty("completionDigestHex", result.completionDigestHex());
            event.addProperty("completedAt", result.completedAt()); event.addProperty("sentFrames", result.sentFrames());
            event.addProperty("receivedFrames", result.receivedFrames()); event.addProperty("udpSent", result.udp().sent());
            event.addProperty("cleanupComplete", result.cleanupComplete()); event.addProperty("liveNativePeers", nativeTransport.channel().liveNativePeers());
            event.addProperty("playerChildren", playerChildren); event.addProperty("nativeServing", nativeTransport.channel().isServing());
            event.addProperty("gameplay", false); diagnosticComplete = true; emit("diagnostic_complete", id, mode, event);
        }
        void stop() throws Exception {
            client.stop().toCompletableFuture().get(12, TimeUnit.SECONDS);
            nativeTransport.close().toCompletableFuture().get(8, TimeUnit.SECONDS);
            if (nativeTransport.channel().isActive() || nativeTransport.channel().liveNativePeers() != 0) throw new IllegalStateException("Native fixture did not terminate");
            emit("stopped", id, mode, null);
        }
    }
    private ControlledProviderLocalSmokeClient(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 65536
                || !Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE).containsAll(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))) throw new IllegalArgumentException("Private fixture config required");
        config = ControlledProviderJson.parse(Files.readString(path), 65536);
        long executionMillis = config.has("executionMillis") ? number(config, "executionMillis") : 180_000;
        if (executionMillis < 180_000 || executionMillis > 420_000) throw new IllegalArgumentException("Invalid private fixture execution bound");
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(executionMillis);
        var requestedRuntimeCheck = config.get("runtimeCheck");
        if (requestedRuntimeCheck != null && (!requestedRuntimeCheck.isJsonPrimitive() || !requestedRuntimeCheck.getAsJsonPrimitive().isBoolean()))
            throw new IllegalArgumentException("runtimeCheck must be a boolean");
        var requestedRotationCheck = config.get("rotationCheck");
        if (requestedRotationCheck != null && (!requestedRotationCheck.isJsonPrimitive() || !requestedRotationCheck.getAsJsonPrimitive().isBoolean()))
            throw new IllegalArgumentException("rotationCheck must be a boolean");
        rotationCheck = requestedRotationCheck != null && requestedRotationCheck.getAsBoolean();
        runtimeCheck = rotationCheck || requestedRuntimeCheck != null && requestedRuntimeCheck.getAsBoolean();
        var requestedCandidateCheck = config.get("candidateCheck");
        if (requestedCandidateCheck != null && (!requestedCandidateCheck.isJsonPrimitive() || !requestedCandidateCheck.getAsJsonPrimitive().isBoolean()))
            throw new IllegalArgumentException("candidateCheck must be a boolean");
        candidateCheck = requestedCandidateCheck != null && requestedCandidateCheck.getAsBoolean();
        var requestedOwnerCheck = config.get("ownerCheck");
        if (requestedOwnerCheck != null && (!requestedOwnerCheck.isJsonPrimitive() || !requestedOwnerCheck.getAsJsonPrimitive().isBoolean()))
            throw new IllegalArgumentException("ownerCheck must be a boolean");
        ownerCheck = requestedOwnerCheck != null && requestedOwnerCheck.getAsBoolean();
        var requestedDiagnosticCheck = config.get("diagnosticCheck");
        if (requestedDiagnosticCheck != null && (!requestedDiagnosticCheck.isJsonPrimitive() || !requestedDiagnosticCheck.getAsJsonPrimitive().isBoolean()))
            throw new IllegalArgumentException("diagnosticCheck must be a boolean");
        diagnosticCheck = requestedDiagnosticCheck != null && requestedDiagnosticCheck.getAsBoolean();
        if (diagnosticCheck && (ownerCheck || candidateCheck || runtimeCheck)) throw new IllegalArgumentException("Diagnostic installation requires its separate scenario");
        if (ownerCheck && (candidateCheck || runtimeCheck)) throw new IllegalArgumentException("Owner faults require their separate scenario");
        if (candidateCheck && runtimeCheck) throw new IllegalArgumentException("Candidate and timed rotation scenarios are separate");
        if (!string(config, "origin").matches("https://127\\.0\\.0\\.1:[1-9][0-9]{0,4}")) throw new IllegalArgumentException("Explicit local HTTPS fixture required");
        var trust = KeyStore.getInstance("PKCS12"); trust.load(null, null);
        try (var certificate = Files.newInputStream(Path.of(string(config, "caCertificate")))) { trust.setCertificateEntry("fixture", CertificateFactory.getInstance("X.509").generateCertificate(certificate)); }
        Files.createDirectories(Path.of(string(config, "journalRoot")), java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        trustStore = Files.createTempFile(Path.of(string(config, "journalRoot")), "fixture-trust-", ".p12", java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        String password = UUID.randomUUID().toString(); try (var out = Files.newOutputStream(trustStore)) { trust.store(out, password.toCharArray()); }
        // This new process uses the public test CA through standard JDK TLS. Production code has no TLS override.
        System.setProperty("javax.net.ssl.trustStore", trustStore.toString()); System.setProperty("javax.net.ssl.trustStoreType", "PKCS12"); System.setProperty("javax.net.ssl.trustStorePassword", password);
    }
    private void run() throws Exception {
        var values = config.getAsJsonArray("hosts"); if (values.isEmpty() || values.size() > 2) throw new IllegalArgumentException("One or two controlled fixture hosts required");
        for (var value : values) hosts.add(new Host(value.getAsJsonObject()));
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var command = reader.submit(input::readLine);
        Long allReadyAt = null; boolean runtimeCheckStarted = false;
        while (System.nanoTime() < deadline) {
            for (var host : hosts) host.observe();
            if (runtimeCheck && !runtimeCheckStarted && hosts.stream().allMatch(host -> host.ready)) {
                long now = System.nanoTime();
                if (allReadyAt == null) allReadyAt = now;
                if (now - allReadyAt >= TimeUnit.SECONDS.toNanos(35)) {
                    runtimeCheckStarted = true;
                    // ProviderClient queues each refresh on its own application executor and returns immediately.
                    for (var host : hosts) {
                        host.allReadyAtNanos = allReadyAt;
                        host.startRuntimeReadiness();
                    }
                }
            }
            if (rotationCheck && runtimeCheckStarted) {
                long now = System.nanoTime();
                for (var host : hosts) {
                    if (host.mode.equals("websocket") && !host.runtimeReady && host.runtimeReadiness == null && now >= host.nextRuntimeReadinessAtNanos)
                        host.startRuntimeReadiness();
                }
            }
            if (command.isDone()) {
                String value = command.get();
                if ("stop".equals(value)) {
                    if (diagnosticCheck && hosts.stream().anyMatch(host -> !host.diagnosticComplete))
                        throw new IllegalStateException("Diagnostic scenario stopped before the signed exchange");
                    if (candidateCheck && hosts.stream().anyMatch(host -> host.candidateStage != 4))
                        throw new IllegalStateException("Candidate scenario stopped before restoration");
                    return;
                }
                if (diagnosticCheck && "diagnostic".equals(value)) {
                    for (var host : hosts) host.diagnostic();
                    command = reader.submit(input::readLine); continue;
                }
                if (ownerCheck && value != null && value.startsWith("owner-remap:")) {
                    String wanted = value.substring("owner-remap:".length());
                    hosts.stream().filter(host -> host.id.equals(wanted)).findFirst().orElseThrow().remapPendingOwner();
                    command = reader.submit(input::readLine); continue;
                }
                if (!candidateCheck || !("withdraw".equals(value) || "restore".equals(value)))
                    throw new IllegalStateException("Expected explicit local transition or stop command");
                for (var host : hosts) host.changeCandidates(value.equals("withdraw"));
                command = reader.submit(input::readLine);
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new TimeoutException("Controlled native smoke exceeded its fixed deadline");
    }
    public static void main(String[] args) {
        ControlledProviderLocalSmokeClient smoke = null; boolean failed = false;
        try {
            if (args.length != 2 || !args[0].equals("--config")) throw new IllegalArgumentException("Expected --config private-file");
            smoke = new ControlledProviderLocalSmokeClient(Path.of(args[1])); smoke.run();
        } catch (Throwable failure) { emit("failed", null, null, failureType(failure)); failed = true; }
        finally {
            if (smoke != null) {
                for (var host : smoke.hosts) try { host.stop(); } catch (Throwable failure) { emit("failed", host.id, host.mode, failureType(failure)); failed = true; }
                smoke.reader.shutdownNow(); smoke.group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
                try { Files.deleteIfExists(smoke.trustStore); } catch (Exception ignored) { }
            }
        }
        if (failed) System.exit(1);
    }
    private static JsonObject failureType(Throwable failure) { var value = new JsonObject(); value.addProperty("errorType", failure.getClass().getSimpleName()); return value; }
    private static synchronized void emit(String phase, String host, String mode, JsonObject fields) {
        var value = fields == null ? new JsonObject() : fields.deepCopy(); value.addProperty("phase", phase);
        if (host != null) value.addProperty("hostId", host); if (mode != null) value.addProperty("transport", mode); System.out.println(value); System.out.flush();
    }
    private static String string(JsonObject value, String name) { return ControlledProviderJson.string(value, name); }
    private static long number(JsonObject value, String name) { return ControlledProviderJson.number(value, name); }
    private static URI uri(JsonObject value, String name) { return URI.create(string(value, name)); }
}
