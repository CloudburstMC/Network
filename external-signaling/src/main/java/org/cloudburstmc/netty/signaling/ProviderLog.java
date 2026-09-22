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

import java.util.EnumSet;
import java.util.function.Consumer;

/** Tracks independent failures for one provider client. Successful unrelated operations do not reset them. */
final class ProviderLog {
    enum Operation {
        STATUS(
                "Cannot update the signaling service. Retrying automatically.",
                "Signaling service updates have resumed."),
        EVENTS(
                "Cannot send connection reports. Retrying automatically.",
                "Connection reports are being sent again."),
        SERVER_STATUS(
                "Cannot read the server status. Retrying automatically.",
                "Server status is available again."),
        CONNECTIVITY(
                "Cannot read connection check results. Retrying automatically.",
                "Connection check results are available again."),
        WEBSOCKET(
                "Lost the live connection to the signaling service. Reconnecting automatically.",
                "Reconnected to the signaling service."),
        STORAGE(
                "Cannot save signaling settings. Signaling is stopping.",
                "Signaling settings can be saved again."),
        DRAIN("Could not tell the signaling service that the server is stopping.", ""),
        TRANSPORT_CLOSE("Could not close the player connection listener.", ""),
        STATE_CLOSE("Could not close the signaling settings file.", "");
        final String failure, recovery;

        Operation(String failure, String recovery) {
            this.failure = failure;
            this.recovery = recovery;
        }
    }

    private final Consumer<ProviderDiagnostic> sink;
    private final EnumSet<Operation> failures = EnumSet.noneOf(Operation.class);

    ProviderLog(Consumer<ProviderDiagnostic> sink) {
        this.sink = sink;
    }

    void failed(Operation operation) {
        sink.accept(
                new ProviderDiagnostic(
                        failures.add(operation)
                                ? ProviderDiagnostic.Level.WARN
                                : ProviderDiagnostic.Level.DEBUG,
                        operation.failure));
    }

    void recovered(Operation operation) {
        if (failures.remove(operation)) {
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

    void detail(Operation operation, String detail) {
        sink.accept(
                new ProviderDiagnostic(ProviderDiagnostic.Level.DEBUG, operation + ": " + detail));
    }
}
