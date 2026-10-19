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

import io.netty.util.NetUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;
import org.jspecify.annotations.Nullable;
import tel.schich.libdatachannel.StunBinding;
import tel.schich.libdatachannel.StunUdpMuxMonitor;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Asks STUN servers for this host's public address from the ICE port, one monitor per server on the
 * socket the peers share, and hands out the mappings that are still fresh. {@link #refresh} reads
 * the monitors on the loop and keeps what {@link #fresh} hands to answers, so an answer never
 * touches a monitor.
 */
final class DiscoveredEndpoints implements AutoCloseable {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(DiscoveredEndpoints.class);

    /** The STUN port, for a URL that names none. */
    static final int DEFAULT_PORT = 3478;
    static final Duration DEFAULT_MAX_AGE = Duration.ofMinutes(5);
    /**
     * How often {@link #refresh} should run, which is also how late a new mapping reaches answers;
     * libjuice refreshes the mappings on its own.
     */
    static final int REFRESH_SECONDS = 5;
    /** When the first {@link #refresh} should run, soon enough to catch the first answers. */
    static final int FIRST_REFRESH_SECONDS = 1;
    /** How long a server may stay silent after opening before that is reported. */
    static final Duration FIRST_ANSWER = Duration.ofSeconds(30);

    /** One server's observations, one per address its name resolved to. */
    interface Monitor extends AutoCloseable {
        List<Observation> read();

        @Override
        void close();
    }

    @FunctionalInterface
    interface MonitorFactory {
        Monitor open(InetSocketAddress server);
    }

    /**
     * One resolved address of a server: the mapping it reports and how long ago that was last
     * confirmed, each null before the first answer, and how many transactions answered and failed.
     */
    record Observation(InetSocketAddress server, @Nullable InetSocketAddress mapped, @Nullable Duration age,
                       long answers, long failures) {
    }

    private static final class Lane {
        final InetSocketAddress server;
        /** The one resolved address asked instead of the name, once the others proved unreachable. */
        @Nullable InetSocketAddress pinned;
        @Nullable Monitor monitor;
        long openedAt;
        /** When each mapping was last confirmed, kept across a reopen so it stays announced until the max age. */
        final Map<InetSocketAddress, Long> confirmed = new LinkedHashMap<>();
        Set<InetSocketAddress> announced = Set.of();
        boolean answered;
        boolean silenceReported;
        boolean unusableReported;
        boolean openFailureReported;

        Lane(InetSocketAddress server) {
            this.server = server;
        }

        String name() {
            return NetUtil.toSocketAddressString(this.server);
        }
    }

    private final long maxAgeNanos;
    private final MonitorFactory factory;
    private final LongSupplier nanoTime;
    private final int icePort;
    private final List<Lane> lanes = new ArrayList<>();
    private volatile List<InetSocketAddress> fresh = List.of();
    private boolean closed;

    /**
     * Opens a monitor for every server.
     *
     * @param servers The servers to ask, from {@link #servers}
     * @param maxAge  How long a mapping is still handed out after its last answer
     * @param icePort The port the monitors and the peers share
     * @throws RuntimeException If one cannot be opened, with any already opened closed again
     */
    DiscoveredEndpoints(List<InetSocketAddress> servers, Duration maxAge, int icePort, MonitorFactory factory,
                        LongSupplier nanoTime) {
        this.maxAgeNanos = maxAge.toNanos();
        this.icePort = icePort;
        this.factory = factory;
        this.nanoTime = nanoTime;
        long now = nanoTime.getAsLong();
        try {
            for (InetSocketAddress server : servers) {
                Lane lane = new Lane(server);
                lane.monitor = factory.open(server);
                lane.openedAt = now;
                this.lanes.add(lane);
            }
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    /**
     * Reads {@code stun:host[:port]} URLs, the form ICE server lists use, without looking anything up.
     *
     * @param urls The URLs to read
     * @return Each as an unresolved host and port
     * @throws IllegalArgumentException If one is anything else, such as a {@code turn:} or {@code stuns:} one
     */
    static List<InetSocketAddress> servers(Collection<String> urls) {
        return urls.stream().map(DiscoveredEndpoints::server).distinct().toList();
    }

    private static InetSocketAddress server(String url) {
        String value = url == null ? "" : url.trim();
        InetSocketAddress server = value.regionMatches(true, 0, "stun:", 0, 5)
                ? HostPort.parse(value.substring(5), DEFAULT_PORT) : null;
        if (server == null) {
            throw new IllegalArgumentException("Expected a stun:host[:port] URL, got " + url);
        }
        return server;
    }

    /**
     * Monitors that share the mux socket of peers bound with the same address and port.
     *
     * @param bind The address ICE binds to, null for libjuice's wildcard
     * @param port The fixed ICE port
     */
    static MonitorFactory nativeMonitors(@Nullable InetAddress bind, int port) {
        return server -> {
            StunUdpMuxMonitor monitor = new StunUdpMuxMonitor(bind, port, server.getHostString(), server.getPort());
            return new Monitor() {
                @Override
                public List<Observation> read() {
                    List<Observation> observed = new ArrayList<>(2);
                    // libjuice keeps at most two resolved addresses per server
                    for (int index = 0; index < 2; index++) {
                        monitor.binding(index).ifPresent(binding -> observed.add(observation(binding)));
                    }
                    return observed;
                }

                @Override
                public void close() {
                    monitor.close();
                }
            };
        };
    }

    private static Observation observation(StunBinding binding) {
        InetAddress mapped = binding.mappedAddress().isEmpty() ? null
                : NetUtil.createInetAddressFromIpAddressString(binding.mappedAddress());
        InetSocketAddress endpoint = mapped == null || binding.mappedPort() == 0 ? null
                : new InetSocketAddress(mapped, binding.mappedPort());
        return new Observation(InetSocketAddress.createUnresolved(binding.serverAddress(), binding.serverPort()),
                endpoint, binding.lastSuccessAge().orElse(null), binding.successfulResponses(),
                binding.failedTransactions());
    }

    /**
     * The fresh public mappings as of the last {@link #refresh}, for an answer to carry. One on
     * another port than the ICE port comes with its address on the ICE port too, see
     * {@link NetherNetHTTPServerSignaling.Builder#setStunServers}.
     *
     * @return The endpoints, empty when there are none
     */
    List<InetSocketAddress> fresh() {
        return this.fresh;
    }

    /**
     * Reads every monitor, keeps the mappings {@link #fresh} hands out, reports what changed since
     * the last call, and reopens a monitor that went {@code maxAge} without a fresh mapping, which
     * also looks its name up again. Called on a timer.
     */
    synchronized void refresh() {
        if (this.closed) {
            return;
        }
        long now = this.nanoTime.getAsLong();
        List<Lane> read = new ArrayList<>();
        for (Lane lane : this.lanes) {
            if (lane.monitor == null) {
                reopen(lane, now);
            } else {
                List<Observation> observed = read(lane);
                if (observed != null) {
                    confirm(lane, observed, now);
                    if (!dropUnreachable(lane, observed, now)) {
                        read.add(lane);
                    }
                }
            }
            lane.confirmed.values().removeIf(confirmedAt -> now - confirmedAt >= this.maxAgeNanos);
        }
        this.fresh = snapshot();
        // A server that goes quiet, as one blocked where this host is does, only matters while no other answers
        boolean covered = !this.fresh.isEmpty();
        for (Lane lane : read) {
            report(lane, covered, now);
        }
    }

    /** Notes when each mapping the monitor reports was last confirmed. */
    private static void confirm(Lane lane, List<Observation> observed, long now) {
        for (Observation observation : observed) {
            if (observation.mapped() != null && observation.age() != null) {
                lane.confirmed.merge(observation.mapped(), now - observation.age().toNanos(), Math::max);
            }
        }
    }

    /** What {@link #fresh} hands out: every lane's public mappings, each also on the ICE port. */
    private List<InetSocketAddress> snapshot() {
        Set<InetSocketAddress> fresh = new LinkedHashSet<>();
        for (Lane lane : this.lanes) {
            for (InetSocketAddress mapped : mappings(lane, true)) {
                fresh.add(mapped);
                fresh.add(new InetSocketAddress(mapped.getAddress(), this.icePort));
            }
        }
        return List.copyOf(fresh);
    }

    private void report(Lane lane, boolean covered, long now) {
        Set<InetSocketAddress> mapped = mappings(lane, false);
        Set<InetSocketAddress> announced = mapped.stream().filter(DiscoveredEndpoints::isPublic)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!announced.equals(lane.announced)) {
            if (!announced.isEmpty()) {
                boolean moved = announced.stream().anyMatch(endpoint -> endpoint.getPort() != this.icePort);
                log.info("STUN discovery through {} maps this host to {}{}", lane.name(), describe(announced),
                        moved ? ", also announced on udp/" + this.icePort : "");
            } else {
                warnUnless(covered, "STUN discovery through {} has no current mapping anymore, answers carry "
                        + "none from it", lane.name());
            }
            lane.announced = announced;
        }

        if (!mapped.isEmpty()) {
            lane.answered = true;
            if (announced.isEmpty() && !lane.unusableReported) {
                log.warn("STUN discovery through {} maps this host to {}, which players elsewhere cannot "
                        + "reach, so it is not announced", lane.name(), describe(mapped));
                lane.unusableReported = true;
            }
            return;
        }

        long silent = now - lane.openedAt;
        if (!lane.answered && !lane.silenceReported && silent >= FIRST_ANSWER.toNanos()) {
            warnUnless(covered, "STUN discovery has had no answer from {} yet", lane.name());
            lane.silenceReported = true;
        }
        if (silent >= this.maxAgeNanos) {
            lane.pinned = null;
            closeMonitor(lane);
            reopen(lane, now);
        }
    }

    /** A warning while no server gives this host a public address, since then answers carry none. */
    private static void warnUnless(boolean covered, String message, Object... args) {
        if (covered) {
            log.debug(message, args);
        } else {
            log.warn(message, args);
        }
    }

    /**
     * Asks only the one address that answered once the others have failed without ever answering,
     * such as an IPv6 one on a host with no IPv6 route, which libjuice would otherwise retry and
     * warn about every 15 seconds. One still pending is left alone, so a slow reply is not dropped.
     *
     * @return Whether the monitor was replaced
     */
    private boolean dropUnreachable(Lane lane, List<Observation> observed, long now) {
        if (lane.pinned != null) {
            return false;
        }
        Observation answering = observed.stream().filter(o -> o.answers() > 0).findFirst().orElse(null);
        List<InetSocketAddress> unreachable = observed.stream().filter(o -> o.answers() == 0 && o.failures() > 0)
                .map(Observation::server).toList();
        if (answering == null || unreachable.isEmpty()) {
            return false;
        }
        log.info("STUN discovery through {} cannot reach {} from here, asking {} only", lane.name(),
                describe(unreachable), NetUtil.toSocketAddressString(answering.server()));
        lane.pinned = answering.server();
        closeMonitor(lane);
        reopen(lane, now);
        return true;
    }

    /** What the lane's monitor reports, or null when it has none or it just failed. */
    private @Nullable List<Observation> read(Lane lane) {
        if (lane.monitor == null) {
            return null;
        }
        try {
            return lane.monitor.read();
        } catch (RuntimeException e) {
            log.warn("STUN discovery through {} failed, reopening it: {}", lane.name(), e.toString());
            closeMonitor(lane);
            return null;
        }
    }

    /** The lane's mappings confirmed within the max age, public ones only if asked. */
    private static Set<InetSocketAddress> mappings(Lane lane, boolean publicOnly) {
        Set<InetSocketAddress> fresh = new LinkedHashSet<>();
        for (InetSocketAddress mapped : lane.confirmed.keySet()) {
            if (!publicOnly || isPublic(mapped)) {
                fresh.add(mapped);
            }
        }
        return fresh;
    }

    private void reopen(Lane lane, long now) {
        if (this.closed) {
            return;
        }
        try {
            lane.monitor = this.factory.open(lane.pinned != null ? lane.pinned : lane.server);
            lane.openedAt = now;
            lane.openFailureReported = false;
        } catch (RuntimeException e) {
            if (!lane.openFailureReported) {
                log.warn("Could not reopen STUN discovery through {}, retrying: {}", lane.name(), e.toString());
                lane.openFailureReported = true;
            }
        }
    }

    private void closeMonitor(Lane lane) {
        Monitor monitor = lane.monitor;
        lane.monitor = null;
        if (monitor != null) {
            try {
                monitor.close();
            } catch (RuntimeException e) {
                log.debug("Closing STUN discovery through {} failed", lane.name(), e);
            }
        }
    }

    private static boolean isPublic(InetSocketAddress endpoint) {
        return EndpointAddress.scope(endpoint.getAddress()) == EndpointAddress.Scope.PUBLIC;
    }

    private static String describe(Collection<InetSocketAddress> endpoints) {
        return endpoints.stream().map(NetUtil::toSocketAddressString).collect(Collectors.joining(", "));
    }

    @Override
    public synchronized void close() {
        this.closed = true;
        this.fresh = List.of();
        for (Lane lane : this.lanes) {
            closeMonitor(lane);
        }
    }
}
