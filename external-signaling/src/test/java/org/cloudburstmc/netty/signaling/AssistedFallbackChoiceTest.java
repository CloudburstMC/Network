package org.cloudburstmc.netty.signaling;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.cloudburstmc.netty.signaling.ProviderTransport.ConnectivityOutcome.*;
import static org.junit.jupiter.api.Assertions.*;

class AssistedFallbackChoiceTest {
    private final ProviderClient.AssistedFallbackChoice choice = new ProviderClient.AssistedFallbackChoice();

    private static JsonObject profile() {
        var profile = new ProviderClientTest.FakeTransport().hostProfile().toCompletableFuture().join();
        profile.getAsJsonArray("candidates").get(0).getAsJsonObject().addProperty("address", "8.8.8.8");
        return profile;
    }

    private static ProviderTransport.HostProfileSnapshot snapshot(JsonObject profile, long revision) {
        return new ProviderTransport.HostProfileSnapshot(profile, revision, () -> { });
    }

    private void report(ProviderTransport.HostProfileSnapshot snapshot, long now, ProviderTransport.ConnectivityCheck... checks) {
        choice.report("host", 1, snapshot, List.of(checks), now);
    }

    private boolean needed(ProviderTransport.HostProfileSnapshot snapshot, long now) {
        return choice.needed("host", 1, snapshot, Set.of(4), now);
    }

    @Test void discoveryMustFinishAndPublicCandidatesNeedFreshNegativeEvidence() {
        var profile = profile(); var snapshot = snapshot(profile, 1);
        report(snapshot, 1000, new ProviderTransport.ConnectivityCheck(4, NOT_ESTABLISHED, 100, 1000),
                new ProviderTransport.ConnectivityCheck(4, NOT_ESTABLISHED, 1001, 2000),
                new ProviderTransport.ConnectivityCheck(4, UNKNOWN, 900, 2000));
        assertFalse(needed(snapshot, 1000));
        profile.getAsJsonArray("candidates").get(0).getAsJsonObject().addProperty("address", "127.0.0.1");
        var privateEndpoint = snapshot(profile, 2);
        assertFalse(choice.needed("host", 1, privateEndpoint, Set.of(), 1000));
        assertTrue(needed(privateEndpoint, 1000));
    }

    @Test void fallbackSurvivesExpiredEmptyAndUnknownFeedbackUntilFreshSuccess() {
        var snapshot = snapshot(profile(), 1);
        var failed = new ProviderTransport.ConnectivityCheck(4, NOT_ESTABLISHED, 900, 2000);
        report(snapshot, 1000, failed);
        assertFalse(choice.needed("host", 1, snapshot, Set.of(), 1000));
        assertTrue(needed(snapshot, 1000));
        report(snapshot, 2000, failed);
        assertTrue(needed(snapshot, 2000));
        report(snapshot, 2100);
        assertTrue(needed(snapshot, 2100));
        report(snapshot, 2200, new ProviderTransport.ConnectivityCheck(4, UNKNOWN, 2100, 4000));
        assertTrue(needed(snapshot, 2200));
        report(snapshot, 2300, new ProviderTransport.ConnectivityCheck(4, ESTABLISHED, 2250, 4000));
        assertFalse(needed(snapshot, 2300));
        report(snapshot, 4000);
        assertFalse(needed(snapshot, 4000));
    }

    @Test void unchangedStunRefreshDoesNotRequireTheExpiredHistoricalSnapshot() {
        var original = profile();
        var candidate = original.getAsJsonArray("candidates").get(0).getAsJsonObject();
        candidate.addProperty("type", "srflx"); candidate.addProperty("expiresAt", 2000);
        var expired = new AtomicBoolean();
        var old = new ProviderTransport.HostProfileSnapshot(original, 1, 1, () -> {
            if (expired.get()) throw new IllegalStateException("Original mapping lease expired");
        });
        report(old, 1000, new ProviderTransport.ConnectivityCheck(4, NOT_ESTABLISHED, 900, 2000));
        assertTrue(needed(old, 1000));
        expired.set(true);
        assertThrows(IllegalStateException.class, old::requireCurrent);
        candidate.addProperty("expiresAt", 5000);
        var refreshed = new ProviderTransport.HostProfileSnapshot(original, 1, 2, () -> { });
        report(refreshed, 2100);
        assertTrue(needed(refreshed, 2100));
        assertThrows(IllegalStateException.class, old::requireCurrent, "Retaining the choice never renews the old lease");
    }

