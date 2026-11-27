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

package org.cloudburstmc.netty.util.nethernet;

import io.netty.util.NetUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.jspecify.annotations.Nullable;
import tel.schich.libdatachannel.StunBinding;
import tel.schich.libdatachannel.StunUdpMuxMonitor;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Asks one STUN server for this host's public address from the ICE port, through a native monitor
 * on the socket the peers share. Built-in and external signaling both announce what it finds, and
 * it logs the same way for both.
 * <p>
 * It keeps the latest mapping for each of the server's addresses, which counts while younger than
 * the max age and goes as soon as the server reports another. A monitor that fails is closed, its
 * mappings withdrawn, and opened again after {@link #RETRY_DELAY}. A server given by name is also
 * looked up again after a max age without a mapping, and asked on one address once its other one
 * proves unreachable.
 * <p>
 * Its owner polls it, one thread at a time; {@link #mappings} may be read from any.
 */
public final class StunObserver implements AutoCloseable {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(StunObserver.class);

    /** How long a failed monitor waits before it is opened again. */
    public static final Duration RETRY_DELAY = Duration.ofSeconds(30);
    /** How long a server may stay silent after opening before that is reported. */
    public static final Duration FIRST_ANSWER = Duration.ofSeconds(30);

    /**
     * What the native monitor reports for one address of the server: the mapping and its age are
     * empty before the first answer, and libjuice keeps the last mapping through later failures.
     */
    public record Reading(InetSocketAddress server, @Nullable InetSocketAddress mapped, StunBinding.State state,
                          long successfulResponses, long failedTransactions, long mappingRevision,
                          Optional<Duration> lastSuccessAge) {
        public Reading {
            Objects.requireNonNull(state);
            Objects.requireNonNull(lastSuccessAge);
            if (!concrete(server) || (mapped != null && !concrete(mapped)) || successfulResponses < 0
                    || failedTransactions < 0 || mappingRevision < 0
                    || lastSuccessAge.filter(Duration::isNegative).isPresent()) {
                throw new IllegalArgumentException("Invalid STUN reading");
            }
        }

        /** Whether the server has answered with a mapping. */
        public boolean answered() {
            return this.mapped != null && this.lastSuccessAge.isPresent() && this.successfulResponses > 0
                    && this.mappingRevision > 0;
        }

        private static boolean concrete(@Nullable InetSocketAddress endpoint) {
            return endpoint != null && !endpoint.isUnresolved() && endpoint.getPort() >= 1;
        }
    }

    /**
     * One address of the server as of the last poll.
     *
     * @param fresh           Whether its mapping counted, being younger than the max age, at that poll
     * @param usable          Whether its mapping is one players elsewhere can reach
     * @param freshUntilNanos Until when its mapping counts, on the clock it was given
     */
    public record Observed(Reading reading, boolean fresh, boolean usable, long freshUntilNanos) {
    }

    /** A native STUN monitor, or a test's stand-in for one. */
    public interface Monitor extends AutoCloseable {
        /** The server's addresses as last sampled, none before one is resolved. */
        List<Reading> read();

        @Override
        void close();
    }

    @FunctionalInterface
    public interface MonitorFactory {
        /** Opens a monitor that shares the ICE port, never a socket of its own. */
        Monitor open(InetSocketAddress server);
    }

    private final long maxAgeNanos;
    private final int icePort;
    private final MonitorFactory factory;
    private final LongSupplier nanoTime;
    private InetSocketAddress server;
    /** The one address of a named server still asked, once its other proved unreachable. */
    private @Nullable InetSocketAddress pinned;
    private @Nullable Monitor monitor;
    /** Whether the monitor failed to close, so it is kept until a replacement or close gets it closed. */
    private boolean retained;
    private long epoch;
    private long openedAt;
    private boolean failed;
    private long retryAt;
    private Map<InetSocketAddress, Observed> latest = new LinkedHashMap<>();
    private volatile List<InetSocketAddress> mappings = List.of();
    private Set<InetSocketAddress> announced = Set.of();
    private boolean started;
    private boolean answered;
    private boolean silenceReported;
    private boolean unusableReported;
    private boolean failureReported;
    private boolean closed;

    /**
     * @param server   The server, as a numeric address or an unresolved name
     * @param maxAge   How long a mapping counts after its last answer
     * @param icePort  The port the monitor and the peers share
     * @param factory  Opens the monitor
     * @param nanoTime The clock
     */
    public StunObserver(InetSocketAddress server, Duration maxAge, int icePort, MonitorFactory factory,
                        LongSupplier nanoTime) {
        if (maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("The max age must be positive");
        }
        this.server = Objects.requireNonNull(server);
        this.maxAgeNanos = saturatedNanos(maxAge);
        this.icePort = icePort;
        this.factory = Objects.requireNonNull(factory);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    /** Monitors of their own on the ICE port, sharing the mux of peers bound to the same address and port. */
    public static MonitorFactory nativeMonitors(@Nullable InetAddress bind, int port) {
        return server -> monitor(new StunUdpMuxMonitor(bind, port, server.getHostString(), server.getPort()));
    }

    /** Reads a native monitor, every address libjuice resolved the server to. */
    public static Monitor monitor(StunUdpMuxMonitor monitor) {
        return new Monitor() {
            @Override
            public List<Reading> read() {
                List<Reading> readings = new ArrayList<>(2);
                // Indices are contiguous, and the first empty one ends them
                for (int index = 0; ; index++) {
                    Optional<StunBinding> binding = monitor.binding(index);
                    if (binding.isEmpty()) {
                        return readings;
                    }
                    readings.add(reading(binding.get()));
                }
            }

            @Override
            public void close() {
                monitor.close();
            }
        };
    }

    /**
     * @throws IllegalStateException If libjuice reported an address that is not numeric
     */
    public static Reading reading(StunBinding binding) {
        try {
            InetSocketAddress server = new InetSocketAddress(EndpointAddress.parse(binding.serverAddress()),
                    binding.serverPort());
            InetSocketAddress mapped = null;
            if (!binding.mappedAddress().isEmpty() && binding.mappedPort() != 0) {
                mapped = new InetSocketAddress(EndpointAddress.parse(binding.mappedAddress()), binding.mappedPort());
            }
            return new Reading(server, mapped, binding.state(), binding.successfulResponses(),
                    binding.failedTransactions(), binding.mappingRevision(), binding.lastSuccessAge());
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Native STUN observation is not numeric", e);
        }
    }

    /**
     * Each mapping, and the same address on the ICE port when the mapping is on another, for a
     * router that moved the port on the way out but forwards the ICE port itself.
     */
    public static List<InetSocketAddress> withIcePort(Collection<InetSocketAddress> mappings, int icePort) {
        Set<InetSocketAddress> endpoints = new LinkedHashSet<>();
        for (InetSocketAddress mapped : mappings) {
            endpoints.add(mapped);
            endpoints.add(new InetSocketAddress(mapped.getAddress(), icePort));
        }
        return List.copyOf(endpoints);
    }

    /**
     * Opens the monitor now rather than on the first poll.
     *
     * @throws RuntimeException If it cannot be opened
     */
    public synchronized void start() {
        if (this.monitor == null && !this.retained && !this.closed) {
            open(this.nanoTime.getAsLong());
        }
    }

    /** Reads the monitor, opening it first when it has none and is not waiting to retry. */
    public synchronized void poll() {
        if (this.closed || this.retained) {
            return;
        }
        long now = this.nanoTime.getAsLong();
        if (this.monitor == null) {
            if (this.failed && now - this.retryAt < 0) {
                return;
            }
            try {
                open(now);
            } catch (RuntimeException e) {
                fail(now, e);
                return;
            }
        }
        List<Reading> readings;
        try {
            readings = this.monitor.read();
            check(readings);
        } catch (RuntimeException e) {
            fail(now, e);
            return;
        }
        long read = this.nanoTime.getAsLong();
        update(readings, now, read);
        this.failureReported = false;
        if (name()) {
            pinOrLookUpAgain(readings, read);
        }
    }

    /**
     * Logs what changed since the last call.
     *
     * @param covered Whether another server gives this host a public address, which turns a
     *                silent or lost server from a warning into a debug line
     */
    public synchronized void report(boolean covered) {
        if (this.closed || this.failed || this.retained) {
            return;
        }
        Set<InetSocketAddress> any = new LinkedHashSet<>();
        Set<InetSocketAddress> usable = new LinkedHashSet<>();
        for (Observed observed : this.latest.values()) {
            if (observed.fresh()) {
                any.add(observed.reading().mapped());
                if (observed.usable()) {
                    usable.add(observed.reading().mapped());
                }
            }
        }
        if (!usable.equals(this.announced)) {
            if (!usable.isEmpty()) {
                boolean moved = usable.stream().anyMatch(endpoint -> endpoint.getPort() != this.icePort);
                log.info("STUN discovery through {} maps this host to {}{}", label(), describe(usable),
                        moved ? ", a different port than udp/" + this.icePort : "");
            } else {
                warnUnless(covered, "STUN discovery through {} has no current mapping anymore", label());
            }
            this.announced = usable;
        }
        if (!any.isEmpty()) {
            this.answered = true;
            if (usable.isEmpty() && !this.unusableReported) {
                log.warn("STUN discovery through {} maps this host to {}, which players elsewhere cannot reach, "
                        + "so it is not announced", label(), describe(any));
                this.unusableReported = true;
            }
            return;
        }
        long silent = this.nanoTime.getAsLong() - this.openedAt;
        if (!this.answered && !this.silenceReported && silent >= saturatedNanos(FIRST_ANSWER)) {
            warnUnless(covered, "STUN discovery has had no answer from {} yet", label());
            this.silenceReported = true;
        }
    }

    /** The fresh mappings players elsewhere can reach, as of the last poll. */
    public List<InetSocketAddress> mappings() {
        return this.mappings;
    }

    /** Every address of the server as of the last poll, including ones carried over a reopen. */
    public synchronized List<Observed> observed() {
        return List.copyOf(this.latest.values());
    }

    /** How many monitors were opened, so a reading can be told from one of an earlier monitor. */
    public synchronized long epoch() {
        return this.epoch;
    }

    /** Whether the monitor failed and has not been opened again yet. */
    public synchronized boolean failed() {
        return this.failed || this.retained;
    }

    /**
     * Withdraws what was found and asks another server from the next poll.
     *
     * @throws IllegalStateException If closed
     * @throws RuntimeException      If the old monitor fails to close again, keeping it and the old server
     */
    public synchronized void replace(InetSocketAddress server) {
        if (this.closed) {
            throw new IllegalStateException("STUN observer closed");
        }
        withdraw();
        closeLogged();
        this.server = Objects.requireNonNull(server);
        this.pinned = null;
        this.failed = false;
        this.started = false;
        this.answered = false;
        this.silenceReported = false;
        this.unusableReported = false;
        this.failureReported = false;
    }

    /**
     * Stops for good. Closing again retries a monitor that failed to close.
     *
     * @throws RuntimeException If the monitor fails to close
     */
    @Override
    public synchronized void close() {
        this.closed = true;
        withdraw();
        closeLogged();
    }

    private void open(long now) {
        InetSocketAddress target = this.pinned != null ? this.pinned : this.server;
        this.epoch++;
        this.monitor = Objects.requireNonNull(this.factory.open(target));
        this.openedAt = now;
        this.failed = false;
        if (!this.started) {
            log.info("Running STUN discovery through {} on udp/{}", label(), this.icePort);
            this.started = true;
        }
    }

    /** Refuses a reading from another server, or of another family than the server's. */
    private void check(List<Reading> readings) {
        InetSocketAddress expected = this.pinned != null ? this.pinned : numeric(this.server);
        for (Reading reading : readings) {
            boolean source = expected == null || expected.equals(reading.server());
            boolean family = reading.mapped() == null
                    || reading.mapped().getAddress().getClass() == reading.server().getAddress().getClass();
            if (!source || !family) {
                throw new IllegalStateException("STUN reading from " + reading.server() + " does not match the server");
            }
        }
    }

    /** Takes the new readings, keeping a fresh mapping found before a reopen until it ages out. */
    private void update(List<Reading> readings, long sampledAt, long read) {
        Map<InetSocketAddress, Observed> next = new LinkedHashMap<>();
        for (Reading reading : readings) {
            Observed previous = this.latest.get(reading.server());
            if (previous == null || previous.reading().state() != reading.state()) {
                log.debug("STUN discovery through {} at {}: {}, {} answers, {} failures", label(),
                        NetUtil.toSocketAddressString(reading.server()), reading.state(),
                        reading.successfulResponses(), reading.failedTransactions());
            }
            next.put(reading.server(), observe(reading, sampledAt, read));
        }
        for (Map.Entry<InetSocketAddress, Observed> entry : this.latest.entrySet()) {
            Observed carried = entry.getValue();
            if (!next.containsKey(entry.getKey()) && carried.fresh() && read - carried.freshUntilNanos() < 0) {
                next.put(entry.getKey(), carried);
            }
        }
        this.latest = next;
        publish();
    }

    /** A mapping counts while its age, plus however long the read took, is under the max age. */
    private Observed observe(Reading reading, long sampledAt, long read) {
        if (!reading.answered()) {
            return new Observed(reading, false, false, sampledAt);
        }
        long age = saturatedNanos(reading.lastSuccessAge().orElseThrow());
        long elapsed = Math.max(0, read - sampledAt);
        boolean fresh = age < this.maxAgeNanos && elapsed < this.maxAgeNanos - age;
        boolean usable = EndpointAddress.scope(reading.mapped().getAddress()) == EndpointAddress.Scope.PUBLIC;
        return new Observed(reading, fresh, usable, fresh ? sampledAt + (this.maxAgeNanos - age) : sampledAt);
    }

    private void pinOrLookUpAgain(List<Reading> readings, long now) {
        if (this.pinned == null) {
            Reading answering = readings.stream().filter(r -> r.successfulResponses() > 0).findFirst().orElse(null);
            List<InetSocketAddress> unreachable = readings.stream()
                    .filter(r -> r.successfulResponses() == 0 && r.failedTransactions() > 0)
                    .map(Reading::server).toList();
            if (answering != null && !unreachable.isEmpty()) {
                // Spares libjuice retrying, and warning about, an address with no route every 15 seconds
                log.info("STUN discovery through {} cannot reach {} from here, asking {} only", label(),
                        describe(unreachable), NetUtil.toSocketAddressString(answering.server()));
                this.pinned = answering.server();
                this.latest.keySet().retainAll(Set.of(this.pinned));
                reopen(now);
                return;
            }
        }
        boolean mapped = this.latest.values().stream().anyMatch(Observed::fresh);
        if (!mapped && now - this.openedAt >= this.maxAgeNanos) {
            log.debug("STUN discovery through {} found no mapping in {} s, looking it up again", label(),
                    this.maxAgeNanos / 1_000_000_000L);
            this.pinned = null;
            reopen(now);
        }
    }

    /** Replaces the monitor on purpose, keeping what it found. */
    private void reopen(long now) {
        try {
            closeMonitor();
            open(now);
        } catch (RuntimeException e) {
            fail(now, e);
        }
    }

    private void fail(long now, RuntimeException e) {
        withdraw();
        this.failed = true;
        this.retryAt = now + saturatedNanos(RETRY_DELAY);
        if (!this.failureReported) {
            log.warn("STUN discovery through {} failed, retrying every {} s: {}", label(), RETRY_DELAY.toSeconds(),
                    e.toString());
            this.failureReported = true;
        } else {
            log.debug("STUN discovery through {} failed again: {}", label(), e.toString());
        }
        if (this.retained) {
            return;
        }
        try {
            closeMonitor();
        } catch (RuntimeException closing) {
            log.warn("STUN discovery through {} could not close its monitor, which stays stopped until it is "
                    + "replaced or closed: {}", label(), closing.toString());
        }
    }

    private void closeLogged() {
        try {
            closeMonitor();
        } catch (RuntimeException e) {
            log.warn("STUN discovery through {} could not close its monitor: {}", label(), e.toString());
            throw e;
        }
    }

    /** @throws RuntimeException If the monitor fails to close, keeping it */
    private void closeMonitor() {
        Monitor monitor = this.monitor;
        if (monitor == null) {
            return;
        }
        try {
            monitor.close();
        } catch (RuntimeException e) {
            this.retained = true;
            throw e;
        }
        this.monitor = null;
        this.retained = false;
    }

    private void withdraw() {
        this.latest = new LinkedHashMap<>();
        this.announced = Set.of();
        publish();
    }

    private void publish() {
        this.mappings = this.latest.values().stream().filter(o -> o.fresh() && o.usable())
                .map(o -> o.reading().mapped()).distinct().toList();
    }

    private void warnUnless(boolean covered, String message, Object... args) {
        if (covered) {
            log.debug(message, args);
        } else {
            log.warn(message, args);
        }
    }

    /** Whether the server is a name rather than an address, so its addresses may change. */
    private boolean name() {
        return numeric(this.server) == null;
    }

    private static @Nullable InetSocketAddress numeric(InetSocketAddress server) {
        if (!server.isUnresolved()) {
            return server;
        }
        try {
            return new InetSocketAddress(EndpointAddress.parse(server.getHostString()), server.getPort());
        } catch (UnknownHostException name) {
            return null;
        }
    }

    private String label() {
        return NetUtil.toSocketAddressString(this.server);
    }

    private static String describe(Collection<InetSocketAddress> endpoints) {
        return endpoints.stream().map(NetUtil::toSocketAddressString).collect(Collectors.joining(", "));
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException tooLong) {
            return Long.MAX_VALUE;
        }
    }
}
