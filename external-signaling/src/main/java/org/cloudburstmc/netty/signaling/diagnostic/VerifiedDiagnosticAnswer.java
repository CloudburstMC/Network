/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import java.util.Arrays;
import java.util.function.Supplier;

/** One-use verified SDP. Applying it still requires an actual pinned DTLS and bounded transport owner. */
public final class VerifiedDiagnosticAnswer implements AutoCloseable {
    private final DiagnosticAnswerCodec.Expected expected;
    private final DiagnosticAnswerCodec.VerificationKey key;
    private final Supplier<DiagnosticAnswerCodec.Catalog> reader;
    private final DiagnosticAnswerCodec.Fence fence;
    private byte[] bytes;
    private boolean consumed;
    VerifiedDiagnosticAnswer(DiagnosticAnswerCodec.Expected expected, byte[] bytes, DiagnosticAnswerCodec.VerificationKey key,
                             Supplier<DiagnosticAnswerCodec.Catalog> reader, DiagnosticAnswerCodec.Fence fence) {
        this.expected = expected; this.bytes = bytes.clone(); this.key = key; this.reader = reader; this.fence = fence;
    }
    /** Rechecks current catalog and deadlines immediately before an owned copy leaves the codec. Apply immediately. */
    public synchronized byte[] takeSdp() {
        if (bytes == null || consumed) throw DiagnosticAdmissionCodec.invalid();
        consumed = true; // The monitor is reentrant when a trusted reader invokes user code.
        try { DiagnosticAnswerCodec.currentKey(reader, expected, key, fence); if (bytes == null) throw DiagnosticAdmissionCodec.invalid(); return bytes.clone(); } finally { close(); }
    }
    @Override public synchronized void close() { if (bytes != null) Arrays.fill(bytes, (byte) 0); bytes = null; }
    @Override public String toString() { return "VerifiedDiagnosticAnswer[redacted]"; }
}