    @Test void materialIdentityAndRegistrationReplacementResetTheChoice() {
        for (String replacement : List.of("revision", "incarnation", "fingerprint", "generation", "instance")) {
            var profile = profile(); var original = snapshot(profile, 1);
            report(original, 1000, new ProviderTransport.ConnectivityCheck(4, NOT_ESTABLISHED, 900, 2000));
            assertTrue(needed(original, 1000));
            if (replacement.equals("incarnation")) profile.getAsJsonObject("statelessAdmission").addProperty("incarnation", "ab".repeat(16));
            if (replacement.equals("fingerprint")) profile.addProperty("dtlsFingerprint", "sha-256 replacement");
            if (replacement.equals("revision")) profile.getAsJsonArray("candidates").get(0).getAsJsonObject().addProperty("port", 19134);
            assertFalse(choice.needed(replacement.equals("instance") ? "replacement" : "host",
                    replacement.equals("generation") ? 2 : 1,
                    snapshot(profile, replacement.equals("revision") ? 2 : 1), Set.of(4), 1100), replacement);
        }
    }

    @Test void unavailableCurrentSnapshotCannotRetainAChoice() {
        var profile = profile(); var current = snapshot(profile, 1);
        report(current, 1000, new ProviderTransport.ConnectivityCheck(4, NOT_ESTABLISHED, 900, 2000));
        var retired = new ProviderTransport.HostProfileSnapshot(profile, 1, () -> { throw new IllegalStateException("retired"); });
        assertThrows(IllegalStateException.class, () -> needed(retired, 1100));
        assertFalse(needed(current, 1200));
    }

    @Test void freshSuccessClearsOnlyItsFamilyAndFencesOlderNegativeReplays() {
        var profile = profile();
        var ipv6 = profile.getAsJsonArray("candidates").get(0).getAsJsonObject().deepCopy();
        ipv6.addProperty("address", "2606:4700:4700::1111"); profile.getAsJsonArray("candidates").add(ipv6);
        var snapshot = snapshot(profile, 1);
        var negative4 = new ProviderTransport.ConnectivityCheck(4, NOT_ESTABLISHED, 900, 3000);
        var negative6 = new ProviderTransport.ConnectivityCheck(6, NOT_ESTABLISHED, 900, 3000);
        report(snapshot, 1000, negative4, negative6);
        report(snapshot, 1200, negative4, new ProviderTransport.ConnectivityCheck(4, ESTABLISHED, 1100, 2000));
        assertFalse(needed(snapshot, 1200));
        assertTrue(choice.needed("host", 1, snapshot, Set.of(4, 6), 1200));
        report(snapshot, 2100, negative4);
        assertFalse(needed(snapshot, 2100), "An older cached failure cannot undo observed recovery");
        assertTrue(choice.needed("host", 1, snapshot, Set.of(6), 3100));
        report(snapshot, 3200, new ProviderTransport.ConnectivityCheck(6, ESTABLISHED, 3100, 4000));
        assertFalse(choice.needed("host", 1, snapshot, Set.of(4, 6), 3200));
        report(snapshot, 3300, new ProviderTransport.ConnectivityCheck(4, NOT_ESTABLISHED, 3250, 4000));
        assertTrue(needed(snapshot, 3300), "A newly observed failure can select fallback again");
    }

    @Test void olderOrEqualSuccessCannotClearANewerSelectedFailure() {
        var snapshot = snapshot(profile(), 1);
        var oldSuccess = new ProviderTransport.ConnectivityCheck(4, ESTABLISHED, 1200, 5000);
        report(snapshot, 1300, oldSuccess);
        assertFalse(needed(snapshot, 1300));
        report(snapshot, 1600, new ProviderTransport.ConnectivityCheck(4, NOT_ESTABLISHED, 1500, 5000));
        assertTrue(needed(snapshot, 1600));
        report(snapshot, 1700, oldSuccess);
        assertTrue(needed(snapshot, 1700), "Replayed success predates the selected failure");
        report(snapshot, 1800, new ProviderTransport.ConnectivityCheck(4, ESTABLISHED, 1500, 5000));
        assertTrue(needed(snapshot, 1800), "Equal timestamps do not prove recovery");
        report(snapshot, 1900, new ProviderTransport.ConnectivityCheck(4, ESTABLISHED, 1800, 5000));
        assertFalse(needed(snapshot, 1900));
    }
}
