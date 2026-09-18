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

package org.cloudburstmc.netty.signaling.admission;

import com.google.gson.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.util.concurrent.ScheduledFuture;
import org.cloudburstmc.netty.signaling.ProviderTransport;
import org.cloudburstmc.netty.signaling.diagnostic.*;
import org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection;
import org.cloudburstmc.netty.signaling.provider.connectivity.MaintainedCandidatePublisher;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Profile adapter: background key/profile lifecycle only; no per-join metadata input is used.
 */
public final class NativeProviderTransport implements ProviderTransport {
    public static final String CAPABILITY = "nethernet.stateless-admission.v1";

    private record Epoch(String id, long notBefore, long retireAfter) {
    }

    private final NativeAdmissionServerChannel channel;
    private final StatelessAdmissionValidator validator;
    private final String incarnation;
    private final Supplier<List<InetSocketAddress>> advertisedAddresses;
    private final ScheduledFuture<?> retireTask;
    private final boolean fixedCandidates;
    private NativeCandidateSnapshot candidateSnapshot;
    private List<NativeCandidateSnapshot.Candidate> ordinaryCandidateOrder = List.of();
    private volatile long candidateGeneration = 1;
    private long maintainedMappingRevision;
    private MaintainedCandidatePublisher candidatePublisher;
    private MaintainedCandidatePublisher.Publication candidatePublication;
    private ScheduledFuture<?> candidateTask;
    private volatile long publicationVersion;
    private boolean connectivityFeedbackPending;
    private volatile List<Epoch> epochs = List.of();
    private volatile boolean draining;
    private volatile boolean closed;
    private NativeDiagnosticHostGate diagnosticGate;
    private DiagnosticHostPolicy diagnosticPolicy;
    private volatile long diagnosticMutation;
    private boolean diagnosticConfiguring;
    private final long diagnosticAnchorNanos = System.nanoTime(), diagnosticAnchorMillis = System.currentTimeMillis();
    private final AtomicLong diagnosticTime = new AtomicLong(diagnosticAnchorMillis);

    private long diagnosticNow() {
        long elapsed = System.nanoTime() - diagnosticAnchorNanos;
        if (elapsed < 0) throw new IllegalStateException("Diagnostic monotonic clock rollback");
        long now = Math.max(System.currentTimeMillis(), Math.addExact(diagnosticAnchorMillis, elapsed / 1_000_000));
        return diagnosticTime.accumulateAndGet(now, Math::max);
    }
    private NativeProviderTransport(NativeAdmissionServerChannel channel, StatelessAdmissionValidator validator,
                                    String incarnation, Supplier<List<InetSocketAddress>> advertisedAddresses,
                                    NativeCandidateSnapshot candidates) {
        this.channel = channel;
        this.validator = validator;
        this.incarnation = incarnation;
        this.advertisedAddresses = advertisedAddresses;
        this.fixedCandidates = candidates != null;
        this.candidateSnapshot = candidates;
        retireTask = channel.eventLoop()
                .scheduleWithFixedDelay(() -> validator.retireKeys(System.currentTimeMillis()), 1, 1, TimeUnit.SECONDS);
    }

    /**
     * The caller provisions the host PEM identity before opening/registration. No client state is accepted.
     */
    public static CompletionStage<NativeProviderTransport> open(ServerBootstrap bootstrap, InetSocketAddress bind,
                                                                Path certificate, Path privateKey,
                                                                AdmissionGate.Limits limits) {
        return open(bootstrap, bind, bind, certificate, privateKey, limits);
    }

    /**
     * Explicit advertised candidate supports wildcard/local binds and operator-provisioned NAT mappings.
     */
    public static CompletionStage<NativeProviderTransport> open(ServerBootstrap bootstrap, InetSocketAddress bind,
                                                                InetSocketAddress advertised, Path certificate,
                                                                Path privateKey, AdmissionGate.Limits limits) {
        return open(bootstrap, bind, () -> List.of(advertised), certificate, privateKey, limits);
    }

