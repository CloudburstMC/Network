package org.cloudburstmc.netty.signaling;

import org.cloudburstmc.netty.signaling.control.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticCompletionEmitter;
import org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Serialized application-lane native drain and local-only receipt settlement. Never changes player state. */
final class ControlledDiagnosticCompletions {
    private final ControlledProviderState storage;
    private final ProviderTransport transport;
    private final Consumer<String> notice;
    final boolean enabled;
    private List<ControlDiagnosticCompletionCodec.Completion> polled;
    private long invalid, dropped, sampledDrop = -1, lastDrop = -1, nextPoll;
    private CachedReceipt cached;
    private boolean announced;
    private record CachedReceipt(String intentDigest, String receiptDigest, ControlDiagnosticCompletionReceiptCodec.Batch batch) { }

    ControlledDiagnosticCompletions(ControlledProviderState storage, ProviderTransport transport, Consumer<String> notice) {
        this.storage = storage; this.transport = transport; this.notice = notice;
        enabled = storage.application().has("diagnosticAdmission");
    }
    boolean maintain(long now) {
        if (!enabled || now < nextPoll) return false;
        nextPoll = now + 1000;
        if (!announced) { announced = true; notice.accept("diagnostic_completion_prior_process_loss_unknown"); }
        try {
            if (polled == null) {
                int capacity = storage.diagnosticCompletionCapacity();
                var counter = transport.diagnosticDroppedResultCount();
                sampledDrop = counter.orElse(-1);
                if (sampledDrop >= 0 && lastDrop < 0) lastDrop = sampledDrop; // Establish a counter baseline, not invented history.
                dropped = sampledDrop >= lastDrop && lastDrop >= 0 ? sampledDrop - lastDrop : 0;
                if (sampledDrop >= 0 && lastDrop >= 0 && sampledDrop < lastDrop) notice.accept("diagnostic_completion_counter_history_unknown");
                var nativeBatch = capacity == 0 ? List.<DiagnosticAdmission.Completion>of() : List.copyOf(transport.pollDiagnosticResults(capacity));
                if (nativeBatch.size() > capacity) throw new IllegalStateException("Native diagnostic poll bound");
                var converted = new ArrayList<ControlDiagnosticCompletionCodec.Completion>(); invalid = 0;
                for (var result : nativeBatch) {
                    try {
                        var completion = DiagnosticCompletionEmitter.from(result);
                        ControlledDiagnosticCompletionQueue.scoped(completion, storage.initial.subject());
                        converted.add(completion);
                    } catch (RuntimeException malformed) { invalid++; }
                }
                polled = List.copyOf(converted);
            }
            // No new poll while this exact <=4 RAM batch awaits its first durable root save.
            int before = storage.diagnosticCompletionState().getAsJsonArray("pending").size();
            storage.appendDiagnosticCompletions(polled, invalid, dropped);
            boolean appended = storage.diagnosticCompletionState().getAsJsonArray("pending").size() > before;
            if (invalid != 0) notice.accept("diagnostic_completion_invalid_unknown");
            if (dropped != 0) notice.accept("diagnostic_completion_native_dropped_unknown");
            lastDrop = sampledDrop; polled = null; invalid = dropped = 0; return appended;
        } catch (IOException | RuntimeException unavailable) {
            notice.accept("diagnostic_completions_persistence_unavailable"); return false;
        }
    }
    void appendToHeartbeat(JsonObject body) {
        if (!enabled) return;
        var batch = storage.diagnosticCompletionBatch(); if (batch == null) return;
        for (int n = batch.completions().size(); n > 0; n--) {
            try {
                body.add("diagnosticCompletions", JsonParser.parseString(ControlDiagnosticCompletionCodec.encodeBatch(
                        new ControlDiagnosticCompletionCodec.Batch(batch.completions().subList(0, n)))));
            } catch (IllegalArgumentException aggregateBound) { continue; }
            if (body.toString().getBytes(StandardCharsets.UTF_8).length <= ControlLifecycleCodec.MAX_WS_BODY_BYTES) return;
        }
        body.remove("diagnosticCompletions"); notice.accept("diagnostic_completion_heartbeat_capacity_unavailable");
    }
    boolean requiresAcknowledgement(ControlLifecycleCodec.Intent intent, byte[] original) {
        if (!enabled || !intent.operation().equals("heartbeat")) return false;
        ControlLifecycleCodec.verifyBody(intent, original);
        return ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatCompletions(new String(original, StandardCharsets.UTF_8)) != null;
    }
    void acknowledge(ControlLifecycleCodec.Intent intent, byte[] original, ControlLifecycleCodec.Receipt receipt, byte[] response) throws IOException {
        if (!requiresAcknowledgement(intent, original)) throw new IOException("Diagnostic reporting not opted in or absent");
        String intentDigest = ControlLifecycleCodec.intentDigest(intent);
        String receiptDigest = ControlFrameCodec.payloadDigest(ControlLifecycleCodec.encodeReceipt(receipt).getBytes(StandardCharsets.UTF_8));
        ControlDiagnosticCompletionReceiptCodec.Batch batch = null;
        if (response != null) {
            try { batch = ControlDiagnosticCompletionsHeartbeatCodec.decodeHeartbeatReceipts(new String(response, StandardCharsets.UTF_8)); }
            catch (RuntimeException malformed) { notice.accept("diagnostic_completion_receipts_unavailable"); }
            if (batch != null) cached = new CachedReceipt(intentDigest, receiptDigest, batch);
        }
        if (batch == null && cached != null && cached.intentDigest().equals(intentDigest) && cached.receiptDigest().equals(receiptDigest)) batch = cached.batch();
        storage.acknowledgeDiagnosticCompletions(intent, original, receipt, batch);
        cached = null;
        if (!storage.diagnosticCompletionState().getAsJsonObject("acknowledgement").get("settled").getAsBoolean())
            notice.accept("diagnostic_completion_receipts_retained");
    }

}
