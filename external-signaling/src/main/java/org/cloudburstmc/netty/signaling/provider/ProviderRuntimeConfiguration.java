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

import org.cloudburstmc.netty.channel.nethernet.signaling.AdvertisedAddresses;
import org.cloudburstmc.netty.signaling.ProviderClient;
import org.cloudburstmc.netty.signaling.ProviderOrigin;
import org.cloudburstmc.netty.util.nethernet.SecretValue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

/**
 * Validated NXS settings, with the listener and capacity inherited from whatever is hosting.
 */
public final class ProviderRuntimeConfiguration {
    private final URI origin;
    private final Path stateDirectory;
    private final String authorizationToken;
    private final String label;
    private final String bindAddress;
    private final int udpPort;
    private final List<InetSocketAddress> advertisedEndpoints;
    private final int capacity;
    private final Settings settings;

    private ProviderRuntimeConfiguration(URI origin, Path stateDirectory, String authorizationToken, String label,
                                         String bindAddress, int udpPort, List<InetSocketAddress> advertisedEndpoints,
                                         int capacity, Settings settings) {
        this.origin = origin;
        this.stateDirectory = stateDirectory;
        this.authorizationToken = authorizationToken;
        this.label = label;
        this.bindAddress = bindAddress;
        this.udpPort = udpPort;
        this.advertisedEndpoints = advertisedEndpoints;
        this.capacity = capacity;
        this.settings = settings;
    }

    /**
     * @param settings   What the host has configured for the provider
     * @param directory  The host's data directory, which the state directory and any token file are
     *                   resolved against
     * @param bindAddress The address the host listens on
     * @param udpPort    The fixed UDP port the provider hands out, which cannot be ephemeral
     * @param maxPlayers The routing capacity to register with
     * @throws IOException If a setting is invalid, or an advertised name does not resolve
     */
    public static ProviderRuntimeConfiguration resolve(Settings settings, Path directory, String bindAddress,
                                                       int udpPort, int maxPlayers) throws IOException {
        URI origin;
        try {
            origin = URI.create(ProviderOrigin.canonicalize(URI.create(settings.endpoint())));
        } catch (RuntimeException invalid) {
            throw new IOException("nxs.endpoint must be an HTTPS origin (HTTP is allowed only on loopback)");
        }

        String token = token(settings.token(), directory);
        String label = label(settings.label());

        Path state = directory.resolve("provider-state");
        String bind = bindAddress;
        int port = udpPort;
        if (port < 1 || port > 65535) {
            throw new IOException("NXS needs a fixed UDP port between 1 and 65535");
        }

        List<InetSocketAddress> endpoints;
        try {
            endpoints = AdvertisedAddresses.resolve(AdvertisedAddresses.parse(settings.advertiseAddresses(), port));
        } catch (IllegalArgumentException invalid) {
            throw new IOException(invalid.getMessage(), invalid);
        }

        int capacity = Math.max(1, maxPlayers);
        if (capacity > 1000000) {
            throw new IOException("Invalid inherited routing capacity");
        }

        var runtime =
                new ProviderRuntimeConfiguration(
                        origin,
                        state,
                        token,
                        label,
                        bind,
                        port,
                        endpoints,
                        capacity,
                        settings);
        try {
            runtime.clientConfiguration();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid nxs settings: " + invalid.getMessage());
        }

        return runtime;
    }

    /**
     * What a host's configuration has to supply, so this does not depend on how it stores it.
     */
    public static final class Settings {
        private final String endpoint;
        private final String token;
        private final List<String> advertiseAddresses;
        private final String label;
        private final boolean connectionChecks;
        private final boolean stunDiscovery;
        private final boolean assistedJoins;
        private final boolean deregisterOnShutdown;
        private final boolean gameOutcomes;
        private final ProviderClient.Location location;

        private Settings(Builder builder) {
            endpoint = builder.endpoint;
            token = builder.token;
            advertiseAddresses = builder.advertiseAddresses;
            label = builder.label;
            connectionChecks = builder.connectionChecks;
            stunDiscovery = builder.stunDiscovery;
            assistedJoins = builder.assistedJoins;
            deregisterOnShutdown = builder.deregisterOnShutdown;
            gameOutcomes = builder.gameOutcomes;
            location = builder.location;
        }

        /**
         * @param endpoint The provider origin
         */
        public static Builder builder(String endpoint) {
            return new Builder(endpoint);
        }

        public String endpoint() {
            return endpoint;
        }

        public String token() {
            return token;
        }

        public List<String> advertiseAddresses() {
            return advertiseAddresses;
        }

        public String label() {
            return label;
        }

        public boolean connectionChecks() {
            return connectionChecks;
        }

        public boolean stunDiscovery() {
            return stunDiscovery;
        }

        public boolean assistedJoins() {
            return assistedJoins;
        }

