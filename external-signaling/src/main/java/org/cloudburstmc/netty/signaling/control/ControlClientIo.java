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
    /** Application refuses to replay an immutable intent whose physical state no longer exists. */
    final class ReconciliationRequired extends IllegalStateException {
        public ReconciliationRequired(String message) { super(message); }
    }
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
        /** Selected writer carrier (HTTPS for an HTTPS writer/oversized body); never replaces transport or bypasses the journal. */
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
    /** HTTPS writers only. WebSocket writers exchange the same signed proofs through their current Link, without an HTTP fallback. */
    CompletionStage<HttpReply> authority(URI endpoint, ControlAuthorityCodec.Request request);
    /** Returns a raw result envelope bounded by ControlResultCodec.MAX_ENVELOPE_BYTES, never a bare receipt. */
    CompletionStage<HttpReply> operation(URI endpoint, ControlHttpCodec.Request request, byte[] originalBody);
    /** Pure bounded body policy, called only for retained/recovering intents; never authenticates or replays the body. */
    default boolean requiresNativeIntentCancellation(ControlLifecycleCodec.Intent intent, byte[] originalBody) { return false; }
    /** Opt-in application queue owner: committed receipts must be durably applied before releasing their intent. */
    default boolean requiresOutcomeAcknowledgement() { return false; }
    /**
     * Runs on the adapter's serialized application executor. Remove the exact original queue prefix and
     * retain an idempotent intent/receipt marker atomically. This is local receipt application, not a grant.
     * No response body or secrets are supplied. Failure keeps the committed receipt for local-only retry.
     */
    default CompletionStage<Void> acknowledgeCommittedOutcomes(ControlLifecycleCodec.Intent intent, byte[] originalBody,
                                                              ControlLifecycleCodec.Receipt receipt) {
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("No durable outcomes owner"));
    }
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
