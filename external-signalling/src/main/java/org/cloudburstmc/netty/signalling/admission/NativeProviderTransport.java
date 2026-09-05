package org.cloudburstmc.netty.signalling.admission;

import com.google.gson.*;
import org.cloudburstmc.netty.channel.nethernet.admission.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.util.concurrent.ScheduledFuture;
import org.cloudburstmc.netty.signalling.ProviderTransport;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Profile adapter: background key/profile lifecycle only; no per-join metadata input is used. */
public final class NativeProviderTransport implements ProviderTransport {
    public static final String CAPABILITY = "nethernet.stateless-admission.v1";
    private record Epoch(String id, long notBefore, long retireAfter) {}
    private final NativeAdmissionServerChannel channel;
    private final StatelessAdmissionValidator validator;
    private final String incarnation;
    private final InetSocketAddress advertisedAddress;
    private final ScheduledFuture<?> retireTask;
    private List<Epoch> epochs = List.of();
    private boolean draining, closed;

    private NativeProviderTransport(NativeAdmissionServerChannel channel, StatelessAdmissionValidator validator, String incarnation, InetSocketAddress advertisedAddress) {
        this.channel = channel; this.validator = validator; this.incarnation = incarnation;
        this.advertisedAddress = advertisedAddress;
        retireTask = channel.eventLoop().scheduleWithFixedDelay(() -> validator.retireKeys(System.currentTimeMillis()), 1, 1, TimeUnit.SECONDS);
    }
    /** The caller provisions the host PEM identity before opening/registration. No client state is accepted. */
    public static CompletionStage<NativeProviderTransport> open(ServerBootstrap bootstrap, InetSocketAddress bind, Path certificate, Path privateKey, AdmissionGate.Limits limits) {
        return open(bootstrap, bind, bind, certificate, privateKey, limits);
    }
    /** Explicit advertised candidate supports wildcard/local binds and operator-provisioned NAT mappings. */
    public static CompletionStage<NativeProviderTransport> open(ServerBootstrap bootstrap, InetSocketAddress bind, InetSocketAddress advertised, Path certificate, Path privateKey, AdmissionGate.Limits limits) {
        CompletableFuture<NativeProviderTransport> result = new CompletableFuture<>();
        try {
            if (advertised == null || advertised.isUnresolved() || advertised.getPort() == 0 || advertised.getAddress().isAnyLocalAddress())
                throw new IllegalArgumentException("Concrete advertised UDP address and fixed port required");
            NativeHostIdentity identity = NativeHostIdentity.load(certificate, privateKey);
            byte[] nonce = new byte[16]; new SecureRandom().nextBytes(nonce);
            String incarnation = HexFormat.of().formatHex(nonce);
            var validator = new StatelessAdmissionValidator(audience(incarnation), 60_000);
            var endpoint = new NativeAdmissionServerChannel(identity, validator, limits, true);
            bootstrap.clone().channelFactory(() -> endpoint).bind(bind).addListener(future -> {
                if (future.isSuccess()) result.complete(new NativeProviderTransport(endpoint, validator, incarnation, advertised));
                else { endpoint.close(); validator.clear(); result.completeExceptionally(future.cause()); }
            });
        } catch (Exception failure) { result.completeExceptionally(failure); }
        return result;
    }
    public static String audience(String incarnation) {
        if (incarnation == null || !incarnation.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid endpoint incarnation");
        return "nxs-stateless-host-v1/" + incarnation;
    }
    public NativeAdmissionServerChannel channel() { return channel; }

    @Override public synchronized CompletionStage<JsonObject> hostProfile() {
        if (closed || draining || !channel.isActive()) return CompletableFuture.failedFuture(new IllegalStateException("Native endpoint unavailable"));
        long now = System.currentTimeMillis(); String keyId = null;
        Set<String> installed = validator.keyIds();
        // The provider supplies keys oldest-to-newest and acknowledges its last epoch before publication.
        for (Epoch epoch : epochs) if (epoch.notBefore() <= now && epoch.retireAfter() > now && installed.contains(epoch.id())) keyId = epoch.id();
        if (keyId == null) return CompletableFuture.failedFuture(new IllegalStateException("No active background admission key"));
        InetSocketAddress bind = advertisedAddress;
        JsonObject candidate = new JsonObject(); candidate.addProperty("address", bind.getAddress().getHostAddress());
        candidate.addProperty("port", bind.getPort()); candidate.addProperty("component", 1); candidate.addProperty("foundation", "1");
        candidate.addProperty("priority", 2130706431); candidate.addProperty("protocol", "udp"); candidate.addProperty("type", "host");
        JsonArray candidates = new JsonArray(); candidates.add(candidate);
        JsonObject capability = new JsonObject(); capability.addProperty("capability", CAPABILITY); capability.addProperty("incarnation", incarnation);
        JsonObject profile = new JsonObject(); profile.add("candidates", candidates); profile.add("statelessAdmission", capability);
        profile.addProperty("credentialKeyId", keyId); profile.addProperty("dtlsFingerprint", channel.identity().fingerprint());
        profile.addProperty("maxMessageSize", 262144); profile.addProperty("sctpPort", 5000);
        return CompletableFuture.completedFuture(profile);
    }
    @Override public synchronized CompletionStage<Void> installTicketKeys(List<TicketKey> keys) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Native endpoint closed"));
        try {
            if (keys == null || keys.size() > 8) throw new IllegalArgumentException("At most eight admission epochs");
            validator.installKeys(keys.stream().map(k -> new StatelessAdmissionValidator.TicketKey(k.keyId(), k.secret(), k.notBefore(), k.retireAfter())).toList());
            epochs = keys.stream().map(k -> new Epoch(k.keyId(), k.notBefore(), k.retireAfter())).toList();
            validator.retireKeys(System.currentTimeMillis());
            return CompletableFuture.completedFuture(null);
        } catch (Exception invalid) { return CompletableFuture.failedFuture(invalid); }
    }
    @Override public CompletionStage<ApplyResult> applyControl(JsonObject command) {
        if (command == null || !command.has("kind") || !command.get("kind").isJsonPrimitive() || !command.getAsJsonPrimitive("kind").isString())
            return CompletableFuture.completedFuture(ApplyResult.REJECTED);
        return switch (command.get("kind").getAsString()) {
            case "noop" -> CompletableFuture.completedFuture(ApplyResult.APPLIED);
            case "drain" -> drain().thenApply(ignored -> ApplyResult.APPLIED);
            case "suspend", "revoke" -> close().thenApply(ignored -> ApplyResult.APPLIED);
            // Native admission never stages a client from control. Unsupported lifecycle changes are explicit rejections.
            default -> CompletableFuture.completedFuture(ApplyResult.REJECTED);
        };
    }
    @Override public List<JsonObject> pollEvents() {
        return channel.pollEvents().stream().map(event -> {
            JsonObject result = new JsonObject(); result.addProperty("ticketId", event.ticketId()); result.addProperty("stage", event.stage());
            result.addProperty("reason", event.reason()); result.addProperty("occurredAt", Instant.ofEpochMilli(event.occurredAt()).toString());
            return result;
        }).toList();
    }
    @Override public synchronized CompletionStage<Void> drain() {
        draining = true; channel.drainAdmissions(); return CompletableFuture.completedFuture(null);
    }
    @Override public synchronized CompletionStage<Void> close() {
        if (!closed) {
            closed = true; draining = true; retireTask.cancel(false); validator.clear(); epochs = List.of(); channel.close();
        }
        return channel.termination();
    }
}
