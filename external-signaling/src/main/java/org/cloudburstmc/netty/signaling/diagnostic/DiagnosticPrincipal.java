/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.diagnostic;

/** Verified diagnostic assertion only. No network/player ID, CPK verifier, or gameplay promotion API. */
public final class DiagnosticPrincipal {
    private final DiagnosticAdmissionCodec.Context context;
    private final DiagnosticAdmissionCodec.Claims claims;
    DiagnosticPrincipal(DiagnosticAdmissionCodec.Context context, DiagnosticAdmissionCodec.Claims claims) { this.context = context; this.claims = claims; }
    public String purpose() { return "connectivity-check"; }
    public DiagnosticAdmissionCodec.Context context() { return context; }
    public DiagnosticAdmissionCodec.Claims claims() { return claims; }
    @Override public String toString() { return "DiagnosticPrincipal[attempt=" + claims.attemptIdHex() + "]"; }
}
