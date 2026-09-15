package org.cloudburstmc.netty.signaling;

import org.cloudburstmc.netty.signaling.control.ControlDiagnosticCompletionCodec;
import org.cloudburstmc.netty.signaling.control.ControlDiagnosticCompletionReceiptCodec;

/** Optional original-byte extraction only. No queue, heartbeat dispatch or receipt acknowledgement is wired here. */
public final class ControlDiagnosticCompletionsHeartbeatCodec {
    public static final int MAX_HEARTBEAT_BYTES = 65_536;
    public static final String FIELD = "diagnosticCompletions";
    private ControlDiagnosticCompletionsHeartbeatCodec() { }

    public static ControlDiagnosticCompletionCodec.Batch decodeHeartbeatCompletions(String originalHeartbeat) {
        String slice = slice(originalHeartbeat);
        return slice == null ? null : ControlDiagnosticCompletionCodec.decodeBatch(slice);
    }
    public static ControlDiagnosticCompletionReceiptCodec.Batch decodeHeartbeatReceipts(String originalHeartbeat) {
        String slice = slice(originalHeartbeat);
        return slice == null ? null : ControlDiagnosticCompletionReceiptCodec.decodeBatch(slice);
    }
    private static String slice(String originalHeartbeat) {
        if (originalHeartbeat == null || originalHeartbeat.length() > MAX_HEARTBEAT_BYTES)
            throw new IllegalArgumentException("Diagnostic heartbeat size");
        // Existing scanner validates the entire original JSON (including unrelated duplicate fields),
        // preserves ordinary telemetry decimals, and returns the exact nested wire for strict typed decoding.
        return ControlledProviderJson.rootProperty(originalHeartbeat, FIELD, MAX_HEARTBEAT_BYTES);
    }
}
