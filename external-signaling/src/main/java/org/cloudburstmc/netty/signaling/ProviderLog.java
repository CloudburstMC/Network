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

import java.time.Duration;
import java.util.EnumMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Tracks independent failures for one provider client. Successful unrelated operations do not reset them. */
final class ProviderLog {
    enum Operation {
        STATUS(
                "Cannot update the signaling service",
                "Retrying automatically.",
                "Signaling service updates have resumed."),
        EVENTS("Cannot send connection reports", "Retrying automatically.", "Connection reports are being sent again."),
        SERVER_STATUS("Cannot read the server status", "Retrying automatically.", "Server status is available again."),
        CONNECTIVITY(
                "Cannot read connection check results",
                "Retrying automatically.",
                "Connection check results are available again."),
        WEBSOCKET(
                "Lost the live connection to the signaling service",
                "Reconnecting automatically.",
                "Reconnected to the signaling service."),
        STORAGE("Cannot save signaling settings", "Signaling is stopping.", "Signaling settings can be saved again."),
        DRAIN("Could not tell the signaling service that the server is stopping", "", ""),
        DEREGISTER("Could not remove this server from the signaling service", "It expires there instead.", ""),
        TRANSPORT_CLOSE("Could not close the player connection listener", "", ""),
        STATE_CLOSE("Could not close the signaling settings file", "", "");

        final String failure, outcome, recovery;

        Operation(String failure, String outcome, String recovery) {
            this.failure = failure;
            this.outcome = outcome;
            this.recovery = recovery;
        }

        /** Such as "Cannot update the signaling service: ConnectException. Retrying automatically." */
        String message(String reason) {
            return failure + ": " + reason + "." + (outcome.isEmpty() ? "" : " " + outcome);
        }
    }

    /** A failure that lasts this long warns again, so a long outage is not one line in an old log. */
    static final long REMINDER_NANOS = TimeUnit.MINUTES.toNanos(30);

    private final Consumer<ProviderDiagnostic> sink;
    private final LongSupplier nanoTime;
    /** For each failing operation, when its streak started and when it last warned. */
    private final EnumMap<Operation, long[]> failures = new EnumMap<>(Operation.class);

    ProviderLog(Consumer<ProviderDiagnostic> sink) {
        this(sink, System::nanoTime);
    }

    ProviderLog(Consumer<ProviderDiagnostic> sink, LongSupplier nanoTime) {
        this.sink = sink;
        this.nanoTime = nanoTime;
    }

    /**
     * The first failure of a streak warns, and so does the first after each further {@link #REMINDER_NANOS}. The rest
     * go to debug, each with its reason.
     */
    void failed(Operation operation, String reason) {
        long now = nanoTime.getAsLong();
        long[] streak = failures.get(operation);
        if (streak == null) {
            failures.put(operation, new long[] {now, now});
            sink.accept(new ProviderDiagnostic(ProviderDiagnostic.Level.WARN, operation.message(reason)));
        } else if (now - streak[1] >= REMINDER_NANOS) {
            streak[1] = now;
            long minutes = TimeUnit.NANOSECONDS.toMinutes(now - streak[0]);
            sink.accept(new ProviderDiagnostic(ProviderDiagnostic.Level.WARN,
                    operation.message(reason) + " Failing for " + minutes + " minutes."));
        } else {
            sink.accept(new ProviderDiagnostic(ProviderDiagnostic.Level.DEBUG, operation.message(reason)));
        }
    }

    void recovered(Operation operation) {
        if (failures.remove(operation) != null) {
            sink.accept(new ProviderDiagnostic(ProviderDiagnostic.Level.INFO, operation.recovery));
        }
    }

    // Each failed player attempt matters, even when another player just failed too.
    void assistedJoinFailed(org.cloudburstmc.netty.signaling.control.AssistedJoin join) {
        sink.accept(
                new ProviderDiagnostic(
                        join.diagnostic()
                                ? ProviderDiagnostic.Level.DEBUG
                                : ProviderDiagnostic.Level.WARN,
                        join.diagnostic()
                                ? "An assisted connection check could not complete."
                                : "A player could not connect using an assisted join."));
    }

    void playerConnectionFailed() {
        sink.accept(new ProviderDiagnostic(ProviderDiagnostic.Level.WARN, "A player could not connect to the server."));
    }

    void registrationLost(String instanceId) {
        sink.accept(new ProviderDiagnostic(ProviderDiagnostic.Level.WARN, "The signaling service no longer knows "
                + "server " + instanceId + ". Registering it again as a new server."));
    }

    void registrationDeferred(String reason, Duration wait) {
        sink.accept(new ProviderDiagnostic(ProviderDiagnostic.Level.WARN, "The signaling service cannot register this "
                + "server yet: " + reason + ". Trying again in " + described(wait) + "."));
    }

    void clockSkew(long offsetMillis) {
        sink.accept(new ProviderDiagnostic(ProviderDiagnostic.Level.WARN, "This machine's clock is "
                + described(Duration.ofMillis(Math.abs(offsetMillis))) + (offsetMillis > 0 ? " behind" : " ahead of")
                + " the signaling service, which refuses requests signed too far from its own time. Set the clock "
                + "right, with NTP where possible."));
    }

    void clockInStep() {
        sink.accept(new ProviderDiagnostic(ProviderDiagnostic.Level.INFO,
                "This machine's clock is in step with the signaling service again."));
    }

    /** Such as "45 seconds", "20 minutes" or "3 hours". */
    static String described(Duration wait) {
        long seconds = wait.toSeconds();
        if (seconds < 120) {
            return seconds + " seconds";
        }
        return seconds < 7200 ? Math.round(seconds / 60.0) + " minutes" : Math.round(seconds / 3600.0) + " hours";
    }

    void registrationGone(String reason) {
        sink.accept(new ProviderDiagnostic(ProviderDiagnostic.Level.WARN, "The signaling service no longer has this "
                + "server's registration: " + reason + ". Signaling is stopping until the server restarts."));
    }

    void replaced() {
        sink.accept(new ProviderDiagnostic(ProviderDiagnostic.Level.WARN, "Another process took over this server's "
                + "signaling registration. Signaling is stopping."));
    }

    void detail(Operation operation, String detail) {
        sink.accept(
                new ProviderDiagnostic(ProviderDiagnostic.Level.DEBUG, operation + ": " + detail));
    }
}
