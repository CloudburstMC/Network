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

package org.cloudburstmc.netty.signaling.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signaling.admission.AdmissionGate;
import io.netty.bootstrap.ServerBootstrap;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;
import org.cloudburstmc.netty.signaling.admission.NativeProviderTransport;
import org.cloudburstmc.netty.signaling.admission.NativeCandidateSnapshot;
import org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.EnumMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Fixed native endpoint using the extension's existing Bedrock child pipeline.
 */
public final class NativeProviderHostFactory implements ProviderHostFactory {
    /** Complete configured set, otherwise public addresses owned by the gameplay listener. */
    public static final String EXPLICIT_OR_PUBLIC_LOCAL = "explicit-or-public-local";
    public static final String MAINTAINED_V1 = "maintained-v1";

    @FunctionalInterface
    interface EndpointSource {
        ProviderEndpoint get() throws IOException;
    }

    /** Owns parsed options once; every later profile refresh retains the selected policy. */
    static EndpointSource endpointSource(InetSocketAddress bind, Map<String, String> options) {
        String policy = options.get("endpointPolicy");
        if (policy != null && !policy.equals(EXPLICIT_OR_PUBLIC_LOCAL)) throw new IllegalArgumentException("Unknown provider endpoint policy");
        List<InetSocketAddress> external = externalEndpoints(options, policy != null);
        boolean localDevelopment = Boolean.parseBoolean(options.getOrDefault("localDevelopment", "false"));
        if (policy == null) return () -> ProviderEndpoint.resolve(bind, external, localDevelopment);
        return () -> {
            // A configured set never invokes interface discovery, even for an omitted family.
            var selected = external.isEmpty() ? EndpointSelection.discover(bind, external, List.of())
                    : EndpointSelection.select(bind, external, List.of());
            if (selected.candidates().isEmpty()) throw new IOException("No public local UDP endpoints; configure explicit advertised endpoints for external forwarding");
            return new ProviderEndpoint(selected.bind(), selected.candidates().stream().map(EndpointSelection.Candidate::endpoint).toList());
        };
    }

    private static List<InetSocketAddress> externalEndpoints(Map<String, String> options, boolean strict) {
        var encoded = JsonParser.parseString(options.getOrDefault("advertisedEndpoints", "[]")).getAsJsonArray();
        if (strict && encoded.size() > 32) throw new IllegalArgumentException("At most 32 configured endpoints");
        List<InetSocketAddress> parsed = new ArrayList<>();
        for (var value : encoded) {
            var address = value.getAsJsonObject();
            try {
                int port = strict ? strictPort(address.get("port")) : address.get("port").getAsInt();
                parsed.add(new InetSocketAddress(EndpointAddress.parse(address.get("address").getAsString()), port));
            } catch (java.net.UnknownHostException invalid) {
                throw new IllegalArgumentException("Advertised endpoint must be a numeric IP address", invalid);
            }
        }
        return List.copyOf(parsed);
    }

    static EndpointSelection maintainedSelection(InetSocketAddress bind, Map<String, String> options) throws IOException {
        if (!EXPLICIT_OR_PUBLIC_LOCAL.equals(options.get("endpointPolicy")))
            throw new IllegalArgumentException("Maintained publication requires the explicit-or-public-local endpoint policy");
        var external = externalEndpoints(options, true);
        return external.isEmpty() ? EndpointSelection.discover(bind, external, List.of()) : EndpointSelection.select(bind, external, List.of());
    }

    private static int strictPort(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Advertised port must be an integer between 1 and 65535");
        try {
            int port = value.getAsBigDecimal().intValueExact();
            if (port < 1 || port > 65535) throw new ArithmeticException();
            return port;
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Advertised port must be an integer between 1 and 65535", invalid);
        }
    }

    @Override
    public CompletionStage<Host> open(ServerBootstrap bootstrap, InetSocketAddress udpBind, Map<String, String> options) {
        try {
            String directory = options.get("stateDirectory");
            if (directory == null || directory.isBlank()) {
                throw new IllegalArgumentException("Provider stateDirectory required");
            }

            if (options.containsKey("controlMode")) throw new IllegalArgumentException("Obsolete provider control mode");
            String publication = options.get("candidatePublication");
            String assisted = options.getOrDefault("assistedJoins", "false");
            if (!Set.of("true", "false").contains(assisted)) throw new IllegalArgumentException("assistedJoins must be a boolean");
            String diagnostic = options.get("diagnosticAdmission");
            if (diagnostic != null && !Set.of("true", "false").contains(diagnostic))
                throw new IllegalArgumentException("diagnosticAdmission must be a boolean");
            if (publication != null) {
                if (!MAINTAINED_V1.equals(publication)) throw new IllegalArgumentException("Unknown candidate publication mode");
                var selected = maintainedSelection(udpBind, options);
                Map<EndpointSelection.Family, InetSocketAddress> servers = Map.of(); // Configured after provider discovery.
                var identity = ProviderHostIdentity.ensure(Path.of(directory));
                return NativeProviderTransport.openMaintained(bootstrap, selected, servers, identity.certificate(), identity.privateKey(), AdmissionGate.Limits.defaults(), Boolean.parseBoolean(assisted))
                        .thenApply(transport -> new Host(transport, transport.channel(), List.of()));
            }
            EndpointSource endpoints = endpointSource(udpBind, options);
            ProviderEndpoint endpoint = endpoints.get();
            var identity = ProviderHostIdentity.ensure(Path.of(directory));

            java.util.function.Supplier<List<InetSocketAddress>> candidates = () -> {
                try { return endpoints.get().advertised(); }
                catch (IOException unavailable) { throw new UncheckedIOException(unavailable); }
            };
            var opened = NativeProviderTransport.open(bootstrap, endpoint.bind(), candidates, identity.certificate(), identity.privateKey(), AdmissionGate.Limits.defaults());
            return opened.thenApply(transport -> new Host(transport, transport.channel(), endpoint.warnings()));
        } catch (Exception invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
    }
}