    /**
     * Refreshes the endpoint snapshot on background profile publication; packet handling stays native.
     */
    public static CompletionStage<NativeProviderTransport> open(ServerBootstrap bootstrap, InetSocketAddress bind,
                                                                Supplier<List<InetSocketAddress>> advertised,
                                                                Path certificate, Path privateKey,
                                                                AdmissionGate.Limits limits) {
        return open(bootstrap, bind, advertised, certificate, privateKey, limits, null);
    }

    /** Explicit maintained publication, on the already bound gameplay mux. No STUN server discovery occurs here. */
    public static CompletionStage<NativeProviderTransport> openMaintained(ServerBootstrap bootstrap,
            EndpointSelection selection, Map<EndpointSelection.Family, InetSocketAddress> numericStunServers,
            Path certificate, Path privateKey, AdmissionGate.Limits limits) {
        return openMaintained(bootstrap, selection, numericStunServers, certificate, privateKey, limits, false);
    }

    /** Explicit assistance uses bounded per-join discovery instead of background STUN warming. */
    public static CompletionStage<NativeProviderTransport> openMaintained(ServerBootstrap bootstrap,
            EndpointSelection selection, Map<EndpointSelection.Family, InetSocketAddress> numericStunServers,
            Path certificate, Path privateKey, AdmissionGate.Limits limits, boolean assistedJoins) {
        Objects.requireNonNull(selection);
        var servers = Map.copyOf(numericStunServers);
        var direct = NativeCandidateSnapshot.hosts(selection.candidates().stream().map(EndpointSelection.Candidate::endpoint).toList());
        return open(bootstrap, selection.bind(), null, certificate, privateKey, limits, direct).thenCompose(transport -> {
            var controller = selection.configured() || assistedJoins
                    ? CompletableFuture.<org.cloudburstmc.netty.signaling.provider.connectivity.EndpointConnectivityController>completedFuture(null)
                    : transport.channel.enableConnectivity(selection, servers, Duration.ofMinutes(5));
            return controller.thenApply(value -> {
                synchronized (transport) {
                    if (transport.closed || !transport.channel.isActive()) throw new IllegalStateException("Native listener closed");
                    transport.candidatePublisher = new MaintainedCandidatePublisher(selection, value);
                    transport.candidatePublisher.configureStunServers(servers);
                    transport.refreshMaintained();
                    transport.candidateTask = transport.channel.eventLoop().scheduleWithFixedDelay(
                            transport::refreshMaintained, 1, 1, TimeUnit.SECONDS);
                }
                return transport;
            }).whenComplete((value, failure) -> { if (failure != null) transport.close(); });
        });
    }

