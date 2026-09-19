/* Copyright 2026 CloudburstMC. Licensed under the Apache License, Version 2.0. */
package org.cloudburstmc.netty.signaling.admission;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionalDiagnosticHostTest {
    private static JsonObject config() {
        var c = new JsonObject(); c.addProperty("port", 41443); c.addProperty("runExpiresAt", 135_000);
        c.addProperty("policyExpiresAt", 135_000); c.addProperty("keyRetireAt", 400_000); return c;
    }
    @Test void fixedDeadlineCanOnlyLoseTimeDuringHandoff() {
        assertEquals(135_000, RegionalDiagnosticHost.validateLifetime(config(), 100_000));
        assertEquals(135_000, RegionalDiagnosticHost.validateLifetime(config(), 120_000));
        assertThrows(IllegalArgumentException.class, () -> RegionalDiagnosticHost.validateLifetime(config(), 135_000));
        assertThrows(IllegalArgumentException.class, () -> RegionalDiagnosticHost.validateLifetime(config(), 99_999));
    }
    @Test void installedPolicyMustEndAtActualHostDeadline() {
        for (long expiry : new long[]{134_999, 135_001}) {
            var c = config(); c.addProperty("policyExpiresAt", expiry);
            assertThrows(IllegalArgumentException.class, () -> RegionalDiagnosticHost.validateLifetime(c, 100_000));
        }
        var c = config(); c.addProperty("keyRetireAt", 134_999);
        assertThrows(IllegalArgumentException.class, () -> RegionalDiagnosticHost.validateLifetime(c, 100_000));
    }
    @Test void sourcePortIsExactIntegralNonzeroAndBounded() {
        for (Number port : new Number[]{0, -1, 65536, 41443.5, Long.MAX_VALUE}) {
            var c = config(); c.addProperty("port", port);
            assertThrows(IllegalArgumentException.class, () -> RegionalDiagnosticHost.validateLifetime(c, 100_000));
        }
        var c = config(); c.addProperty("port", "41443");
        assertThrows(IllegalArgumentException.class, () -> RegionalDiagnosticHost.validateLifetime(c, 100_000));
    }
}
