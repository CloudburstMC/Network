package org.cloudburstmc.netty.signaling.control;

import java.net.URI;
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
    /** This is trusted synchronization output, not a deserialized unauthenticated frame payload. */
    record Authority(ControlWriterFence writer, long sourceCheckedAt, long expiresAt) {
        public Authority {
            ControlJson.safe(sourceCheckedAt, false); ControlJson.safe(expiresAt, false);
            if (expiresAt <= sourceCheckedAt) throw ControlJson.invalid("synchronized authority");
        }
    }
    CompletionStage<HttpReply> bootstrap(URI endpoint, ControlSessionCodec.Request request);
    CompletionStage<HttpReply> operation(URI endpoint, ControlHttpCodec.Request request, byte[] originalBody);
    Link openWebSocket(URI endpoint, ControlSessionCodec.Request upgradeProof, Consumer<String> received);
    /** Completes only after required state/keys are applied and the provider confirms this exact active binding. */
    CompletionStage<Authority> synchronize(ControlWriterFence writer, ControlClientJournal.Grant fixedGrant);
    /** Only verified session.ready/session.resync/state.desired during active synchronization; never assisted work. */
    void onSynchronizationFrame(ControlFrameCodec.Frame frame);
    /** Receives verified active frames after activation; standby traffic never reaches this hook. */
    void onVerifiedFrame(ControlFrameCodec.Frame frame);
}
