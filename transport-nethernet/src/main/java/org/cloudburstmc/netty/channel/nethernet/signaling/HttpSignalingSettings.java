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

package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.jspecify.annotations.Nullable;

import javax.net.ssl.SSLContext;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * How {@link NetherNetHTTPClientSignaling} reaches an endpoint. Immutable; {@link #DEFAULT} does
 * what the retail client does. Share one across connections: the HTTP client behind it is built
 * once per settings object, and a signaling is made per connection.
 */
public final class HttpSignalingSettings {

    /** Which scheme the signaling speaks. */
    public enum Scheme {
        /**
         * Probe first, HTTPS then plaintext, and speak whichever answered. This is what the retail
         * client does. The probe costs one round trip per connect. A host that does not answer
         * fails at once; only one that answers without TLS gets the plaintext attempt. Whoever can
         * make the HTTPS attempt look like that gets a plaintext join, so a hop that has to stay
         * private uses {@link #HTTPS}.
         */
        AUTO,
        /** HTTPS only: no probe, and no plaintext fallback. */
        HTTPS,
        /** Plaintext only, with no probe. */
        HTTP
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    public static final HttpSignalingSettings DEFAULT = new HttpSignalingSettings(Scheme.AUTO, List.of(), null);

    private final Scheme scheme;
    private final List<IceServerInfo> iceServers;
    private final @Nullable SSLContext sslContext;
    private volatile @Nullable HttpClient http;

    private HttpSignalingSettings(Scheme scheme, List<IceServerInfo> iceServers, @Nullable SSLContext sslContext) {
        this.scheme = scheme;
        this.iceServers = iceServers;
        this.sslContext = sslContext;
    }

    public Scheme scheme() {
        return this.scheme;
    }

    public List<IceServerInfo> iceServers() {
        return this.iceServers;
    }

    /** The trust HTTPS validates the server against, or null for the JDK default. */
    public @Nullable SSLContext sslContext() {
        return this.sslContext;
    }

    public HttpSignalingSettings withScheme(Scheme scheme) {
        return new HttpSignalingSettings(scheme, this.iceServers, this.sslContext);
    }

    /**
     * @param iceServers The STUN and TURN servers to gather through. Every one of them has to
     *                   answer or time out before the offer can go, since nothing trickles after it
     * @return The settings with those servers
     */
    public HttpSignalingSettings withIceServers(Collection<IceServerInfo> iceServers) {
        return new HttpSignalingSettings(this.scheme, List.copyOf(iceServers), this.sslContext);
    }

    /**
     * @param sslContext The trust to validate the server's certificate with, for a private CA
     * @return The settings with that trust
     */
    public HttpSignalingSettings withSslContext(SSLContext sslContext) {
        return new HttpSignalingSettings(this.scheme, this.iceServers, sslContext);
    }

    HttpClient http() {
        HttpClient client = this.http;
        if (client == null) {
            synchronized (this) {
                client = this.http;
                if (client == null) {
                    HttpClient.Builder builder = HttpClient.newBuilder()
                            .connectTimeout(CONNECT_TIMEOUT)
                            .followRedirects(HttpClient.Redirect.NEVER);
                    if (this.sslContext != null) {
                        builder.sslContext(this.sslContext);
                    }
                    this.http = client = builder.build();
                }
            }
        }
        return client;
    }
}
