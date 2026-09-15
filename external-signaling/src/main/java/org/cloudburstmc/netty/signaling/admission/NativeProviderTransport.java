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
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
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
    private List<Epoch> epochs = List.of();
    private Update update;
    private boolean draining;
    private boolean closed;

    private static final class Update implements AdmissionUpdate {
        private final AdmissionGate.Staging nativeUpdate;
        private boolean installed;
        private boolean committing;
        private Update(AdmissionGate.Staging nativeUpdate) { this.nativeUpdate = nativeUpdate; }
    }

    private NativeProviderTransport(NativeAdmissionServerChannel channel, StatelessAdmissionValidator validator,
                                    String incarnation, Supplier<List<InetSocketAddress>> advertisedAddresses,
                                    boolean controlled) {
        this.channel = channel;
        this.validator = validator;
        this.incarnation = incarnation;
        this.advertisedAddresses = advertisedAddresses;
        this.controlled = controlled;
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

    private static CompletionStage<NativeProviderTransport> open(ServerBootstrap bootstrap, InetSocketAddress bind,
            Supplier<List<InetSocketAddress>> advertised, Path certificate, Path privateKey,
            AdmissionGate.Limits limits, boolean controlled) {
        CompletableFuture<NativeProviderTransport> result = new CompletableFuture<>();
        try {
            checkedEndpoints(advertised.get());
            NativeHostIdentity identity = NativeHostIdentity.load(certificate, privateKey);
            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String incarnation = HexFormat.of().formatHex(nonce);
            var validator = new StatelessAdmissionValidator(audience(incarnation), 60_000);
            var endpoint = new NativeAdmissionServerChannel(identity, validator, limits, true, !controlled);
            bootstrap.clone().channelFactory(() -> endpoint).bind(bind).addListener(future -> {
                if (future.isSuccess()) {
                    result.complete(new NativeProviderTransport(endpoint, validator, incarnation, advertised, controlled));
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

    @Override
    public synchronized CompletionStage<JsonObject> hostProfile() {
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

        List<InetSocketAddress> endpoints;
        try {
            endpoints = checkedEndpoints(advertisedAddresses.get());
        } catch (RuntimeException unavailable) {
            return CompletableFuture.failedFuture(unavailable);
        }

        JsonArray candidates = new JsonArray();
        int index = 0;
        for (InetSocketAddress endpoint : endpoints) {
            JsonObject candidate = new JsonObject();
            candidate.addProperty("address", endpoint.getAddress().getHostAddress());
            candidate.addProperty("port", endpoint.getPort());
            candidate.addProperty("component", 1);
            candidate.addProperty("foundation", Integer.toString(++index));
            candidate.addProperty("priority", 2130706431 - (index - 1) * 256);
            candidate.addProperty("protocol", "udp");
            candidate.addProperty("type", "host");
            candidates.add(candidate);
        }

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

        return CompletableFuture.completedFuture(profile);
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
    public synchronized AdmissionUpdate beginAdmissionUpdate() {
        if (!controlled) throw new UnsupportedOperationException("Listener was not opened controlled");
        if (closed || draining || !channel.isActive()) throw new IllegalStateException("Native endpoint unavailable");
        update = new Update(channel.stageAdmissions());
        return update;
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
        return channel.pollEvents().stream().map(event -> {
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
        channel.drainAdmissions();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> close() {
        if (!closed) {
            if (controlled) invalidateUpdate();
            closed = true;
            draining = true;
            retireTask.cancel(false);
            validator.clear();
            epochs = List.of();
            channel.close();
        }

        return channel.termination();
    }
}
