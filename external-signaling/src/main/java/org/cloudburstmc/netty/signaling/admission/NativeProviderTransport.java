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
import org.cloudburstmc.netty.signaling.control.CandidateLeaseCodec;
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
    private volatile long candidateGeneration = 1;
    private MaintainedCandidatePublisher candidatePublisher;
    private volatile List<Epoch> epochs = List.of();
    private Update update;
    private volatile boolean draining;
    private volatile boolean closed;
    private NativeDiagnosticHostGate diagnosticGate;
    private volatile DiagnosticInstall diagnosticInstall;
    private DiagnosticAdmission.Policy lastDiagnosticPolicy;
    private volatile long diagnosticMutation;
    private boolean diagnosticInstalling;
    private volatile long diagnosticKeyGeneration;
    private final long diagnosticAnchorNanos = System.nanoTime(), diagnosticAnchorMillis = System.currentTimeMillis();
    private final AtomicLong diagnosticTime = new AtomicLong(diagnosticAnchorMillis);

    private long diagnosticNow() {
        long elapsed = System.nanoTime() - diagnosticAnchorNanos;
        if (elapsed < 0) throw new IllegalStateException("Diagnostic monotonic clock rollback");
        long now = Math.max(System.currentTimeMillis(), Math.addExact(diagnosticAnchorMillis, elapsed / 1_000_000));
        return diagnosticTime.accumulateAndGet(now, Math::max);
    }
    private final class DiagnosticInstall {
        final DiagnosticAdmission.Installation handle;
        final long notBefore, expiresAt;
        final String profileKeyId;
        final long[] endpointExpiries;
        final long endpoints = candidateGeneration, keys = diagnosticKeyGeneration;
        final long mutation = diagnosticMutation;
        volatile boolean valid = true;
        DiagnosticInstall(DiagnosticAdmission.Policy policy, String profileKeyId) {
            this.profileKeyId = profileKeyId;
            notBefore = policy.notBefore(); expiresAt = policy.expiresAt();
            endpointExpiries = policy.endpoints().stream().mapToLong(DiagnosticAdmission.Endpoint::expiresAt).toArray();
            handle = new DiagnosticAdmission.Installation(policy.binding(), this::requireCurrent);
        }
        void requireCurrent() {
            long now = diagnosticNow();
            if (!valid || diagnosticInstall != this || diagnosticMutation != mutation || closed || draining || !channel.isActive()
                    || candidateGeneration != endpoints || diagnosticKeyGeneration != keys
                    || !profileKeyId.equals(diagnosticProfileKey(now))
                    || now < notBefore || now >= expiresAt
                    || Arrays.stream(endpointExpiries).anyMatch(expiry -> now >= expiry))
                throw new IllegalStateException("Diagnostic installation changed or expired");
        }
    }
    private String diagnosticProfileKey(long now) {
        String key = null;
        for (Epoch epoch : epochs) if (now >= epoch.notBefore() && now < epoch.retireAfter()) key = epoch.id();
        return key;
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
        long nextGeneration = Math.incrementExact(candidateGeneration);
        candidateSnapshot = next;
        candidateGeneration = nextGeneration;
        if (diagnosticInstall != null) diagnosticInstall.valid = false;
        if (diagnosticGate != null && lastDiagnosticPolicy != null) {
            var retained = new HashSet<DiagnosticHostPolicy.Endpoint>();
            for (var endpoint : lastDiagnosticPolicy.endpoints()) if (containsEndpoint(next, endpoint)) retained.add(endpoint.target());
            diagnosticGate.retainEndpoints(retained);
        }
        if (update != null) invalidateUpdate();
        return true;
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

    @Override public boolean supportsDiagnosticAdmission() { return controlled && version2; }

    /** Trusted parsed policy only; the application supplies its original authority/native-owner fence. */
    @Override public CompletionStage<DiagnosticAdmission.Installation> installDiagnosticPolicy(DiagnosticAdmission.Policy policy, Runnable requireCurrent) {
        Objects.requireNonNull(policy); Objects.requireNonNull(requireCurrent);
        var result = new CompletableFuture<DiagnosticAdmission.Installation>();
        final long mutation;
        synchronized (this) {
            if (!supportsDiagnosticAdmission()) return CompletableFuture.failedFuture(new UnsupportedOperationException("Controlled v2 diagnostic ownership required"));
            if (diagnosticInstalling) return CompletableFuture.failedFuture(new IllegalStateException("Diagnostic installation already in flight"));
            diagnosticInstalling = true;
            mutation = ++diagnosticMutation;
        }
        try {
            channel.eventLoop().execute(() -> {
                synchronized (NativeProviderTransport.this) {
                    DiagnosticInstall installed = null;
                    try {
                        requireCurrent.run(); requireDiagnosticMutation(mutation);
                        long now = diagnosticNow();
                        if (now < policy.notBefore() || now >= policy.expiresAt()
                                || policy.endpoints().stream().anyMatch(endpoint -> now >= endpoint.expiresAt()))
                            throw new IllegalStateException("Expired diagnostic policy");
                        // This adapter produces its profile synchronously; no native or application future is awaited here.
                        long capturedKeys = diagnosticKeyGeneration;
                        var snapshot = captureHostProfile().toCompletableFuture().getNow(null);
                        if (snapshot == null) throw new IllegalStateException("Native profile unavailable");
                        var profile = CandidateLeaseCodec.readProfile(snapshot.profile());
                        var binding = policy.binding();
                        if (!incarnation.equals(binding.context().incarnation())
                                || !channel.identity().fingerprint().substring(8).replace(":", "").toLowerCase(Locale.ROOT).equals(binding.hostFingerprintHex())
                                || !CandidateLeaseCodec.profileDigest(profile).equals(binding.hostProfileSha256())
                                || policy.endpoints().stream().anyMatch(endpoint -> !containsEndpoint(candidateSnapshot, endpoint)))
                            throw new IllegalStateException("Diagnostic native profile mismatch");
                        if (lastDiagnosticPolicy != null) {
                            var old = lastDiagnosticPolicy.binding();
                            if (!old.context().providerOrigin().equals(binding.context().providerOrigin()) || !old.context().hostId().equals(binding.context().hostId())
                                    || binding.context().generation() < old.context().generation()
                                    || binding.context().generation() == old.context().generation() && (binding.nativeOwnerEpoch() < old.nativeOwnerEpoch()
                                        || binding.policyRevision() < old.policyRevision()
                                        || binding.policyRevision() == old.policyRevision() && !policy.equals(lastDiagnosticPolicy)))
                                throw new IllegalStateException("Diagnostic policy rollback or revision conflict");
                        }
                        requireCurrent.run(); requireDiagnosticMutation(mutation); snapshot.requireCurrent();
                        if (capturedKeys != diagnosticKeyGeneration) throw new IllegalStateException("Diagnostic profile key changed");
                        installed = new DiagnosticInstall(policy, profile.credentialKeyId());
                        diagnosticGate = channel.installDiagnostics(policy.hostPolicy(), diagnosticGate);
                        if (diagnosticInstall != null) diagnosticInstall.valid = false;
                        diagnosticInstall = installed; lastDiagnosticPolicy = policy;
                        requireCurrent.run(); requireDiagnosticMutation(mutation); installed.requireCurrent();
                        diagnosticInstalling = false;
                        result.complete(installed.handle);
                    } catch (Throwable failure) {
                        // Reentrant callbacks may already own a replacement. Only this exact install can be removed.
                        if (installed != null && diagnosticInstall == installed) clearDiagnostic(installed);
                        diagnosticInstalling = false;
                        result.completeExceptionally(failure);
                    }
                }
            });
        } catch (RuntimeException failure) {
            synchronized (this) { diagnosticInstalling = false; }
            result.completeExceptionally(failure);
        }
        return result.minimalCompletionStage();
    }

    private void requireDiagnosticMutation(long mutation) {
        if (diagnosticMutation != mutation || closed || draining || !channel.isActive())
            throw new IllegalStateException("Diagnostic native owner replaced");
    }
    private static boolean containsEndpoint(NativeCandidateSnapshot snapshot, DiagnosticAdmission.Endpoint endpoint) {
        return snapshot.candidates().stream().anyMatch(candidate -> {
            var address = candidate.endpoint().getAddress();
            int family = address instanceof java.net.Inet6Address ? 6 : 4;
            return family == endpoint.target().family() && candidate.endpoint().getPort() == endpoint.target().port()
                    && candidate.type().wire().equals(endpoint.type())
                    && DiagnosticAdmissionCodec.address(family, address.getHostAddress()).equals(endpoint.target().addressHex());
        });
    }
    private void clearDiagnostic(DiagnosticInstall expected) {
        if (diagnosticInstall != expected) return;
        expected.valid = false; diagnosticInstall = null;
        if (diagnosticGate != null) diagnosticGate.retainEndpoints(Set.of());
    }
    @Override public CompletionStage<Boolean> withdrawDiagnosticPolicy(DiagnosticAdmission.Installation expected) {
        Objects.requireNonNull(expected);
        synchronized (this) {
            if (diagnosticInstall == null || diagnosticInstall.handle != expected || diagnosticInstall.mutation != diagnosticMutation)
                return CompletableFuture.completedFuture(false);
            ++diagnosticMutation; clearDiagnostic(diagnosticInstall);
            return CompletableFuture.completedFuture(true);
        }
    }
    @Override public Optional<DiagnosticAdmission.Installation> captureDiagnosticInstallation() {
        var installed = diagnosticInstall;
        if (installed == null) return Optional.empty();
        try { installed.requireCurrent(); return Optional.of(installed.handle); }
        catch (IllegalStateException unavailable) { return Optional.empty(); }
    }
    @Override public synchronized java.util.OptionalLong diagnosticDroppedResultCount() {
        return diagnosticGate == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(diagnosticGate.stats().droppedResults());
    }
    @Override public synchronized List<DiagnosticAdmission.Completion> pollDiagnosticResults(int maximum) {
        if (maximum < 0 || maximum > 32) throw new IllegalArgumentException("Diagnostic result poll bound");
        if (diagnosticGate == null) return List.of();
        return diagnosticGate.pollResults(maximum).stream().map(result -> {
            var udp = result.udp();
            var counters = udp == null ? null : new DiagnosticAdmission.UdpCounters(udp.reservedDatagrams(), udp.sentDatagrams(), udp.sentBytes(), udp.rejectedDatagrams());
            return new DiagnosticAdmission.Completion(result.installation(), result.context(), result.keyId(), result.attemptId(), result.offerDigestHex(),
                    result.clientFingerprintHex(), result.expiresAt(), result.target(), result.success(), result.cleanupComplete(), result.reason(),
                    result.selectedLocal(), result.selectedRemote(), counters, result.sentFrames(), result.sentBytes(), result.receivedFrames(),
                    result.receivedBytes(), result.completionDigestHex(), result.completedAt());
        }).toList();
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
            endpoints = version2 ? candidateSnapshot.candidates() : checkedEndpoints(advertisedAddresses.get()).stream()
                    .map(endpoint -> new NativeCandidateSnapshot.Candidate(endpoint, NativeCandidateSnapshot.Type.HOST)).toList();
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
        return CompletableFuture.completedFuture(new HostProfileSnapshot(profile, () -> {
            if (version2 && (candidateGeneration != capturedGeneration || closed || draining || !channel.isActive()))
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
            diagnosticKeyGeneration++;
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
        ++diagnosticMutation;
        if (diagnosticInstall != null) clearDiagnostic(diagnosticInstall);
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
            if (diagnosticInstall != null) clearDiagnostic(diagnosticInstall);
            retireTask.cancel(false);
            validator.clear();
            epochs = List.of();
            try { if (candidatePublisher != null) candidatePublisher.close(); }
            finally { channel.close(); }
        }

        return channel.termination();
    }
}
