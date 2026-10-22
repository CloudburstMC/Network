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
import org.cloudburstmc.netty.util.nethernet.StunObserver;
import org.cloudburstmc.netty.util.nethernet.StunObserver.Monitor;
import org.cloudburstmc.netty.util.nethernet.StunObserver.Reading;
import org.junit.jupiter.api.Test;
import tel.schich.libdatachannel.StunBinding;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the background discovery hands to answers, on a clock the test moves by hand. */
class DiscoveredEndpointsTest {
    /** The port the router keeps unless a test says otherwise, so a mapping comes back as itself. */
    private static final int ICE_PORT = 40404;
    private static final InetSocketAddress PUBLIC = new InetSocketAddress("11.22.33.44", ICE_PORT);
    private static final Duration MAX_AGE = Duration.ofMinutes(5);

    private long now = 1_000_000_000L;
    private final List<FakeMonitor> opened = new ArrayList<>();
    private RuntimeException openFailure;

    private static final class FakeMonitor implements Monitor {
        final InetSocketAddress server;
        List<Reading> observed = List.of();
        RuntimeException readFailure;
        boolean closed;

        FakeMonitor(InetSocketAddress server) {
            this.server = server;
        }

        @Override
        public List<Reading> read() {
            if (this.readFailure != null) {
                throw this.readFailure;
            }
            return this.observed;
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }

    private DiscoveredEndpoints discovery(String... servers) {
        return new DiscoveredEndpoints(DiscoveredEndpoints.servers(List.of(servers)), MAX_AGE, ICE_PORT, server -> {
            if (this.openFailure != null) {
                throw this.openFailure;
            }
            FakeMonitor monitor = new FakeMonitor(server);
            this.opened.add(monitor);
            return monitor;
        }, () -> this.now);
    }

    private static InetSocketAddress address(String address, int port) {
        return new InetSocketAddress(NetUtil.createInetAddressFromIpAddressString(address), port);
    }

    /** The addresses libjuice resolves a name to, as it reports them. */
    private static final InetSocketAddress SERVER_V4 = address("74.125.250.129", 3478);
    private static final InetSocketAddress SERVER_V6 = address("2001:4860:4864:5::1", 3478);

    private static Reading seen(InetSocketAddress server, String address, int port, Duration age) {
        return seen(server, address, port, age, 1);
    }

    private static Reading seen(InetSocketAddress server, String address, int port, Duration age, long revision) {
        return new Reading(server, address(address, port), StunBinding.State.SUCCEEDED, 1, 0, revision,
                Optional.of(age));
    }

    private static Reading seen(String address, int port, Duration age) {
        return seen(SERVER_V4, address, port, age);
    }

    /** A resolved address that has not answered yet, with this many failed transactions. */
    private static Reading silent(InetSocketAddress server, long failures) {
        return new Reading(server, null, failures > 0 ? StunBinding.State.FAILED : StunBinding.State.PENDING, 0,
                failures, 0, Optional.empty());
    }

    @Test
    void handsOutOnlyFreshPublicMappings() {
        DiscoveredEndpoints discovery = discovery("stun:a.test", "stun:b.test", "stun:c.test");
        this.opened.get(0).observed = List.of(seen("11.22.33.44", ICE_PORT, Duration.ofSeconds(10)),
                seen(SERVER_V6, "2600::45", ICE_PORT, Duration.ofMinutes(6)));
        this.opened.get(1).observed = List.of(seen("10.0.0.5", ICE_PORT, Duration.ofSeconds(1)));
        // Two servers that agree give one candidate
        this.opened.get(2).observed = List.of(seen("11.22.33.44", ICE_PORT, Duration.ofSeconds(3)));

        assertEquals(List.of(), discovery.fresh(), "nothing before the first refresh");
        discovery.refresh();
        assertEquals(List.of(PUBLIC), discovery.fresh());
    }

    @Test
    void aMovedPortComesWithTheIcePortOnTheSameAddress() {
        // The router took another port on the way out, as one does when the ICE port is forwarded
        DiscoveredEndpoints discovery = discovery("stun:a.test");
        this.opened.get(0).observed = List.of(seen("11.22.33.44", 1454, Duration.ofSeconds(1)));
        discovery.refresh();

        assertEquals(List.of(new InetSocketAddress("11.22.33.44", 1454), PUBLIC), discovery.fresh());
    }

