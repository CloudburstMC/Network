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

package org.cloudburstmc.netty.signaling;

import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signaling.ProviderDiagnostic.Level;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityOutcome.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectivityLogTest {
    private static final String TARGET = "[{\"address\":\"203.0.113.10\",\"port\":19133}]";

    private final List<ProviderDiagnostic> logged = new ArrayList<>();

    private ConnectivityLog log(boolean assisted, boolean checks) {
        return new ConnectivityLog(logged::add, assisted, checks);
    }

    private static ProviderTransport.HostProfileSnapshot snapshot(long revision, String offers, String probes,
                                                                  boolean assisted) {
        var profile = JsonParser.parseString("{\"candidates\":" + offers + "}").getAsJsonObject();
        return new ProviderTransport.HostProfileSnapshot(profile, revision, revision,
                JsonParser.parseString(probes).getAsJsonArray(), assisted ? Set.of(4, 6) : Set.of(), () -> {
        });
    }

    private static ProviderTransport.ConnectivityCheck check(String region, int family, String method, String address,
                                                             ProviderTransport.ConnectivityOutcome outcome, long time) {
        return new ProviderTransport.ConnectivityCheck(region, family, method, new InetSocketAddress(address, 19133),
                outcome, time, time + 60000);
    }

    private List<String> at(Level level, String fragment) {
        return logged.stream().filter(d -> d.level() == level && d.message().contains(fragment))
                .map(ProviderDiagnostic::message).toList();
    }

    @Test
    void failuresRecoveryAndFailureAgainLogOnlyChanges() {
        var log = log(true, true);
        var snapshot = snapshot(1, "[]", "[]", true);
        log.published(snapshot);
        logged.clear();
        long now = System.currentTimeMillis() - 5000;
        var first = check("lim1", 4, "per_join", "203.0.113.10", NOT_ESTABLISHED, now);
        log.checks(snapshot, List.of(first));
        log.checks(snapshot, List.of(first)); // A heartbeat copy is not a new check
        log.checks(snapshot, List.of(check("lim1", 4, "per_join", "203.0.113.11", NOT_ESTABLISHED, now + 1)));
        log.checks(snapshot, List.of(check("lim1", 4, "per_join", "203.0.113.12", ESTABLISHED, now + 2)));
        log.checks(snapshot, List.of(check("lim1", 4, "per_join", "203.0.113.13", ESTABLISHED, now + 3)));
        log.checks(snapshot, List.of(check("lim1", 4, "per_join", "203.0.113.14", NOT_ESTABLISHED, now + 4)));
        var results = logged.stream().filter(d -> !d.message().startsWith("Check details"))
                .map(d -> d.level() + " " + d.message().substring(0, d.message().indexOf('.') + 1)).toList();
        assertEquals(List.of(
                "WARN IPv4 assisted connection check from lim1 failed.",
                "DEBUG IPv4 assisted connection check from lim1 failed.",
                "INFO IPv4 assisted connection check from lim1 passed.",
                "DEBUG IPv4 assisted connection check from lim1 passed.",
                "WARN IPv4 assisted connection check from lim1 failed."), results);
    }

    @Test
    void regionsFamiliesAndMethodsHaveIndependentResults() {
        var log = log(true, true);
        var snapshot = snapshot(1, TARGET, TARGET, true);
        log.published(snapshot);
        long now = System.currentTimeMillis() - 100;
        log.checks(snapshot, List.of(
                check("lim1", 4, "per_join", "203.0.113.10", NOT_ESTABLISHED, now),
                check("vin1", 4, "per_join", "203.0.113.10", ESTABLISHED, now),
                check("lim1", 6, "per_join", "2001:db8::1", ESTABLISHED, now),
                check("lim1", 4, "defined", "203.0.113.10", ESTABLISHED, now)));
        assertEquals(1, at(Level.WARN, "IPv4 assisted connection check from lim1 failed.").size());
        assertEquals(1, at(Level.INFO, "IPv4 assisted connection check from vin1 passed.").size());
        assertEquals(1, at(Level.INFO, "IPv6 assisted connection check from lim1 passed.").size());
        assertEquals(1, at(Level.INFO, "IPv4 direct connection check from lim1 passed.").size());
    }

    @Test
    void revisionChangesDoNotRepeatAnUnchangedFailureButNewFixedTargetsDo() {
        var log = log(false, true);
        long now = System.currentTimeMillis() - 100;
        var first = check("lim1", 4, "defined", "203.0.113.10", NOT_ESTABLISHED, now);
        log.checks(snapshot(1, TARGET, TARGET, false), List.of(first));
        log.checks(snapshot(2, TARGET, TARGET, false), List.of(first));
        log.checks(snapshot(1, TARGET, TARGET, false),
                List.of(check("lim1", 4, "defined", "203.0.113.10", ESTABLISHED, now + 10)));
        assertEquals(1, at(Level.WARN, "check from lim1 failed.").size());
        assertEquals(1, at(Level.DEBUG, "check from lim1 failed.").size());
        assertTrue(at(Level.INFO, "passed").isEmpty(), "A retired revision is ignored");
        log.checks(snapshot(2, TARGET, TARGET, false),
                List.of(check("lim1", 4, "defined", "203.0.113.11", NOT_ESTABLISHED, now + 1)));
        assertEquals(2, at(Level.WARN, "check from lim1 failed.").size());
    }

    @Test
    void withdrawalAndRecoveryKeepTheirOperatorMeaning() {
        var log = log(false, true);
        long now = System.currentTimeMillis() - 100;
        log.checks(snapshot(1, "[]", TARGET, false),
                List.of(check("lim1", 4, "warm_stun", "203.0.113.10", NOT_ESTABLISHED, now)));
        assertEquals(1, at(Level.WARN, "This address is temporarily unavailable to players.").size());
        assertEquals(1, at(Level.WARN, "Check UDP port 19133 in your firewall.").size());
        log.checks(snapshot(1, TARGET, TARGET, false),
                List.of(check("lim1", 4, "warm_stun", "203.0.113.10", ESTABLISHED, now + 1)));
        assertEquals(1, at(Level.INFO, "IPv4 is available for player connections.").size());
        assertEquals(1, at(Level.INFO, "automatically discovered connection check from lim1 passed.").size());
    }

    @Test
    void partialRegionalFailureDoesNotClaimWithdrawal() {
        var log = log(false, true);
        log.checks(snapshot(1, TARGET, TARGET, false), List.of(
                check("vin1", 4, "defined", "203.0.113.10", NOT_ESTABLISHED, System.currentTimeMillis() - 1)));
        assertEquals(1, at(Level.WARN, "Other regions can still reach this address.").size());
        assertTrue(at(Level.WARN, "temporarily unavailable").isEmpty());
    }

    @Test
    void ipv6TargetsMatchTheirCompressedCandidate() {
        var log = log(false, true);
        String offer = "[{\"address\":\"2001:db8::1\",\"port\":19133}]";
        log.checks(snapshot(1, offer, offer, false), List.of(
                check("vin1", 6, "defined", "2001:db8::1", NOT_ESTABLISHED, System.currentTimeMillis() - 1)));
        assertEquals(1, at(Level.WARN, "Other regions can still reach this address.").size());
    }

    @Test
    void inconclusiveAndUnavailableChecksRepeatAtDebugAndInvalidTimesAreIgnored() {
        var log = log(false, true);
        var snapshot = snapshot(1, TARGET, TARGET, false);
        long now = System.currentTimeMillis() - 1000;
        log.checks(snapshot, List.of(
                check("lim1", 4, "defined", "203.0.113.10", UNKNOWN, now),
                check("vin1", 4, "defined", "203.0.113.10", UNAVAILABLE, now),
                check("old", 4, "defined", "203.0.113.10", NOT_ESTABLISHED, now - 60001),
                check("future", 4, "defined", "203.0.113.10", NOT_ESTABLISHED, now + 10000)));
        log.checks(snapshot, List.of(
                check("lim1", 4, "defined", "203.0.113.10", UNKNOWN, now + 1),
                check("vin1", 4, "defined", "203.0.113.10", UNAVAILABLE, now + 1)));
        assertEquals(2, at(Level.WARN, "Player addresses are unchanged.").size());
        assertEquals(1, at(Level.DEBUG, "was inconclusive.").size());
        assertEquals(1, at(Level.DEBUG, "could not run.").size());
        assertTrue(at(Level.WARN, "failed.").isEmpty());
    }

    @Test
    void disabledChecksAreAnnouncedOnce() {
        var log = log(false, false);
        var snapshot = snapshot(1, TARGET, TARGET, false);
        log.published(snapshot);
        log.published(snapshot);
        assertEquals(1, at(Level.WARN, "Connection checks are turned off").size());
    }
}
