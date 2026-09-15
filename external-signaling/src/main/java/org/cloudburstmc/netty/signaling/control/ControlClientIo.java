package org.cloudburstmc.netty.signaling.control;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Explicit integration seam. HTTPS implementations use normal TLS, exact configured routes, no
 * redirects and bounded response bodies; failures cannot manufacture authoritative receipts.
 * It owns no gameplay/native transport. Missing synchronization must fail, never default to ready.
 */
public interface ControlClientIo {
    record HttpReply(URI requestUri, String requestMethod, URI responseUri, int status, String body) { }
    interface Link {
        CompletionStage<Void> opened();
        CompletionStage<?> closed();
        CompletionStage<Void> sendText(String wire);
        void close();
        void abort();
    }
    interface Scheduler {
        interface Task { void cancel(); }
        Task schedule(Runnable action, long delayMillis);
    }
    /** Restricted initial-state exchange; the coordinator remains the sole durable intent/sequence owner. */
    interface Synchronization {
        /** Original fixed application deadline on the shared ControlClientClock; reading it grants no authority. */
        long deadlineMillis();
        /** Exact retained heartbeat bytes, if recovery must finish one before issuing a fresh heartbeat. */
        Optional<byte[]> pendingHeartbeat();
        /** One-off HTTPS under the selected writer; it does not replace the transport or bypass the journal. */
        CompletionStage<ControlOperationResult> heartbeat(byte[] originalBody);
        /**
         * Call only after actual native application and durable save. The coordinator compares the exact basis
         * with cached authority; WebSocket confirmation additionally requires a matching signed session.ready.
         * One call per synchronization pass. A source mismatch returns awaitingSource without a database query.
         */
        CompletionStage<ControlSynchronizationResult> applied(ControlStateCodec.AppliedBasis basis);
        /** Recheck immediately before and after asynchronous state/key application. */
        void requireCurrent();
    }
    CompletionStage<HttpReply> bootstrap(URI endpoint, ControlSessionCodec.Request request);
    /** Raw bounded response. The coordinator checks exact HTTPS provenance and provider-control proof. */
    CompletionStage<HttpReply> authority(URI endpoint, ControlAuthorityCodec.Request request);
    /** Returns a raw result envelope bounded by ControlResultCodec.MAX_ENVELOPE_BYTES, never a bare receipt. */
    CompletionStage<HttpReply> operation(URI endpoint, ControlHttpCodec.Request request, byte[] originalBody);
    Link openWebSocket(URI endpoint, ControlSessionCodec.Request upgradeProof, Consumer<String> received);
    /**
     * Body application must run on the adapter's serialized application executor, not inline under the coordinator monitor.
     * Use the restricted lane for initial heartbeat/key/state exchange and requireCurrent before/after async application.
     * Complete only after actual application and provider readiness confirmation; a committed receipt is insufficient.
     */
    CompletionStage<ControlSynchronizationResult> synchronize(ControlWriterFence writer, ControlClientJournal.Grant fixedGrant,
                                      ControlAuthorityCodec.Verified authority, Synchronization exchange);
    /** State/resync notices during active synchronization; readiness acknowledgements belong to the coordinator. */
    void onSynchronizationFrame(ControlFrameDelivery delivery);
    /** Verified active frames, guarded through queued application; standby traffic never reaches this hook. */
    void onVerifiedFrame(ControlFrameDelivery delivery);
}