    @Test
    void aRemapDropsTheOldMappingAtOnce() {
        // The old mapping is dead once the router moved, so it would only cost a player a failed check
        DiscoveredEndpoints discovery = discovery("stun:a.test");
        this.opened.get(0).observed = List.of(seen("11.22.33.44", ICE_PORT, Duration.ofSeconds(1)));
        discovery.refresh();

        this.opened.get(0).observed = List.of(seen(SERVER_V4, "11.22.33.55", ICE_PORT, Duration.ZERO, 2));
        discovery.refresh();

        assertEquals(List.of(new InetSocketAddress("11.22.33.55", ICE_PORT)), discovery.fresh());
    }

    @Test
    void reopensAMonitorThatGoesWithoutAMappingForTheMaxAge() {
        DiscoveredEndpoints discovery = discovery("stun:a.test");
        this.now += Duration.ofMinutes(4).toNanos();
        discovery.refresh();
        assertEquals(1, this.opened.size(), "a silent monitor gets its max age before it is replaced");

        this.now += Duration.ofMinutes(1).toNanos();
        discovery.refresh();
        assertEquals(2, this.opened.size(), "which looks the name up again");
        assertTrue(this.opened.get(0).closed);
        assertFalse(this.opened.get(1).closed);
    }

    @Test
    void keepsAMonitorWhoseMappingIsFresh() {
        DiscoveredEndpoints discovery = discovery("stun:a.test");
        this.opened.get(0).observed = List.of(seen("11.22.33.44", ICE_PORT, Duration.ofSeconds(10)));
        this.now += Duration.ofMinutes(30).toNanos();
        discovery.refresh();

        assertEquals(1, this.opened.size());
        assertEquals(List.of(PUBLIC), discovery.fresh());
    }

    @Test
    void aMonitorThatFailsIsReplacedAfterTheRetryDelay() {
        DiscoveredEndpoints discovery = discovery("stun:a.test");
        this.opened.get(0).observed = List.of(seen("11.22.33.44", ICE_PORT, Duration.ofSeconds(1)));
        discovery.refresh();
        this.opened.get(0).readFailure = new IllegalStateException("STUN UDP mux monitor closed");
        assertEquals(List.of(PUBLIC), discovery.fresh(), "an answer goes out with what the last refresh found");
        assertFalse(this.opened.get(0).closed, "an answer never touches a monitor");

        discovery.refresh();
        assertTrue(this.opened.get(0).closed);
        assertEquals(List.of(), discovery.fresh(), "a failed monitor's mapping is withdrawn");

        this.openFailure = new IllegalStateException("Failed to create STUN UDP mux monitor");
        discovery.refresh();
        this.now += StunObserver.RETRY_DELAY.toNanos();
        discovery.refresh();
        assertEquals(1, this.opened.size(), "nothing is opened before the delay, and a failed reopen waits again");

        this.openFailure = null;
        this.now += StunObserver.RETRY_DELAY.toNanos();
        discovery.refresh();
        assertEquals(2, this.opened.size());
    }

    @Test
    void aServerThatCannotBeOpenedFailsTheStartAndClosesTheRest() {
        assertThrows(IllegalStateException.class, () -> new DiscoveredEndpoints(
                DiscoveredEndpoints.servers(List.of("stun:a.test", "stun:b.test")), MAX_AGE, ICE_PORT, server -> {
                    if (server.getHostString().equals("b.test")) {
                        throw new IllegalStateException("Failed to create STUN UDP mux monitor");
                    }
                    FakeMonitor monitor = new FakeMonitor(server);
                    this.opened.add(monitor);
                    return monitor;
                }, () -> this.now));

        assertEquals(1, this.opened.size());
        assertTrue(this.opened.get(0).closed, "the one already open is closed again");
    }

    @Test
    void asksOnlyTheAddressThatAnswersWhenTheOtherCannotBeReached() {
        // A host without IPv6 fails every send to the name's IPv6 address, every 15 seconds, for good
        DiscoveredEndpoints discovery = discovery("stun:a.test");
        this.opened.get(0).observed = List.of(seen("11.22.33.44", ICE_PORT, Duration.ofSeconds(1)),
                silent(SERVER_V6, 1));
        discovery.refresh();

        assertEquals(2, this.opened.size());
        assertTrue(this.opened.get(0).closed);
        assertEquals(SERVER_V4, this.opened.get(1).server, "the address that answered, as libjuice resolved it");
        assertEquals(List.of(PUBLIC), discovery.fresh(), "the mapping found before the reopen stays until it ages out");

        this.opened.get(1).observed = List.of(seen("11.22.33.44", ICE_PORT, Duration.ofSeconds(1)));
        discovery.refresh();
        assertEquals(List.of(PUBLIC), discovery.fresh());

        // Gone silent for the max age, the name is looked up again, since the address may have moved
        this.opened.get(1).observed = List.of(silent(SERVER_V4, 20));
        this.now += Duration.ofMinutes(5).toNanos();
        discovery.refresh();
        assertEquals(3, this.opened.size());
        assertEquals("a.test", this.opened.get(2).server.getHostString());
    }

