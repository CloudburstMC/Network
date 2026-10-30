/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.admission;

import com.google.gson.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import org.cloudburstmc.netty.signaling.diagnostic.*;
import tel.schich.libdatachannel.PeerConnection;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

/** Temporary one-target regional fixture. Installs a fixed, already-authorized public IPv6 endpoint for at most 35 seconds. */
public final class RegionalDiagnosticHost {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> FIELDS =
            Set.of(
                    "bindAddress",
                    "providerOrigin",
                    "hostId",
                    "incarnation",
                    "generation",
                    "keyId",
                    "secret",
                    "keyRetireAt",
                    "policyExpiresAt",
                    "candidateRevision",
                    "enabled",
                    "runExpiresAt",
                    "certificatePath",
                    "keyPath",
                    "port");

    private static byte[] privateFile(Path path, int maximum) throws Exception {
        if (!path.isAbsolute()
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > maximum
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                        .equals(PosixFilePermissions.fromString("rw-------"))) {
            throw new IllegalArgumentException("private_file_required");
        }
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) {
                throw new IllegalArgumentException("private_file_too_large");
            }
            return bytes;
        }
    }

    private static String text(JsonObject config, String name) {
        JsonElement value = config.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("config_string");
        }
        return value.getAsString();
    }

    private static long integer(JsonObject config, String name) {
        JsonElement value = config.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("config_integer");
        }
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("config_integer");
        }
    }

    static long validateLifetime(JsonObject config, long now) {
        long expiry = integer(config, "runExpiresAt"),
                policyExpiry = integer(config, "policyExpiresAt");
        long port = integer(config, "port");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("config_port");
        }
        if (expiry <= now
                || expiry > now + 35_000
                || policyExpiry != expiry
                || integer(config, "keyRetireAt") < expiry) {
            throw new IllegalArgumentException("bounded_run_required");
        }
        return expiry;
    }

    private static void emit(Map<String, ?> value) {
        System.out.println(JSON.toJson(value));
        System.out.flush();
    }

    private static Map<String, Object> result(NativeDiagnosticHostGate.Result result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("event", "diagnostic_result");
        out.put("success", result.success());
        out.put("reason", result.reason());
        out.put("attemptId", result.attemptId());
        out.put("offerDigestHex", result.offerDigestHex());
        out.put("clientFingerprintHex", result.clientFingerprintHex());
        out.put("expiresAt", result.expiresAt());
        out.put("completedAt", result.completedAt());
        out.put("family", result.target().family());
        out.put("candidateRevision", result.target().candidateRevision());
        out.put("sentFrames", result.sentFrames());
        out.put("sentBytes", result.sentBytes());
        out.put("receivedFrames", result.receivedFrames());
        out.put("receivedBytes", result.receivedBytes());
        if (result.selectedLocal() != null) {
            out.put("selectedLocalAddress", result.selectedLocal().getAddress().getHostAddress());
            out.put("selectedLocalPort", result.selectedLocal().getPort());
            out.put("selectedRemoteAddress", result.selectedRemote().getAddress().getHostAddress());
            out.put("selectedRemotePort", result.selectedRemote().getPort());
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].equals("--config")) {
            throw new IllegalArgumentException("config_required");
        }
        Path configPath = Path.of(args[1]);
        String wire =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(privateFile(configPath, 8192)))
                        .toString();
        JsonObject config = JsonParser.parseString(wire).getAsJsonObject();
        if (!config.keySet().equals(FIELDS) || !JSON.toJson(config).equals(wire)) {
            throw new IllegalArgumentException("canonical_closed_config_required");
        }
        String numeric = text(config, "bindAddress");
        int family = numeric.contains(":") ? 6 : 4;
        DiagnosticAdmissionCodec.address(family, numeric);
        InetAddress bind = InetAddress.getByName(numeric);
        if (family != 6
                || org.cloudburstmc.netty.util.nethernet.EndpointAddress.scope(bind)
                        != org.cloudburstmc.netty.util.nethernet.EndpointAddress.Scope.PUBLIC
                || NetworkInterface.getByInetAddress(bind) == null) {
            throw new IllegalArgumentException("local_public_ipv6_required");
        }
        long now = System.currentTimeMillis(), runExpiry = validateLifetime(config, now);
        long durationNanos = TimeUnit.MILLISECONDS.toNanos(runExpiry - now),
                started = System.nanoTime();
        Path certificate = Path.of(text(config, "certificatePath")),
                keyPath = Path.of(text(config, "keyPath"));
        privateFile(certificate, 16384);
        privateFile(keyPath, 16384);
        var identity = NativeHostIdentity.load(certificate, keyPath);
        Context context =
                new Context(
                        text(config, "providerOrigin"),
                        text(config, "hostId"),
                        text(config, "incarnation"),
                        integer(config, "generation"));
        var key =
                new DiagnosticAdmissionCodec.Key(
                        text(config, "keyId"),
                        text(config, "secret"),
                        0,
                        integer(config, "keyRetireAt"));
        if (!config.get("enabled").isJsonPrimitive()
                || !config.getAsJsonPrimitive("enabled").isBoolean()) {
            throw new IllegalArgumentException("config_boolean");
        }
        boolean enabled = config.get("enabled").getAsBoolean();
        int port = Math.toIntExact(integer(config, "port"));
        Thread watchdog =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(45_000);
                                Runtime.getRuntime().halt(2);
                            } catch (InterruptedException finished) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        "regional-fixture-hard-deadline");
        watchdog.setDaemon(true);
        watchdog.start();
        AtomicInteger playerValidations = new AtomicInteger(), playerChildren = new AtomicInteger();
        var endpoint =
                new NativeAdmissionServerChannel(
                        identity,
                        NativeDiagnosticHostTest.validator(context, key, playerValidations),
                        AdmissionGate.Limits.defaults());
        var group = new DefaultEventLoopGroup(1);
        NativeDiagnosticHostGate gate = null;
        long creations = PeerConnection.nativeCreationAttempts();
        boolean cleanup = false, sawResult = false;
        try {
            new ServerBootstrap()
                    .group(group)
                    .channelFactory(() -> endpoint)
                    .childHandler(
                            new ChannelInitializer<Channel>() {
                                protected void initChannel(Channel channel) {
                                    playerChildren.incrementAndGet();
                                    channel.close();
                                }
                            })
                    .bind(bind, port)
                    .sync();
            if (enabled) {
                gate =
                        endpoint.enableDiagnostics(
                                        new DiagnosticHostPolicy(
                                                context,
                                                List.of(key),
                                                Set.of(
                                                        new DiagnosticHostPolicy.Endpoint(
                                                                family,
                                                                DiagnosticAdmissionCodec.address(
                                                                        family, numeric),
                                                                port,
                                                                integer(
                                                                        config,
                                                                        "candidateRevision"))),
                                                integer(config, "policyExpiresAt")))
                                .toCompletableFuture()
                                .get(3, TimeUnit.SECONDS);
            }
            emit(
                    Map.of(
                            "event",
                            "ready",
                            "family",
                            family,
                            "port",
                            port,
                            "enabled",
                            enabled,
                            "hostFingerprintHex",
                            identity.fingerprint()
                                    .substring(8)
                                    .replace(":", "")
                                    .toLowerCase(Locale.ROOT),
                            "nativeCreations",
                            PeerConnection.nativeCreationAttempts() - creations,
                            "playerChildren",
                            playerChildren.get(),
                            "policyExpiresAt",
                            runExpiry));
            while (System.currentTimeMillis() < runExpiry
                    && System.nanoTime() - started < durationNanos
                    && !Files.exists(
                            configPath.resolveSibling("stop"), LinkOption.NOFOLLOW_LINKS)) {
                if (gate != null) {
                    for (var report : gate.pollResults()) {
                        emit(result(report));
                        sawResult = true;
                    }
                }
                if (sawResult) {
                    break;
                }
                Thread.sleep(10);
            }
            long received = endpoint.nativeStats()[0];
            int playerEvents = endpoint.pollEvents().size();
            endpoint.close().awaitUninterruptibly();
            endpoint.termination().toCompletableFuture().get(6, TimeUnit.SECONDS);
            cleanup = true;
            if (gate != null) {
                for (var report : gate.pollResults()) {
                    emit(result(report));
                }
            }
            emit(
                    Map.of(
                            "event",
                            "closed",
                            "cleanup",
                            true,
                            "nativeCreations",
                            PeerConnection.nativeCreationAttempts() - creations,
                            "liveNativePeers",
                            endpoint.liveNativePeers(),
                            "playerValidations",
                            playerValidations.get(),
                            "playerChildren",
                            playerChildren.get(),
                            "playerEvents",
                            playerEvents,
                            "udpReceived",
                            received,
                            "retainedAttempts",
                            gate == null ? 0 : gate.stats().retainedAttempts()));
            if (playerValidations.get() != 0
                    || playerChildren.get() != 0
                    || endpoint.liveNativePeers() != 0) {
                throw new AssertionError("diagnostic_isolation");
            }
        } finally {
            if (!cleanup) {
                endpoint.close().awaitUninterruptibly();
                endpoint.termination().toCompletableFuture().get(6, TimeUnit.SECONDS);
            }
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
}
