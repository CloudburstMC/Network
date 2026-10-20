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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityCheck;
import org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityOutcome;
import org.cloudburstmc.netty.signaling.ProviderTransport.HostProfileSnapshot;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Operator-facing reachability observations; never changes serving or admission. Executor-owned. */
final class ConnectivityLog {
    private record Key(String region, int family, String method) {
    }

    private record Observation(long revision, ConnectivityCheck check) {
    }

    private final Consumer<ProviderDiagnostic> sink;
    private final boolean assisted;
    private final boolean checks;
    private final Map<Key, Observation> reported = new HashMap<>();
    private final Map<Integer, String> publications = new HashMap<>();
    private final Map<Integer, Boolean> available = new HashMap<>();
    private long revision;
    private boolean announced;

    ConnectivityLog(Consumer<ProviderDiagnostic> sink, boolean assisted, boolean checks) {
        this.sink = sink;
        this.assisted = assisted;
        this.checks = checks;
    }

    /** Logs which families players can reach, when that changes. */
    void published(HostProfileSnapshot snapshot) {
        if (!announced) {
            announced = true;
            debug("Assisted joins " + (assisted ? "on" : "off") + ", connection checks " + (checks ? "on" : "off") + ".");
            if (!checks) {
                warn("Connection checks are turned off, so server reachability cannot be checked.");
            }
        }
        JsonArray candidates = snapshot.profile().getAsJsonArray("candidates");
        JsonArray probes = snapshot.probeCandidates();
        for (int family : List.of(4, 6)) {
            var offered = endpoints(candidates, family);
            boolean assist = assisted && snapshot.assistedFamilies().contains(family);
            String decision = "offered=" + offered + ", test targets=" + endpoints(probes, family) + ", assisted=" + assist;
            if (decision.equals(publications.put(family, decision))) {
                continue;
            }
            boolean usable = !offered.isEmpty() || assist;
            Boolean wasUsable = available.put(family, usable);
            if (!Objects.equals(wasUsable, usable)) {
                if (usable) {
                    info("IPv" + family + " is available for player connections.");
                } else if (Boolean.TRUE.equals(wasUsable) || !endpoints(probes, family).isEmpty()) {
                    warn("IPv" + family + " has no available player address." + (checks ? " Connection checks will continue." : ""));
                }
            }
            debug("IPv" + family + " addresses: " + decision + ".");
        }
    }

    /** Logs new check results once; an unchanged result repeats only at debug. */
    void checks(HostProfileSnapshot snapshot, List<ConnectivityCheck> results) {
        if (snapshot.candidateRevision() < revision) {
            return;
        }
        revision = snapshot.candidateRevision();
        published(snapshot);
        JsonArray candidates = snapshot.profile().getAsJsonArray("candidates");
        long now = System.currentTimeMillis();
        for (var check : results.stream().sorted(Comparator.comparingLong(ConnectivityCheck::checkedAt)).toList()) {
            if (check.checkedAt() > now || check.expiresAt() <= now) {
                continue;
            }
            var key = new Key(check.region(), check.family(), check.method());
            var observation = reported.get(key);
            var previous = observation == null ? null : observation.check();
            if (observation != null && observation.revision() == revision && previous.checkedAt() >= check.checkedAt()) {
                continue;
            }
            reported.put(key, new Observation(revision, check));
            String message = "IPv" + check.family() + " " + method(check.method()) + " connection check from "
                    + check.region() + " " + result(check.outcome()) + ".";
            // An assisted check reaches a different temporary peer each time; that alone is not a new result.
            boolean unchanged = previous != null && previous.outcome() == check.outcome()
                    && ("per_join".equals(check.method()) || Objects.equals(previous.target(), check.target()));
            if (unchanged) {
                debug(message);
            } else if (check.outcome() == ConnectivityOutcome.ESTABLISHED) {
                info(message);
            } else if (check.outcome() == ConnectivityOutcome.NOT_ESTABLISHED) {
                boolean assist = assisted && snapshot.assistedFamilies().contains(check.family());
                boolean offered = check.target() != null
                        && endpoints(candidates, check.family()).contains(endpoint(check.target()));
                String impact = assist ? " Some players may still be able to connect."
                        : check.target() == null ? " Player addresses are unchanged."
                        : offered ? " Other regions can still reach this address."
                        : " This address is temporarily unavailable to players.";
                warn(message + impact + " Check " + port(check, candidates) + " in your firewall.");
            } else {
                warn(message + " Player addresses are unchanged. Checks will retry automatically.");
            }
            debug("Check details: region=" + check.region() + ", family=IPv" + check.family() + ", method="
                    + check.method() + ", target=" + (check.target() == null ? "unknown" : endpoint(check.target()))
                    + ", result=" + check.outcome() + ", checkedAt=" + Instant.ofEpochMilli(check.checkedAt()) + ".");
        }
    }

    private static String result(ConnectivityOutcome outcome) {
        return switch (outcome) {
            case ESTABLISHED -> "passed";
            case NOT_ESTABLISHED -> "failed";
            case UNKNOWN -> "was inconclusive";
            case UNAVAILABLE -> "could not run";
        };
    }

    private static String method(String value) {
        return switch (value) {
            case "per_join" -> "assisted";
            case "warm_stun" -> "automatically discovered";
            default -> "direct";
        };
    }

    private static String port(ConnectivityCheck check, JsonArray candidates) {
        if (check.target() != null) {
            return "UDP port " + check.target().getPort();
        }
        var first = candidates == null || candidates.isEmpty() ? null : candidate(candidates.get(0));
        if (first != null) {
            return "UDP port " + first.getPort();
        }
        return "the server's UDP port";
    }

    private static List<String> endpoints(JsonArray candidates, int family) {
        if (candidates == null) {
            return List.of();
        }
        return candidates.asList().stream().map(ConnectivityLog::candidate).filter(Objects::nonNull)
                .filter(value -> (value.getAddress() instanceof Inet6Address ? 6 : 4) == family)
                .map(ConnectivityLog::endpoint).sorted().toList();
    }

    static InetSocketAddress candidate(JsonElement value) {
        try {
            var candidate = value.getAsJsonObject();
            return new InetSocketAddress(EndpointAddress.parse(candidate.get("address").getAsString()),
                    candidate.get("port").getAsInt());
        } catch (UnknownHostException | RuntimeException unusable) {
            return null;
        }
    }

    /** Same spelling for candidates and check targets, so they compare. */
    private static String endpoint(InetSocketAddress target) {
        String address = target.getAddress().getHostAddress();
        return (target.getAddress() instanceof Inet6Address ? "[" + address + "]" : address) + ":" + target.getPort();
    }

    private void debug(String message) {
        sink.accept(new ProviderDiagnostic(ProviderDiagnostic.Level.DEBUG, message));
    }

    private void info(String message) {
        sink.accept(new ProviderDiagnostic(ProviderDiagnostic.Level.INFO, message));
    }

    private void warn(String message) {
        sink.accept(new ProviderDiagnostic(ProviderDiagnostic.Level.WARN, message));
    }
}
