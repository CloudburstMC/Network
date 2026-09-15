/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

import java.security.MessageDigest;
import java.util.Arrays;
import static org.cloudburstmc.netty.signaling.diagnostic.DiagnosticAdmissionCodec.*;

/** Quarantined permit metadata. It does not implement player AdmissionValidator/VerifiedAdmission. */
public final class VerifiedDiagnosticAdmission implements AutoCloseable {
    private final Context context;
    private final Claims claims;
    private final Credentials credentials;
    private final String remoteUfrag;
    private final byte[] secret, contextDigest, binding;
    private final Clock clock;
    private final long deadlineNanos;
    private boolean used, closed;
    VerifiedDiagnosticAdmission(Context context, Claims claims, Credentials credentials, String remoteUfrag, byte[] secret,
                                byte[] contextDigest, byte[] binding, Clock clock, long deadlineNanos) {
        this.context = context; this.claims = claims; this.credentials = credentials; this.remoteUfrag = remoteUfrag;
        this.secret = secret.clone(); this.contextDigest = contextDigest.clone(); this.binding = binding.clone(); this.clock = clock; this.deadlineNanos = deadlineNanos;
    }
    public Context context() { return context; }
    public Claims claims() { return claims; }
    public Credentials credentials() { return credentials; }
    public String remoteUfrag() { return remoteUfrag; }
    synchronized boolean usable() { return !closed && clock.wallMillis().getAsLong() < claims.expiresAt() && clock.nanoTime().getAsLong() - deadlineNanos < 0; }
    /** One AUTH verification, before any challenge. Call only after pinned DTLS and exact channels. */
    public synchronized DiagnosticPrincipal authenticate(byte[] frame) {
        if (used || !usable()) return null; used = true;
        try {
            DiagnosticAssertionCodec.Assertion proof = DiagnosticAssertionCodec.decodeAuth(frame, claims.attemptIdHex());
            if (!MessageDigest.isEqual(binding, identity(secret, contextDigest, proof.publicPoint())) || !DiagnosticAssertionCodec.verify(context, claims, remoteUfrag, proof) || !usable()) return null;
            return new DiagnosticPrincipal(context, claims);
        } catch (RuntimeException invalid) { return null; } finally { close(); }
    }
    /** Key revocation and pending-peer cancellation must close this retained verification lease. */
    @Override public synchronized void close() { closed = true; Arrays.fill(secret, (byte) 0); Arrays.fill(binding, (byte) 0); }
    @Override public String toString() { return "VerifiedDiagnosticAdmission[attempt=" + claims.attemptIdHex() + "]"; }
}
