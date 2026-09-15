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
import org.cloudburstmc.netty.signaling.provider.connectivity.ObservationLeaseTracker;
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
    private final boolean controlled;
    private final boolean version2;
    private NativeCandidateSnapshot candidateSnapshot;
    private List<NativeCandidateSnapshot.Candidate> ordinaryCandidateOrder = List.of();
    private volatile long candidateGeneration = 1;
    private MaintainedCandidatePublisher candidatePublisher;
    private volatile List<Epoch> epochs = List.of();
    private Update update;
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
    private static final class Update implements AdmissionUpdate {
        private final AdmissionGate.Staging nativeUpdate;
        private boolean installed;
        private boolean committing;
        private Update(AdmissionGate.Staging nativeUpdate) { this.nativeUpdate = nativeUpdate; }
    }

    private NativeProviderTransport(NativeAdmissionServerChannel channel, StatelessAdmissionValidator validator,
                                    String incarnation, Supplier<List<InetSocketAddress>> advertisedAddresses,
                                    boolean controlled, NativeCandidateSnapshot candidates) {
        this.channel = channel;
        this.validator = validator;
        this.incarnation = incarnation;
        this.advertisedAddresses = advertisedAddresses;
        this.controlled = controlled;
        this.version2 = candidates != null;
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
        return open(bootstrap, bind, advertised, certificate, privateKey, limits, false);
    }

    /**
     * Opt-in controlled listener. Admission is disabled before bind and remains disabled through key installation
     * and durable application storage, until an explicit current update is committed. Legacy open is unchanged.
     */
    public static CompletionStage<NativeProviderTransport> openControlled(ServerBootstrap bootstrap,
            InetSocketAddress bind, Supplier<List<InetSocketAddress>> advertised, Path certificate, Path privateKey,
            AdmissionGate.Limits limits) {
        return open(bootstrap, bind, advertised, certificate, privateKey, limits, true);
    }

    /** Explicit controlled v2 publication. Empty endpoints permit binding; STUN publication needs a later lease protocol. */
    public static CompletionStage<NativeProviderTransport> openControlledVersion2(ServerBootstrap bootstrap,
            InetSocketAddress bind, NativeCandidateSnapshot candidates, Path certificate, Path privateKey,
            AdmissionGate.Limits limits) {
        try { requirePublishableCandidates(candidates); }
        catch (RuntimeException invalid) { return CompletableFuture.failedFuture(invalid); }
        return open(bootstrap, bind, null, certificate, privateKey, limits, true, candidates);
    }

    /** Explicit maintained publication, on the already bound gameplay mux. No STUN server discovery occurs here. */
    public static CompletionStage<NativeProviderTransport> openControlledMaintained(ServerBootstrap bootstrap,
            EndpointSelection selection, Map<EndpointSelection.Family, InetSocketAddress> numericStunServers,
            Path certificate, Path privateKey, AdmissionGate.Limits limits) {
        Objects.requireNonNull(selection);
        var servers = Map.copyOf(numericStunServers);
        var direct = NativeCandidateSnapshot.hosts(selection.candidates().stream().map(EndpointSelection.Candidate::endpoint).toList());
        return openControlledVersion2(bootstrap, selection.bind(), direct, certificate, privateKey, limits).thenCompose(transport -> {
            var controller = selection.configured()
                    ? CompletableFuture.<org.cloudburstmc.netty.signaling.provider.connectivity.EndpointConnectivityController>completedFuture(null)
                    : transport.channel.enableConnectivity(selection, servers, Duration.ofMinutes(5));
            return controller.thenApply(value -> {
                synchronized (transport) {
                    transport.captureNativeIdentity().requireCurrent();
                    transport.candidatePublisher = new MaintainedCandidatePublisher(selection, value, new ObservationLeaseTracker(transport.incarnation));
                }
                return transport;
            }).whenComplete((value, failure) -> { if (failure != null) transport.close(); });
        });
    }

    private static CompletionStage<NativeProviderTransport> open(ServerBootstrap bootstrap, InetSocketAddress bind,
            Supplier<List<InetSocketAddress>> advertised, Path certificate, Path privateKey,
            AdmissionGate.Limits limits, boolean controlled) {
        return open(bootstrap, bind, advertised, certificate, privateKey, limits, controlled, null);
    }

    private static CompletionStage<NativeProviderTransport> open(ServerBootstrap bootstrap, InetSocketAddress bind,
            Supplier<List<InetSocketAddress>> advertised, Path certificate, Path privateKey,
            AdmissionGate.Limits limits, boolean controlled, NativeCandidateSnapshot candidates) {
        CompletableFuture<NativeProviderTransport> result = new CompletableFuture<>();
        try {
            if (candidates == null) checkedEndpoints(advertised.get());
            NativeHostIdentity identity = NativeHostIdentity.load(certificate, privateKey);
            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String incarnation = HexFormat.of().formatHex(nonce);
            var validator = new StatelessAdmissionValidator(audience(incarnation), 60_000);
            var endpoint = new NativeAdmissionServerChannel(identity, validator, limits, true, !controlled);
            bootstrap.clone().channelFactory(() -> endpoint).bind(bind).addListener(future -> {
                if (future.isSuccess()) {
                    result.complete(new NativeProviderTransport(endpoint, validator, incarnation, advertised, controlled, candidates));
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

    /** Replace semantic endpoint material without rotating native identity, keys or established peers. */
    public synchronized boolean replaceCandidates(NativeCandidateSnapshot next) {
        if (candidatePublisher != null) throw new IllegalStateException("Maintained publisher owns endpoint replacement");
        requirePublishableCandidates(next);
        return replaceCandidateMaterial(next);
    }

    private boolean replaceCandidateMaterial(NativeCandidateSnapshot next) {
        if (!version2 || closed || draining || !channel.isActive()) throw new IllegalStateException("Controlled v2 listener unavailable");
        if (candidateSnapshot.equals(next)) return false;
        if (candidateGeneration >= 9007199254740991L) throw new IllegalStateException("Candidate revision exhausted");
        long nextGeneration = Math.incrementExact(candidateGeneration);
        candidateSnapshot = next;
        candidateGeneration = nextGeneration;
        if (diagnosticGate != null && diagnosticPolicy != null) {
            diagnosticGate.retainEndpoints(Set.of());
        }
        if (update != null) invalidateUpdate();
        return true;
    }

    /** Ordinary supplier changes are sampled only on background work, never on packet callbacks. */
    private NativeCandidateSnapshot currentCandidates() {
        if (version2) return candidateSnapshot;
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

    @Override public boolean supportsMaintainedCandidateLeases() { return candidatePublisher != null; }

    @Override public synchronized CandidateLeaseSnapshot maintainCandidateLeases(boolean reflexivePublicationAllowed) {
        if (candidatePublisher == null) throw new UnsupportedOperationException("Maintained candidate publication was not enabled");
        var identity = captureNativeIdentity();
        var publication = candidatePublisher.refresh(reflexivePublicationAllowed);
        replaceCandidateMaterial(publication.candidates());
        var captured = publication.leases(); long generation = candidateGeneration;
        return new CandidateLeaseSnapshot(captured.materialRevision(), captured.observations(), () -> {
            identity.requireCurrent(); captured.requireCurrent();
            if (candidateGeneration != generation) throw new IllegalStateException("Maintained endpoint material changed");
        });
    }

    @Override public synchronized void candidateControlSynchronized() {
        if (candidatePublisher != null) candidatePublisher.controlSynchronized();
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
                        NativeCandidateSnapshot current = currentCandidates();
                        if (!incarnation.equals(policy.context().incarnation()) || policy.endpoints().stream().anyMatch(endpoint -> endpoint.candidateRevision() != candidateGeneration || !containsEndpoint(current, endpoint)))
                            throw new IllegalArgumentException("Diagnostic listener or endpoint mismatch");
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
        return snapshot.candidates().stream().anyMatch(candidate -> {
            var address = candidate.endpoint().getAddress();
            int family = address instanceof java.net.Inet6Address ? 6 : 4;
            return family == endpoint.family() && candidate.endpoint().getPort() == endpoint.port()
                    && DiagnosticAdmissionCodec.address(family, address.getHostAddress()).equals(endpoint.addressHex());
        });
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

    private static void requirePublishableCandidates(NativeCandidateSnapshot value) {
        Objects.requireNonNull(value, "candidates");
        if (value.candidates().stream().anyMatch(candidate -> candidate.type() != NativeCandidateSnapshot.Type.HOST))
            throw new IllegalArgumentException("STUN candidate publication requires a bounded candidate lease");
    }

    @Override
    public CompletionStage<JsonObject> hostProfile() {
        return captureHostProfile().thenApply(HostProfileSnapshot::profile);
    }

    @Override public boolean supportsNativeIdentityCapture() { return controlled && version2; }

    @Override public NativeIdentitySnapshot captureNativeIdentity() {
        if (!supportsNativeIdentityCapture()) throw new UnsupportedOperationException("Issued native ownership requires controlled version 2");
        var snapshot = new NativeIdentitySnapshot(incarnation, () -> {
            if (closed || draining || !channel.isActive()) throw new IllegalStateException("Native listener identity retired");
        });
        snapshot.requireCurrent(); return snapshot;
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
            endpoints = version2 ? current.candidates() : ordinaryCandidateOrder;
        } catch (RuntimeException unavailable) {
            return CompletableFuture.failedFuture(unavailable);
        }

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

        JsonObject capability = new JsonObject();
        capability.addProperty("capability", CAPABILITY);
        capability.addProperty("incarnation", incarnation);

        JsonObject profile = new JsonObject();
        if (version2) profile.addProperty("version", 2);
        profile.add("candidates", candidates);
        profile.add("statelessAdmission", capability);
        profile.addProperty("credentialKeyId", keyId);
        profile.addProperty("dtlsFingerprint", channel.identity().fingerprint());
        profile.addProperty("maxMessageSize", NetherNetFrameDecoder.MESSAGE_LIMIT);
        profile.addProperty("sctpPort", 5000);

        long capturedGeneration = candidateGeneration;
        return CompletableFuture.completedFuture(new HostProfileSnapshot(profile, capturedGeneration, () -> {
            if (candidateGeneration != capturedGeneration || closed || draining || !channel.isActive())
                throw new IllegalStateException("Native endpoint snapshot changed or closed");
        }));
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
        if (controlled) invalidateUpdate();
        return installOwnedKeys(keys);
    }

    @Override
    public boolean supportsAdmissionStaging() { return controlled; }

    @Override
    public AdmissionUpdate beginAdmissionUpdate(long deadlineNanos) {
        if (!controlled) throw new UnsupportedOperationException("Listener was not opened controlled");
        // Validate the caller's fixed bound before lock contention; never create a new relative deadline here.
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0 || remaining > 300_000_000_000L) throw new IllegalArgumentException("Admission deadline");
        synchronized (this) {
            if (closed || draining || !channel.isActive()) throw new IllegalStateException("Native endpoint unavailable");
            update = new Update(channel.stageAdmissions(deadlineNanos));
            return update;
        }
    }

    @Override
    public synchronized CompletionStage<Void> installTicketKeys(AdmissionUpdate expected, List<TicketKey> keys) {
        if (!current(expected)) return CompletableFuture.failedFuture(new IllegalStateException("Stale admission update"));
        if (update.installed || update.committing) {
            invalidateUpdate();
            return CompletableFuture.failedFuture(new IllegalStateException("One key snapshot per admission update"));
        }
        update.installed = true;
        CompletionStage<Void> installed = installOwnedKeys(keys);
        // installOwnedKeys is synchronous; no callback or native continuation can renew this token.
        if (installed.toCompletableFuture().isCompletedExceptionally()) invalidateUpdate();
        return installed;
    }

    @Override
    public CompletionStage<ApplyResult> commitAdmissionUpdate(AdmissionUpdate expected, Runnable requireCurrent) {
        synchronized (this) {
            if (!current(expected) || update.committing) return CompletableFuture.completedFuture(ApplyResult.REJECTED);
            if (!update.installed) {
                invalidateUpdate();
                return CompletableFuture.completedFuture(ApplyResult.REJECTED);
            }
            // Freeze the staged contents before invoking application code outside the lock.
            update.committing = true;
        }
        // Never run application/coordinator code under a transport/native monitor.
        try {
            Objects.requireNonNull(requireCurrent, "Current authority guard").run();
        } catch (RuntimeException failure) {
            synchronized (this) { if (current(expected)) invalidateUpdate(); }
            return CompletableFuture.failedFuture(failure);
        }
        synchronized (this) {
            // A guard may reenter and replace, close or drain this transport; no old token can undo it.
            if (!current(expected)) return CompletableFuture.completedFuture(ApplyResult.REJECTED);
            long now = System.currentTimeMillis();
            Set<String> installed = validator.keyIds();
            boolean eligible = epochs.stream().anyMatch(e -> e.notBefore() <= now && e.retireAfter() > now
                    && installed.contains(e.id()));
            if (!eligible) {
                invalidateUpdate();
                return CompletableFuture.completedFuture(ApplyResult.REJECTED);
            }
            boolean enabled = channel.enableAdmissions(update.nativeUpdate);
            update = null;
            return CompletableFuture.completedFuture(enabled ? ApplyResult.APPLIED : ApplyResult.REJECTED);
        }
    }

    private boolean current(AdmissionUpdate expected) {
        return controlled && expected != null && expected == update && !closed && !draining
                && channel.currentAdmissionUpdate(update.nativeUpdate);
    }

    private void invalidateUpdate() {
        update = null;
        channel.disableAdmissions();
    }

    private CompletionStage<Void> installOwnedKeys(List<TicketKey> keys) {
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
            // Observe only: neither legacy permanent drain nor controlled staging can be bypassed here.
            case "serving" -> CompletableFuture.completedFuture(!closed && !draining && channel.isServing()
                    ? ApplyResult.APPLIED : ApplyResult.REJECTED);
            case "draining" -> {
                if (!controlled) yield drain().thenApply(ignored -> ApplyResult.APPLIED);
                invalidateUpdate();
                yield CompletableFuture.completedFuture(!closed && !draining && channel.isActive()
                        ? ApplyResult.APPLIED : ApplyResult.REJECTED);
            }
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
            return result;
        }).toList();
    }

    @Override
    public synchronized CompletionStage<Void> drain() {
        if (controlled) invalidateUpdate();
        draining = true;
        disableDiagnostics();
        if (candidatePublisher != null) candidatePublisher.close();
        channel.drainAdmissions();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> close() {
        if (!closed) {
            if (controlled) invalidateUpdate();
            closed = true;
            draining = true;
            ++diagnosticMutation;
            retireTask.cancel(false);
            validator.clear();
            epochs = List.of();
            try { if (candidatePublisher != null) candidatePublisher.close(); }
            finally { channel.close(); }
        }

        return channel.termination();
    }
}