        public boolean deregisterOnShutdown() {
            return deregisterOnShutdown;
        }

        public boolean gameOutcomes() {
            return gameOutcomes;
        }

        public ProviderClient.Location location() {
            return location;
        }

        public static final class Builder {
            private final String endpoint;
            private String token;
            private List<String> advertiseAddresses = List.of();
            private String label;
            private boolean connectionChecks;
            private boolean stunDiscovery;
            private boolean assistedJoins;
            private boolean deregisterOnShutdown;
            private boolean gameOutcomes;
            private ProviderClient.Location location;

            private Builder(String endpoint) {
                this.endpoint = endpoint;
            }

            /**
             * @param token A bearer token, a {@code file:} path to one, or empty to register anonymously
             */
            public Builder token(String token) {
                this.token = token;
                return this;
            }

            /**
             * @param advertiseAddresses Reachable endpoints as {@link AdvertisedAddresses} reads them, one
             *                           without a port on the UDP port, empty to derive them
             */
            public Builder advertiseAddresses(List<String> advertiseAddresses) {
                this.advertiseAddresses = List.copyOf(advertiseAddresses);
                return this;
            }

            /**
             * @param label The instance's initial name at the provider, empty for the host name
             */
            public Builder label(String label) {
                this.label = label;
                return this;
            }

            /**
             * @param connectionChecks Whether the provider may check reachability from its regions, through
             *                         diagnostic admission
             */
            public Builder connectionChecks(boolean connectionChecks) {
                this.connectionChecks = connectionChecks;
                return this;
            }

            /**
             * @param stunDiscovery Whether to find and maintain public addresses through the provider's STUN
             *                      servers when none are advertised
             */
            public Builder stunDiscovery(boolean stunDiscovery) {
                this.stunDiscovery = stunDiscovery;
                return this;
            }

            /**
             * @param assistedJoins Whether the provider may help players connect over its WebSocket, which
             *                      this then uses for every operation
             */
            public Builder assistedJoins(boolean assistedJoins) {
                this.assistedJoins = assistedJoins;
                return this;
            }

            /**
             * @param deregisterOnShutdown Whether to end the registration on shutdown, for hosts whose
             *                             state directory does not persist
             */
            public Builder deregisterOnShutdown(boolean deregisterOnShutdown) {
                this.deregisterOnShutdown = deregisterOnShutdown;
                return this;
            }

            /**
             * @see ProviderClient.Configuration.Builder#gameOutcomes
             */
            public Builder gameOutcomes(boolean gameOutcomes) {
                this.gameOutcomes = gameOutcomes;
                return this;
            }

            /**
             * @see ProviderClient.Configuration.Builder#location
             */
            public Builder location(ProviderClient.Location location) {
                this.location = location;
                return this;
            }

            public Settings build() {
                return new Settings(this);
            }
        }
    }

    public URI origin() {
        return origin;
    }

    public Path stateDirectory() {
        return stateDirectory;
    }

    public String label() {
        return label;
    }

    public String bindAddress() {
        return bindAddress;
    }

    public int udpPort() {
        return udpPort;
    }

    public int capacity() {
        return capacity;
    }

    String authorizationToken() {
        return authorizationToken;
    }

    List<InetSocketAddress> advertisedEndpoints() {
        return advertisedEndpoints;
    }

    boolean stunDiscovery() {
        return settings.stunDiscovery();
    }

    boolean assistedJoins() {
        return settings.assistedJoins();
    }

    public ProviderClient.Configuration clientConfiguration() {
        return ProviderClient.Configuration.builder(origin)
                .label(label)
                .authorizationToken(authorizationToken)
                // Only assistance needs the provider's WebSocket
                .controlTransport(settings.assistedJoins() ? ProviderClient.ControlTransport.AUTO : ProviderClient.ControlTransport.HTTP)
                .diagnosticAdmission(settings.connectionChecks())
                .connectivityMethod(advertisedEndpoints.isEmpty() ? "discovered" : "defined")
                .assistedJoins(settings.assistedJoins())
                .deregisterOnShutdown(settings.deregisterOnShutdown())
                .gameOutcomes(settings.gameOutcomes())
                .location(settings.location())
                .build();
    }

    private static String label(String configured) throws IOException {
        if (configured != null && !configured.isBlank()) {
            configured = configured.strip();
            if (configured.codePointCount(0, configured.length()) > 128) {
                throw new IOException("The NXS label allows at most 128 characters");
            }
            return configured;
        }

        // Container runtimes set HOSTNAME to the pod or container name.
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (IOException | RuntimeException unavailable) {
                return null;
            }
        }

        host = host.strip();
        return host.isEmpty() ? null : host.substring(0, Math.min(host.length(), 128));
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

    @Override
    public String toString() {
        return "ProviderRuntimeConfiguration[origin=" + origin + "]";
    }
}