    private static CompletionStage<NativeProviderTransport> open(ServerBootstrap bootstrap, InetSocketAddress bind,
            Supplier<List<InetSocketAddress>> advertised, Path certificate, Path privateKey,
            AdmissionGate.Limits limits, NativeCandidateSnapshot candidates) {
        CompletableFuture<NativeProviderTransport> result = new CompletableFuture<>();
        try {
            if (candidates == null) checkedEndpoints(advertised.get());
            NativeHostIdentity identity = NativeHostIdentity.load(certificate, privateKey);
            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String incarnation = HexFormat.of().formatHex(nonce);
            var validator = new StatelessAdmissionValidator(audience(incarnation), 60_000);
            var endpoint = new NativeAdmissionServerChannel(identity, validator, limits, true);
            bootstrap.clone().channelFactory(() -> endpoint).bind(bind).addListener(future -> {
                if (future.isSuccess()) {
                    result.complete(new NativeProviderTransport(endpoint, validator, incarnation, advertised, candidates));
                } else {
                    endpoint.close();
                    validator.clear();
                    result.completeExceptionally(future.cause());
                }
            });
        } catch (Exception failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    public static String audience(String incarnation) {
        if (incarnation == null || !incarnation.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid endpoint incarnation");
        }

        return "nxs-stateless-host-v1/" + incarnation;
    }

    public NativeAdmissionServerChannel channel() {
        return channel;
    }

    private boolean replaceCandidateMaterial(NativeCandidateSnapshot next, boolean identityChanged) {
        if (!fixedCandidates || closed || draining || !channel.isActive()) throw new IllegalStateException("Native listener unavailable");
        if (candidateSnapshot.equals(next) && !identityChanged) return false;
        if (candidateGeneration >= 9007199254740991L) throw new IllegalStateException("Candidate revision exhausted");
        candidateGeneration = Math.incrementExact(candidateGeneration);
        candidateSnapshot = next;
        if (diagnosticGate != null && diagnosticPolicy != null) {
            diagnosticGate.retainEndpoints(Set.of());
        }
        return true;
    }

    /** Ordinary supplier changes are sampled only on background work, never on packet callbacks. */
    private NativeCandidateSnapshot currentCandidates() {
        if (fixedCandidates) return candidateSnapshot;
        var advertised = checkedEndpoints(advertisedAddresses.get());
        var next = NativeCandidateSnapshot.hosts(advertised);
        ordinaryCandidateOrder = advertised.stream().map(endpoint -> new NativeCandidateSnapshot.Candidate(endpoint, NativeCandidateSnapshot.Type.HOST)).toList();
        if (candidateSnapshot == null) candidateSnapshot = next;
        else if (!candidateSnapshot.equals(next)) {
            if (diagnosticGate != null) diagnosticGate.retainEndpoints(Set.of());
            if (candidateGeneration >= 9007199254740991L) throw new IllegalStateException("Candidate revision exhausted");
            candidateSnapshot = next;
            candidateGeneration++;
        }
        return candidateSnapshot;
    }

    @Override public long candidatePublicationVersion() { return publicationVersion; }
    private synchronized void refreshMaintained() {
        if (closed || draining || !channel.isActive() || candidatePublisher == null) return;
        try {
            var next = candidatePublisher.refresh();
            boolean changed = replaceCandidateMaterial(next.candidates(), next.mappingRevision() != maintainedMappingRevision);
            maintainedMappingRevision = next.mappingRevision();
            if (candidatePublication == null || changed)
                publicationVersion = Math.incrementExact(publicationVersion);
            candidatePublication = next;
        } catch (RuntimeException unavailable) {
            // A failed refresh cannot leave an old mapping published indefinitely. Keep sampling.
            replaceCandidateMaterial(NativeCandidateSnapshot.hosts(List.of()), true);
            candidatePublication = null;
            publicationVersion = Math.incrementExact(publicationVersion);
        }
    }

    @Override public CompletionStage<Void> configureStunServers(List<StunServer> servers) {
        if (servers.size() > 2) return CompletableFuture.failedFuture(new IllegalArgumentException("At most two provider STUN servers"));
        synchronized (this) {
            if (closed || candidatePublisher == null || !candidatePublisher.needsStunServers()) return CompletableFuture.completedFuture(null);
        }
        // ProviderClient invokes configuration from its background discovery task, never the native loop.
        var numeric = new java.util.EnumMap<EndpointSelection.Family, InetSocketAddress>(EndpointSelection.Family.class);
        for (var server : servers) {
            try {
                for (var address : java.net.InetAddress.getAllByName(server.host())) {
                    if (!address.isAnyLocalAddress() && !address.isMulticastAddress())
                        numeric.putIfAbsent(EndpointSelection.Family.of(address), new InetSocketAddress(address, server.port()));
                }
            } catch (java.net.UnknownHostException unavailable) { /* Unavailable discovery remains unknown. */ }
        }
        var result = new CompletableFuture<Void>();
        try { channel.eventLoop().execute(() -> {
            try {
                synchronized (this) {
                    if (closed || draining || candidatePublisher == null) throw new IllegalStateException("Native listener unavailable");
                    candidatePublisher.configureStunServers(numeric); refreshMaintained();
                }
                result.complete(null);
            } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        }); } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return result;
    }

    @Override public CompletionStage<Void> reportConnectivityChecks(long revision, List<ConnectivityCheck> checks) {
        Objects.requireNonNull(checks);
        if (checks.size() > 18) throw new IllegalArgumentException("At most eighteen connectivity checks");
        var owned = List.copyOf(checks);
        long startNanos = System.nanoTime(), startMillis = diagnosticNow();
        synchronized (this) {
            if (closed || draining || candidatePublisher == null || candidateGeneration != revision)
                return CompletableFuture.completedFuture(null);
            if (connectivityFeedbackPending)
                return CompletableFuture.failedFuture(new IllegalStateException("Connectivity feedback already pending"));
            connectivityFeedbackPending = true;
        }
        // The original clock pair above bounds all time spent waiting for the event loop.
        var result = new CompletableFuture<Void>();
        try {
            channel.eventLoop().execute(() -> {
                try {
                    synchronized (this) {
                        if (!closed && !draining && channel.isActive() && candidatePublisher != null && candidateGeneration == revision) {
                            long elapsed = System.nanoTime() - startNanos;
                            if (elapsed < 0) throw new IllegalStateException("Connectivity clock reversed");
                            long now = Math.max(diagnosticNow(), Math.addExact(startMillis, elapsed / 1000000));
                            var fresh = owned.stream().filter(check -> check.checkedAt() <= startMillis
                                    && check.expiresAt() > now).toList();
                            candidatePublisher.reportDirectChecks(fresh, now);
                            refreshMaintained();
                        }
                    }
                    synchronized (this) { connectivityFeedbackPending = false; }
                    result.complete(null);
                } catch (RuntimeException failure) {
                    synchronized (this) { connectivityFeedbackPending = false; }
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            synchronized (this) { connectivityFeedbackPending = false; }
            result.completeExceptionally(failure);
        }
        return result;
    }

    @Override public boolean supportsDiagnosticAdmission() { return true; }

    /** Trusted local configuration, serialized on this listener; no installation acknowledgement or control owner. */
    @Override public CompletionStage<Void> configureDiagnostics(DiagnosticHostPolicy policy) {
        long started = System.nanoTime();
        long remaining = Math.min(300000, policy.expiresAt() - System.currentTimeMillis());
        if (remaining <= 0) return CompletableFuture.failedFuture(new IllegalArgumentException("Expired diagnostic configuration"));
        return configureDiagnostics(policy, started + TimeUnit.MILLISECONDS.toNanos(remaining));
    }

    @Override public CompletionStage<Void> configureDiagnostics(DiagnosticHostPolicy policy, long deadlineNanos) {
        Objects.requireNonNull(policy);
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0 || remaining > TimeUnit.MINUTES.toNanos(5))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Diagnostic deadline"));
        var result = new CompletableFuture<Void>();
        final long mutation;
        synchronized (this) {
            if (closed || draining || !channel.isActive()) return CompletableFuture.failedFuture(new IllegalStateException("Native listener unavailable"));
            if (diagnosticConfiguring) return CompletableFuture.failedFuture(new IllegalStateException("Diagnostic configuration already in flight"));
            diagnosticConfiguring = true; mutation = ++diagnosticMutation;
        }
        try {
            channel.eventLoop().execute(() -> {
                Throwable failure = null;
                synchronized (NativeProviderTransport.this) {
                    try {
                        long now = diagnosticNow();
                        if (diagnosticMutation != mutation || closed || draining || !channel.isActive()) throw new IllegalStateException("Diagnostic configuration cancelled");
                        if (now >= policy.expiresAt() || policy.endpointExpiries().values().stream().anyMatch(expiry -> now >= expiry))
                            throw new IllegalArgumentException("Expired diagnostic configuration");
                        NativeCandidateSnapshot current = candidatePublication == null ? currentCandidates() : candidatePublication.candidates();
                        if (!incarnation.equals(policy.context().incarnation()) || policy.endpoints().stream().anyMatch(endpoint -> endpoint.candidateRevision() != candidateGeneration
                                || (endpoint.assisted() ? !eligibleAssistedFamilies(current.candidates()).contains(endpoint.family()) : !containsEndpoint(current, endpoint))))
                            throw new IllegalArgumentException("Diagnostic listener or endpoint mismatch");
                        if (candidatePublication != null) {
                            candidatePublication.requireCurrent();
                        }
                        if (diagnosticMutation != mutation || closed || draining || !channel.isActive()) throw new IllegalStateException("Diagnostic configuration cancelled");
                        long nanosLeft = deadlineNanos - System.nanoTime();
                        if (nanosLeft <= 0) throw new IllegalArgumentException("Expired diagnostic configuration");
                        long expiry = Math.min(policy.expiresAt(), System.currentTimeMillis() + TimeUnit.NANOSECONDS.toMillis(nanosLeft));
                        var endpointExpiries = new HashMap<DiagnosticHostPolicy.Endpoint, Long>();
                        policy.endpointExpiries().forEach((endpoint, limit) -> endpointExpiries.put(endpoint, Math.min(limit, expiry)));
                        var bounded = new DiagnosticHostPolicy(policy.context(), policy.keys(), policy.endpoints(), expiry, endpointExpiries);
                        diagnosticGate = channel.installDiagnostics(bounded, diagnosticGate);
                        diagnosticPolicy = bounded;
                    } catch (Throwable problem) { failure = problem; }
                    finally { diagnosticConfiguring = false; }
                }
                // User completions never run under the transport monitor.
                if (failure == null) result.complete(null); else result.completeExceptionally(failure);
            });
        } catch (RuntimeException failure) {
            synchronized (this) { diagnosticConfiguring = false; }
            result.completeExceptionally(failure);
        }
        return result.minimalCompletionStage();
    }

    private static boolean containsEndpoint(NativeCandidateSnapshot snapshot, DiagnosticHostPolicy.Endpoint endpoint) {
        return snapshot.candidates().stream().anyMatch(candidate -> sameEndpoint(candidate, endpoint));
    }

    private static boolean sameEndpoint(NativeCandidateSnapshot.Candidate candidate, DiagnosticHostPolicy.Endpoint endpoint) {
        var address = candidate.endpoint().getAddress();
        int family = address instanceof java.net.Inet6Address ? 6 : 4;
        return family == endpoint.family() && candidate.endpoint().getPort() == endpoint.port()
                && DiagnosticAdmissionCodec.address(family, address.getHostAddress()).equals(endpoint.addressHex());
    }
    /** Withdraw new and active diagnostics, retaining the local anti-replay history. Player admission is untouched. */
    @Override public synchronized CompletionStage<Void> disableDiagnostics() {
        ++diagnosticMutation;
        if (diagnosticGate != null) diagnosticGate.retainEndpoints(Set.of());
        return CompletableFuture.completedFuture(null);
    }
    /** Local observations only. They do not require a control-plane completion receipt. */
    public synchronized List<NativeDiagnosticHostGate.Result> pollDiagnosticResults(int maximum) {
        if (maximum < 0 || maximum > 32) throw new IllegalArgumentException("Diagnostic result poll bound");
        return diagnosticGate == null ? List.of() : diagnosticGate.pollResults(maximum);
    }
    public synchronized java.util.OptionalLong diagnosticDroppedResultCount() {
        return diagnosticGate == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(diagnosticGate.stats().droppedResults());
    }

    @Override
    public CompletionStage<JsonObject> hostProfile() {
        return captureHostProfile().thenApply(HostProfileSnapshot::profile);
    }

    @Override public boolean supportsAssistedJoins() { return true; }

    @Override public CompletionStage<String> assistedJoin(org.cloudburstmc.netty.signaling.control.AssistedJoin join, Runnable requireCurrent) {
        Runnable guard = () -> {
            requireCurrent.run();
            if (closed || draining || !channel.isServing() || !incarnation.equals(join.incarnation())
                    || !validator.keyIds().contains(join.keyId()) || epochs.stream().noneMatch(e -> e.id().equals(join.keyId())
                        && e.notBefore() <= System.currentTimeMillis() && e.retireAfter() > System.currentTimeMillis()
                        && e.retireAfter() >= join.expiresAt())) throw new IllegalStateException("Assisted native identity unavailable");
        };
        guard.run();
        Map<Integer, InetSocketAddress> stunServers;
        var publicCandidates = new HashMap<Integer, InetSocketAddress>();
        synchronized (this) {
            stunServers = candidatePublisher == null ? Map.of() : candidatePublisher.assistedStunServers();
            if (candidateSnapshot != null) for (var candidate : candidateSnapshot.candidates()) {
                var endpoint = candidate.endpoint();
                if (candidate.type() == NativeCandidateSnapshot.Type.HOST
                        && EndpointAddress.scope(endpoint.getAddress()) == EndpointAddress.Scope.PUBLIC)
                    publicCandidates.putIfAbsent(endpoint.getAddress() instanceof java.net.Inet4Address ? 4 : 6, endpoint);
            }
        }
        var selected = Map.copyOf(publicCandidates);
        return join.diagnostic() ? channel.assistDiagnostic(join, guard, stunServers, selected)
                : channel.assist(join, guard, stunServers, selected);
    }

    @Override
    public synchronized CompletionStage<HostProfileSnapshot> captureHostProfile() {
        if (closed || draining || !channel.isActive()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Native endpoint unavailable"));
        }

        long now = System.currentTimeMillis();
        String keyId = null;
        Set<String> installed = validator.keyIds();

        // The provider supplies keys oldest-to-newest and acknowledges its last epoch before publication.
        for (Epoch epoch : epochs) {
            if (epoch.notBefore() <= now && epoch.retireAfter() > now && installed.contains(epoch.id())) {
                keyId = epoch.id();
            }
        }

        if (keyId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No active background admission key"));
        }

        List<NativeCandidateSnapshot.Candidate> endpoints;
        try {
            NativeCandidateSnapshot current = currentCandidates();
            endpoints = fixedCandidates ? current.candidates() : ordinaryCandidateOrder;
        } catch (RuntimeException unavailable) {
            return CompletableFuture.failedFuture(unavailable);
        }

        JsonArray candidates = encodeCandidates(endpoints);

        JsonObject capability = new JsonObject();
        capability.addProperty("capability", CAPABILITY);
        capability.addProperty("incarnation", incarnation);

        JsonObject profile = new JsonObject();
        profile.add("candidates", candidates);
        profile.add("statelessAdmission", capability);
        profile.addProperty("credentialKeyId", keyId);
        profile.addProperty("dtlsFingerprint", channel.identity().fingerprint());
        profile.addProperty("maxMessageSize", NetherNetFrameDecoder.MESSAGE_LIMIT);
        profile.addProperty("sctpPort", 5000);

        long capturedGeneration = candidateGeneration;
        var observed = candidatePublication;
        if (observed != null) observed.requireCurrent();
        var assisted = eligibleAssistedFamilies(endpoints);
        return CompletableFuture.completedFuture(new HostProfileSnapshot(profile, capturedGeneration, publicationVersion, candidates, assisted, () -> {
            if (closed || draining || !channel.isActive())
                throw new IllegalStateException("Native endpoint snapshot closed");
            if (candidateGeneration != capturedGeneration)
                throw new HostProfileSnapshotChangedException();
            if (observed != null) observed.requireCurrent();
        }));
    }

    private Set<Integer> eligibleAssistedFamilies(List<NativeCandidateSnapshot.Candidate> endpoints) {
        if (candidatePublication != null) return candidatePublication.assistedFamilies();
        var families = new HashSet<Integer>();
        for (var candidate : endpoints) {
            var address = candidate.endpoint().getAddress();
            if (EndpointAddress.scope(address) == EndpointAddress.Scope.PUBLIC)
                families.add(address instanceof java.net.Inet4Address ? 4 : 6);
        }
        return Set.copyOf(families);
    }

    private JsonArray encodeCandidates(List<NativeCandidateSnapshot.Candidate> endpoints) {
        JsonArray candidates = new JsonArray();
        int index = 0;
        for (NativeCandidateSnapshot.Candidate selected : endpoints) {
            InetSocketAddress endpoint = selected.endpoint();
            JsonObject candidate = new JsonObject();
            candidate.addProperty("address", endpoint.getAddress().getHostAddress());
            candidate.addProperty("port", endpoint.getPort());
            candidate.addProperty("component", 1);
            candidate.addProperty("foundation", Integer.toString(++index));
            candidate.addProperty("priority", 2130706431 - (index - 1) * 256);
            candidate.addProperty("protocol", "udp");
            candidate.addProperty("type", selected.type().wire());
            candidates.add(candidate);
        }

        return candidates;
    }

    private static List<InetSocketAddress> checkedEndpoints(List<InetSocketAddress> endpoints) {
        List<InetSocketAddress> unique = endpoints.stream().distinct().toList();
        if (unique.isEmpty() || unique.size() > 32) {
            throw new IllegalArgumentException("Publish 1-32 UDP endpoints");
        }

        for (InetSocketAddress endpoint : unique) {
            if (endpoint == null || endpoint.isUnresolved() || endpoint.getPort() == 0
                    || EndpointAddress.scope(endpoint.getAddress()) == EndpointAddress.Scope.UNUSABLE) {
                throw new IllegalArgumentException("Concrete advertised UDP address and fixed port required");
            }
        }

        return List.copyOf(unique);
    }

    @Override
    public synchronized CompletionStage<Void> installTicketKeys(List<TicketKey> keys) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Native endpoint closed"));
        }

        try {
            if (keys == null || keys.size() > 8) {
                throw new IllegalArgumentException("At most eight admission epochs");
            }
            keys = List.copyOf(keys);

            validator.installKeys(keys.stream()
                    .map(k -> new StatelessAdmissionValidator.TicketKey(k.keyId(), k.secret(), k.notBefore(),
                            k.retireAfter())).toList());
            epochs = keys.stream().map(k -> new Epoch(k.keyId(), k.notBefore(), k.retireAfter())).toList();
            validator.retireKeys(System.currentTimeMillis());

            return CompletableFuture.completedFuture(null);
        } catch (Exception invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
    }

    @Override
    public synchronized CompletionStage<ApplyResult> applyState(String state) {
        if (state == null) {
            return CompletableFuture.completedFuture(ApplyResult.REJECTED);
        }

        return switch (state) {
            // A serving observation cannot reopen a permanently drained listener.
            case "serving" -> CompletableFuture.completedFuture(!closed && !draining && channel.isServing()
                    ? ApplyResult.APPLIED : ApplyResult.REJECTED);
            case "draining" -> drain().thenApply(ignored -> ApplyResult.APPLIED);
            case "closed" -> close().thenApply(ignored -> ApplyResult.APPLIED);
            default -> CompletableFuture.completedFuture(ApplyResult.REJECTED);
        };
    }

    @Override
    public List<JsonObject> pollEvents() {
        return pollEvents(256);
    }

    @Override
    public List<JsonObject> pollEvents(int maximum) {
        return channel.pollEvents(maximum).stream().map(event -> {
            JsonObject result = new JsonObject();
            result.addProperty("ticketId", event.ticketId());
            result.addProperty("stage", event.stage());
            result.addProperty("reason", event.reason());
            result.addProperty("occurredAt", Instant.ofEpochMilli(event.occurredAt()).toString());
            if (event.remoteEndpoint() != null) {
                result.addProperty("remoteAddress", event.remoteEndpoint().getAddress().getHostAddress());
                result.addProperty("remotePort", event.remoteEndpoint().getPort());
            }
            return result;
        }).toList();
    }

    @Override
    public synchronized CompletionStage<Void> drain() {
        draining = true;
        disableDiagnostics();
        if (candidateTask != null) candidateTask.cancel(false);
        if (candidatePublisher != null) candidatePublisher.close();
        channel.drainAdmissions();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> close() {
        if (!closed) {
            closed = true;
            draining = true;
            ++diagnosticMutation;
            retireTask.cancel(false);
            if (candidateTask != null) candidateTask.cancel(false);
            validator.clear();
            epochs = List.of();
            try { if (candidatePublisher != null) candidatePublisher.close(); }
            finally { channel.close(); }
        }

        return channel.termination();
    }
}
