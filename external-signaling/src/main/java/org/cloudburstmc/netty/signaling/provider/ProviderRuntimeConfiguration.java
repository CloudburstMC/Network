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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signaling.ProviderClient;
import org.cloudburstmc.netty.signaling.ProviderCrypto;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;
import org.cloudburstmc.netty.util.nethernet.SecretValue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/**
 * Validated NXS settings, with the listener and capacity inherited from whatever is hosting.
 */
public record ProviderRuntimeConfiguration(
    URI origin, Path stateDirectory, String authorizationToken, String region, String pool,
    Map<String, String> tags, String label, String bindAddress, int udpPort,
    List<InetSocketAddress> advertisedEndpoints, int capacity, ProviderClient.ControlTransport controlTransport, boolean diagnosticAdmission,
    boolean maintainedCandidates, List<InetSocketAddress> stunServers
) {
    /**
     * @param settings   What the host has configured for the provider
     * @param directory  The host's data directory, which the state directory and any token file are
     *                   resolved against
     * @param bindAddress The address the host listens on
     * @param udpPort    The fixed UDP port the provider hands out, which cannot be ephemeral
     * @param maxPlayers The routing capacity to register with
     * @param label      The host's name, as the provider should show it
     */
    public static ProviderRuntimeConfiguration resolve(Settings settings, Path directory, String bindAddress,
                                                       int udpPort, int maxPlayers, String label) throws IOException {
        return resolve(settings, directory, bindAddress, udpPort, maxPlayers, label, InetAddress::getAllByName,
                message -> System.getLogger(ProviderRuntimeConfiguration.class.getName()).log(System.Logger.Level.WARNING, message));
    }

    @FunctionalInterface
    interface AddressResolver { InetAddress[] resolve(String name) throws UnknownHostException; }

    // DNS is resolved during host startup, never from the gameplay event loop or heartbeat.
    static ProviderRuntimeConfiguration resolve(Settings settings, Path directory, String bindAddress,
            int udpPort, int maxPlayers, String label, AddressResolver resolver, Consumer<String> warning) throws IOException {
        URI origin;
        try {
            origin = URI.create(settings.endpoint());
            ProviderCrypto.origin(origin);
        } catch (RuntimeException invalid) {
            throw new IOException("nxs.endpoint must be an HTTPS origin (HTTP is allowed only on loopback)");
        }

        String token = token(settings.token(), directory);
        Map<String, String> tags = new TreeMap<>(settings.data());
        String region = tags.remove("region"), pool = tags.remove("pool");
        if (region != null || pool != null || !tags.isEmpty()) {
            if (region == null) region = "global";
            if (pool == null) pool = "default";
        }

        Path state = directory.resolve("provider-state");
        String bind = bindAddress;
        int port = udpPort;
        if (port < 1 || port > 65535) {
            throw new IOException("NXS needs a fixed UDP port between 1 and 65535");
        }

        Set<InetSocketAddress> endpoints = new LinkedHashSet<>();
        for (String address : settings.advertiseAddresses()) {
            endpoints.add(endpoint(address));
        }

        if (endpoints.size() > 32) {
            throw new IOException("nxs.advertise-addresses allows at most 32 endpoints");
        }

        int capacity = Math.max(1, maxPlayers);
        if (capacity > 1000000) {
            throw new IOException("Invalid inherited routing capacity");
        }

        // Explicit advertised endpoints suppress DNS and STUN, including omitted families.
        var stun = endpoints.isEmpty() && settings.maintainedCandidates()
                ? resolveStunServers(settings.stunServers(), resolver, warning) : List.<InetSocketAddress>of();
        var runtime = new ProviderRuntimeConfiguration(origin, state, token, region, pool, Map.copyOf(tags), label,
            bind, port, List.copyOf(endpoints), capacity, settings.controlTransport(), settings.diagnosticAdmission(),
            settings.maintainedCandidates(), List.copyOf(stun));
        try {
            runtime.clientConfiguration();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid nxs.data: region/pool and tag names or values exceed the NXS limits");
        }

        return runtime;
    }

    /**
     * What a host's configuration has to supply, so this does not depend on how it stores it.
     *
     * @param endpoint           The provider origin
     * @param token              A bearer token, a {@code file:} path to one, or empty to register
     *                           anonymously
     * @param advertiseAddresses Reachable {@code host:port} endpoints, empty to derive them
     * @param data               Instance metadata; {@code region} and {@code pool} place it and
     *                           anything else is a registration tag
     */
    public record Settings(String endpoint, String token, List<String> advertiseAddresses,
                           Map<String, String> data, ProviderClient.ControlTransport controlTransport, boolean diagnosticAdmission,
                           boolean maintainedCandidates, List<String> stunServers) {
        public Settings { Objects.requireNonNull(controlTransport); stunServers = List.copyOf(stunServers); }
        public Settings(String endpoint, String token, List<String> advertiseAddresses, Map<String, String> data,
                        ProviderClient.ControlTransport controlTransport, boolean diagnosticAdmission) {
            this(endpoint, token, advertiseAddresses, data, controlTransport, diagnosticAdmission, false, List.of());
        }
        public Settings(String endpoint, String token, List<String> advertiseAddresses, Map<String, String> data,
                        ProviderClient.ControlTransport controlTransport) {
            this(endpoint, token, advertiseAddresses, data, controlTransport, false);
        }
        public Settings(String endpoint, String token, List<String> advertiseAddresses, Map<String, String> data) {
            this(endpoint, token, advertiseAddresses, data, ProviderClient.ControlTransport.HTTP);
        }
    }

    public String profile() {
        return "nxs-admission-v1";
    }

    public ProviderClient.Configuration clientConfiguration() {
        return new ProviderClient.Configuration(origin, profile(), label, ProviderClient.AUTOMATIC,
            authorizationToken == null ? ProviderClient.ANONYMOUS_PROOF_OF_WORK : ProviderClient.BEARER_TOKEN,
            authorizationToken, region, pool, tags, controlTransport, diagnosticAdmission, advertisedEndpoints.isEmpty() ? "discovered" : "defined");
    }

    private static List<InetSocketAddress> resolveStunServers(List<String> configured, AddressResolver resolver,
                                                             Consumer<String> warning) throws IOException {
        if (configured.size() > 2) throw new IOException("nxs.stun-servers allows at most two endpoints");
        var selected = new LinkedHashMap<Integer, InetSocketAddress>();
        for (String value : configured) {
            URI endpoint;
            try {
                endpoint = URI.create("stun://" + value);
                if (endpoint.getHost() == null || endpoint.getHost().length() > 253 || endpoint.getRawUserInfo() != null ||
                        !endpoint.getRawPath().isEmpty() || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null ||
                        endpoint.getPort() < 1 || endpoint.getPort() > 65535) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                throw new IOException("nxs.stun-servers entries must be host:port or [IPv6]:port with ports 1-65535");
            }
            String host = endpoint.getHost();
            if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
            InetAddress[] addresses;
            try { addresses = new InetAddress[] { EndpointAddress.parse(host) }; }
            catch (UnknownHostException hostname) {
                try { addresses = resolver.resolve(host); }
                catch (UnknownHostException unavailable) {
                    warning.accept("STUN server " + endpoint.getHost() + " could not be resolved; direct connectivity remains available.");
                    continue;
                }
            }
            for (InetAddress address : addresses) {
                if (EndpointAddress.scope(address) != EndpointAddress.Scope.UNUSABLE)
                    selected.putIfAbsent(address.getAddress().length, new InetSocketAddress(address, endpoint.getPort()));
            }
        }
        return List.copyOf(selected.values());
    }

    private static InetSocketAddress endpoint(String value) throws IOException {
        try {
            var match = Pattern.compile("(?:\\[([^\\]]+)\\]|([^:]+)):([0-9]{1,5})").matcher(value);
            if (!match.matches()) {
                throw new IllegalArgumentException();
            }

            int port = Integer.parseInt(match.group(3));
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException();
            }

            InetAddress address = EndpointAddress.parse(match.group(1) == null ? match.group(2) : match.group(1));
            if (EndpointAddress.scope(address) == EndpointAddress.Scope.UNUSABLE) {
                throw new IllegalArgumentException();
            }

            return new InetSocketAddress(address, port);
        } catch (Exception invalid) {
            throw new IOException("nxs.advertise-addresses entries must be numeric IPv4:port or [IPv6]:port with ports 1-65535");
        }
    }

    private static String token(String value, Path directory) throws IOException {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            value = SecretValue.resolve(value, directory).trim();
        } catch (IOException unreadable) {
            throw new IOException("nxs.token file must be readable and contain at most 16384 bytes");
        }

        if (value.isBlank() || value.length() > 16384 || value.chars().anyMatch(c -> c <= 32 || c == 127)) {
            throw new IOException("nxs.token must contain one non-empty bearer token");
        }

        return value;
    }

    public String encodedAdvertisedEndpoints() {
        var values = new JsonArray();
        for (InetSocketAddress endpoint : advertisedEndpoints) {
            var value = new JsonObject();
            value.addProperty("address", endpoint.getAddress().getHostAddress());
            value.addProperty("port", endpoint.getPort());
            values.add(value);
        }
        return values.toString();
    }

    /** Ordinary native factory options, with explicit endpoints suppressing discovery/STUN. */
    public Map<String, String> nativeHostOptions() {
        var options = new HashMap<String, String>();
        options.put("stateDirectory", stateDirectory.toString());
        options.put("advertisedEndpoints", encodedAdvertisedEndpoints());
        options.put("endpointPolicy", NativeProviderHostFactory.EXPLICIT_OR_PUBLIC_LOCAL);
        options.put("diagnosticAdmission", Boolean.toString(diagnosticAdmission));
        if (maintainedCandidates) {
            options.put("candidatePublication", NativeProviderHostFactory.MAINTAINED_V1);
            var servers = new JsonArray();
            for (var endpoint : stunServers) {
                var item = new JsonObject(); item.addProperty("address", endpoint.getAddress().getHostAddress());
                item.addProperty("port", endpoint.getPort()); servers.add(item);
            }
            options.put("stunServers", servers.toString());
        }
        return Map.copyOf(options);
    }

    @Override
    public String toString() {
        return "ProviderRuntimeConfiguration[origin=" + origin + ", profile=" + profile() + "]";
    }
}
