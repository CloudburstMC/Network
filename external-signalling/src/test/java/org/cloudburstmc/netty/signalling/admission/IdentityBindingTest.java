package org.cloudburstmc.netty.signalling.admission;

import org.cloudburstmc.netty.util.nethernet.IdentityPublicKey;
import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class IdentityBindingTest extends AdmissionFixture {
    PublicKey key() throws Exception {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder()
                .decode(f.getAsJsonObject("identity").get("canonicalCpk").getAsString())));
    }

    StatelessAdmissionValidator.TicketKey epoch() {
        return new StatelessAdmissionValidator.TicketKey("K001", f.getAsJsonObject("context").get("secret").getAsString());
    }

    @Test
    void canonicalNodeKeyAcceptsExactlyOneLoginAndDifferentKeyIsRejected() throws Exception {
        assertEquals(f.getAsJsonObject("identity").get("canonicalCpk").getAsString(),
                Base64.getEncoder().encodeToString(IdentityPublicKey.canonical(key())));
        var admitted = validator().validate(request(), now).identityVerifier();
        assertNull(admitted.mismatch(key()));
        assertFalse(admitted.pending());
        assertFalse(admitted.rejected());
        assertNotNull(admitted.mismatch(key()));
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        var other = validator().validate(request(), now).identityVerifier();
        assertNotNull(other.mismatch(generator.generateKeyPair().getPublic()));
        assertTrue(other.rejected());
        assertNotNull(other.mismatch(key()));
    }

    @Test
    void unsupportedAndMissingKeysFailClosed() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        assertNotNull(validator().validate(request(), now).identityVerifier().mismatch(generator.generateKeyPair().getPublic()));
        assertNotNull(validator().validate(request(), now).identityVerifier().mismatch(null));
    }

    @Test
    void refreshRotationAndSameIdReplacementKeepTheActualAdmittedEpoch() throws Exception {
        var v = validator();
        var refresh = v.validate(request(), now).identityVerifier();
        v.installKeys(List.of(epoch()));
        assertNull(refresh.mismatch(key()));
        var replaced = v.validate(request(), now).identityVerifier();
        v.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001", "replacement-secret-at-least-thirty-two-characters")));
        assertNull(v.validate(request(), now));
        assertNull(replaced.mismatch(key()));
        v.installKeys(List.of(new StatelessAdmissionValidator.TicketKey("K001", epoch().secret(), now, now + 1000)));
        var retired = v.validate(request(), now).identityVerifier();
        v.retireKeys(now + 1000);
        assertFalse(v.ready());
        assertNull(retired.mismatch(key()));
    }

    @Test
    void clearAlsoRevokesRetainedEpochsAndCloseIsIdempotent() throws Exception {
        var v = validator();
        var retired = v.validate(request(), now).identityVerifier();
        v.installKeys(List.of());
        v.clear();
        assertTrue(retired.rejected());
        assertNotNull(retired.mismatch(key()));
        v.installKeys(List.of(epoch()));
        var closed = v.validate(request(), now).identityVerifier();
        closed.close();
        closed.close();
        assertNotNull(closed.mismatch(key()));
    }

    @Test
    void pendingDeadlineIsMonotonicAndCompletedForwardingSurvivesExpiry() throws Exception {
        var nanos = new AtomicLong(0);
        var v = new StatelessAdmissionValidator(f.getAsJsonObject("context").get("audience").getAsString(), 60_000, nanos::get);
        v.installKeys(List.of(epoch()));
        var pending = v.validate(request(), now).identityVerifier();
        var forwarded = v.validate(request(), now).identityVerifier();
        assertNull(forwarded.acceptForwardedIdentity());
        nanos.set(30_000_000_000L);
        assertTrue(pending.rejected());
        assertNotNull(pending.mismatch(key()));
        assertFalse(forwarded.rejected());
        assertFalse(forwarded.pending());
    }

    @Test
    void connectedWithoutLoginTimesOutButVerifiedAndForwardedSessionsContinue() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            var gate = new AdmissionGate(AdmissionGate.Limits.defaults(), validator());
            var r = gate.reserve(request(), now, 0);
            var binding = gate.admission(r).identityVerifier();
            assertTrue(gate.ready(r));
            gate.connected(r);
            if (mode == 1) assertNull(binding.mismatch(key()));
            if (mode == 2) assertNull(binding.acceptForwardedIdentity());
            assertEquals(mode == 0 ? List.of(r) : List.of(), gate.sweep(now + 30_000, 30_000_000_000L));
            gate.finish(r);
            assertFalse(binding.pending());
        }
    }

    @Test
    void reserveRejectionAndNativeFailureReleaseTheirOwnBinding() {
        var v = validator();
        var issued = new java.util.ArrayList<VerifiedAdmission>();
        var gate = new AdmissionGate(AdmissionGate.Limits.defaults(), (request, time) -> {
            var a = v.validate(request, time);
            issued.add(a);
            return a;
        });
        var accepted = gate.reserve(request(), now, 0);
        assertNull(gate.reserve(request(), now, 0));
        assertFalse(issued.get(1).identityVerifier().pending());
        assertTrue(issued.get(0).identityVerifier().pending());
        gate.finish(accepted);
        assertFalse(issued.get(0).identityVerifier().pending());
        gate.drain();
        assertNull(gate.reserve(request(), now, 0));
        assertFalse(issued.get(2).identityVerifier().pending());
    }
}