    @Test
    void keepsAnAddressWhoseFirstAnswerIsStillPending() {
        // A dual stack host whose IPv6 reply is slow must not lose it
        DiscoveredEndpoints discovery = discovery("stun:a.test");
        this.opened.get(0).observed = List.of(seen("11.22.33.44", ICE_PORT, Duration.ofSeconds(1)),
                silent(SERVER_V6, 0));
        discovery.refresh();

        assertEquals(1, this.opened.size());
        assertFalse(this.opened.get(0).closed);
    }

    @Test
    void warnsAboutASilentServerOnlyWhileNoOtherOneAnswers() {
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(StunObserver.class.getName());
        logger.addHandler(capture);
        try {
            // One blocked where this host is, the other standing in for it
            DiscoveredEndpoints discovery = discovery("stun:a.test", "stun:b.test");
            this.opened.get(1).observed = List.of(seen("11.22.33.44", ICE_PORT, Duration.ofSeconds(1)));
            this.now += StunObserver.FIRST_ANSWER.toNanos();
            discovery.refresh();
            assertEquals(List.of(), warnings, "a.test is not missed while b.test answers");

            // Once neither gives an address, answers carry none from STUN, which is worth one
            this.now += MAX_AGE.toNanos();
            this.opened.get(1).observed = List.of(seen("11.22.33.44", ICE_PORT, MAX_AGE.plusSeconds(1)));
            discovery.refresh();
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).getMessage().contains("b.test:3478 has no current mapping"),
                    warnings.get(0).getMessage());

            DiscoveredEndpoints alone = discovery("stun:c.test");
            this.now += StunObserver.FIRST_ANSWER.toNanos();
            alone.refresh();
            assertEquals(2, warnings.size());
            assertTrue(warnings.get(1).getMessage().contains("no answer from c.test:3478"),
                    warnings.get(1).getMessage());
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    void closingStopsEveryMonitorForGood() {
        DiscoveredEndpoints discovery = discovery("stun:a.test", "stun:b.test");
        discovery.close();
        assertTrue(this.opened.stream().allMatch(monitor -> monitor.closed));

        this.now += Duration.ofMinutes(10).toNanos();
        discovery.refresh();
        assertEquals(2, this.opened.size(), "nothing is reopened once closed");
        assertEquals(List.of(), discovery.fresh());
    }

    @Test
    void readsTheUrlFormsIceServerListsUse() {
        List<InetSocketAddress> servers = DiscoveredEndpoints.servers(List.of("stun:stun.example.test",
                "STUN:stun.example.test:19302", "stun:198.51.100.7:3479", "stun:[2001:db8::1]",
                "stun:[2001:db8::1]:19302", "stun:stun.example.test"));

        assertEquals(List.of(InetSocketAddress.createUnresolved("stun.example.test", 3478),
                InetSocketAddress.createUnresolved("stun.example.test", 19302),
                InetSocketAddress.createUnresolved("198.51.100.7", 3479),
                InetSocketAddress.createUnresolved("2001:db8::1", 3478),
                InetSocketAddress.createUnresolved("2001:db8::1", 19302)), servers, "once each");
        assertTrue(servers.stream().allMatch(InetSocketAddress::isUnresolved), "nothing is looked up");
    }

    @Test
    void refusesWhatIsNotAPlainStunUrl() {
        for (String url : List.of("turn:turn.example.test", "stuns:stun.example.test", "stun.example.test:3478",
                "stun:", "stun:2001:db8::1", "stun:[2001:db8::1", "stun:[2001:db8::1]3478", "stun:host:0",
                "stun:host:65536", "stun:host:port", "stun:user@host", "stun:host?transport=udp")) {
            assertThrows(IllegalArgumentException.class, () -> DiscoveredEndpoints.servers(List.of(url)), url);
        }
    }
}
