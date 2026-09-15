package org.cloudburstmc.netty.signaling.provider.connectivity;

import org.cloudburstmc.netty.signaling.control.CandidateLeaseCodec;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection.Family;
import static org.junit.jupiter.api.Assertions.*;

class ObservationLeaseTrackerTest {
    private static final String INCARNATION = "0123456789abcdef0123456789abcdef";
    private static final CandidateLeaseCodec.NativeOwner OWNER = new CandidateLeaseCodec.NativeOwner(5, INCARNATION, "candidate_owner_claim_01");
    private static final long WALL = 1789400000000L, START = 10000000000L, MILLIS = 1000000L, AGE = 300000 * MILLIS;
    private static final class Clock {
        long wall = WALL, nano = START;
        void advance(long millis) { wall += millis; nano += millis * MILLIS; }
        ObservationLeaseTracker tracker() { return new ObservationLeaseTracker(INCARNATION, () -> wall, () -> nano); }
    }
    private static InetSocketAddress endpoint(String address, int port) {
        try { return new InetSocketAddress(EndpointAddress.parse(address), port); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static EndpointConnectivityController.Observation sample(Clock clock, Family family, long epoch, long revision, long sequence,
                                                                   String mapping, int port, long succeededAt) {
        return new EndpointConnectivityController.Observation(endpoint(family == Family.IPV4 ? "1.1.1.1" : "2606:4700:4700::1111", 3478),
                endpoint(mapping, port), EndpointConnectivityController.TransactionState.SUCCEEDED,
                epoch, revision, sequence, 0, Math.max(0, (clock.nano - succeededAt) / MILLIS), succeededAt + AGE);
    }
    private static EndpointConnectivityController.Observation sample(Clock clock, long sequence, long succeededAt) {
        return sample(clock, Family.IPV4, 1, 1, sequence, "8.8.8.8", 43000, succeededAt);
    }
    private static EndpointConnectivityController.Snapshot snapshot(EndpointConnectivityController.Observation... samples) {
        var lanes = new EnumMap<Family, EndpointConnectivityController.FamilySnapshot>(Family.class);
        for (var sample : samples) lanes.put(Family.of(sample.mapped().getAddress()), lane(sample, EndpointConnectivityController.State.STUN_FRESH));
        return new EndpointConnectivityController.Snapshot(1, lanes);
    }
    private static EndpointConnectivityController.FamilySnapshot lane(EndpointConnectivityController.Observation observation,
                                                                    EndpointConnectivityController.State state) {
        return new EndpointConnectivityController.FamilySnapshot(state, EndpointConnectivityController.CheckOutcome.UNKNOWN,
                OptionalLong.empty(), List.of(), Optional.ofNullable(observation),
                state == EndpointConnectivityController.State.STUN_FRESH ? Optional.of(observation.mapped()) : Optional.empty());
    }
    private static CandidateLeaseCodec.Profile profile(List<CandidateLeaseCodec.Observation> observations, String key) {
        var candidates = observations.stream().map(o -> new CandidateLeaseCodec.Candidate(
                o.family().equals("ipv4") ? "8.8.8.8" : "2606:4700:4700::1001", o.port(), 1, o.family(),
                2130706431, "udp", "srflx")).toList();
        return new CandidateLeaseCodec.Profile(candidates, CandidateLeaseCodec.ADMISSION_CAPABILITY, INCARNATION, key,
                "sha-256 " + String.join(":", java.util.Collections.nCopies(32, "AB")), 262144, 5000);
    }

    @Test void repeatedSuccessAndFailedRefreshRetainIdenticalDatesAndOriginalDeadline() {
        Clock clock = new Clock(); var tracker = clock.tracker();
        var first = tracker.capture(snapshot(sample(clock, 1, START))); var original = first.observations().get(0);
        assertEquals(WALL - 1, original.observedAt()); assertEquals(WALL + 269999, original.expiresAt());
        clock.advance(15000);
        var prior = sample(clock, 1, START);
        var failed = new EndpointConnectivityController.Observation(prior.server(), prior.mapped(), EndpointConnectivityController.TransactionState.FAILED,
                1, 1, 1, 7, 15000, prior.freshUntilNanos() + 999999);
        var repeat = tracker.capture(snapshot(failed));
        assertSame(original, repeat.observations().get(0)); first.requireCurrent();
        clock.advance(254998); first.requireCurrent();
        clock.advance(1); assertThrows(IllegalStateException.class, first::requireCurrent);
        assertThrows(IllegalStateException.class, repeat::requireCurrent);
        assertTrue(tracker.capture(snapshot(sample(clock, 1, START))).observations().isEmpty());
    }

    @Test void profileAndKeyRebindingDoesNotChangeObservationIdentityOrExpiry() {
        Clock clock = new Clock(); var tracker = clock.tracker(); var capture = tracker.capture(snapshot(sample(clock, 1, START)));
        var initial = capture.bind(profile(capture.observations(), "A001"), OWNER);
        clock.advance(1000);
        var rebound = capture.bind(profile(capture.observations(), "B002"), OWNER);
        assertEquals(initial.observations(), rebound.observations()); assertEquals(initial.expiresAt(), rebound.expiresAt());
        assertNotEquals(initial.profileSha256(), rebound.profileSha256());
        assertNotEquals(CandidateLeaseCodec.leasesDigest(initial), CandidateLeaseCodec.leasesDigest(rebound));
    }

    @Test void newerSuccessKeepsOlderCaptureLiveUntilItsOwnDeadline() {
        Clock clock = new Clock(); var tracker = clock.tracker(); var old = tracker.capture(snapshot(sample(clock, 1, START)));
        clock.advance(15000); var next = tracker.capture(snapshot(sample(clock, 2, clock.nano)));
        assertEquals(old.observations().get(0).expiresAt() + 15000, next.observations().get(0).expiresAt());
        old.requireCurrent(); next.requireCurrent();
        clock.advance(254999); assertThrows(IllegalStateException.class, old::requireCurrent); next.requireCurrent();
    }

    @Test void materialRemapAndAbaNeverRestoreOldCapture() {
        Clock clock = new Clock(); var tracker = clock.tracker(); var a = tracker.capture(snapshot(sample(clock, 1, START)));
        clock.advance(1000);
        var b = tracker.capture(snapshot(sample(clock, Family.IPV4, 1, 2, 2, "8.8.4.4", 43001, clock.nano)));
        assertThrows(IllegalStateException.class, a::requireCurrent); b.requireCurrent();
        clock.advance(1000);
        var again = tracker.capture(snapshot(sample(clock, Family.IPV4, 1, 3, 3, "8.8.8.8", 43000, clock.nano)));
        assertThrows(IllegalStateException.class, a::requireCurrent); assertThrows(IllegalStateException.class, b::requireCurrent); again.requireCurrent();
        assertEquals(a.observations().get(0).addressHex(), again.observations().get(0).addressHex());
    }

    @Test void skippedMappingRevisionsAreAllowedAndMaterialRevisionAloneRevokesOldCapture() {
        Clock clock = new Clock(); var tracker = clock.tracker(); var old = tracker.capture(snapshot(sample(clock, 1, START)));
        clock.advance(1000);
        var next = tracker.capture(snapshot(sample(clock, Family.IPV4, 1, 9, 2, "8.8.8.8", 43000, clock.nano)));
        assertThrows(IllegalStateException.class, old::requireCurrent); next.requireCurrent();
    }

    @Test void equalSequenceCannotChangeMappingRevisionAddressPortOrMonitorServer() {
        for (int field = 0; field < 4; field++) {
            Clock clock = new Clock(); var tracker = clock.tracker(); var original = sample(clock, 1, START);
            var old = tracker.capture(snapshot(original));
            var altered = new EndpointConnectivityController.Observation(field == 3 ? endpoint("1.0.0.1", 3478) : original.server(),
                    field == 1 ? endpoint("8.8.4.4", 43000) : field == 2 ? endpoint("8.8.8.8", 43001) : original.mapped(), original.transactionState(),
                    1, field == 0 ? 2 : 1, 1, 0, 0, original.freshUntilNanos());
            assertThrows(IllegalArgumentException.class, () -> tracker.capture(snapshot(altered)));
            assertThrows(IllegalStateException.class, old::requireCurrent);
        }
    }

    @Test void newerSequenceCannotLowerRevisionOrChangeEndpointAtEqualRevision() {
        for (boolean lower : List.of(true, false)) {
            Clock clock = new Clock(); var tracker = clock.tracker();
            var old = tracker.capture(snapshot(sample(clock, Family.IPV4, 1, 3, 7, "8.8.8.8", 43000, START)));
            var altered = sample(clock, Family.IPV4, 1, lower ? 2 : 3, 8, lower ? "8.8.8.8" : "8.8.4.4", 43000, START);
            assertThrows(IllegalArgumentException.class, () -> tracker.capture(snapshot(altered)));
            assertThrows(IllegalStateException.class, old::requireCurrent);
        }
    }

    @Test void newerMonitorOwnerInvalidatesOldAndHighWaterSurvivesWithdrawal() {
        Clock clock = new Clock(); var tracker = clock.tracker(); var old = tracker.capture(snapshot(sample(clock, 7, START)));
        var next = tracker.capture(snapshot(sample(clock, Family.IPV4, 2, 1, 1, "8.8.8.8", 43000, START)));
        assertThrows(IllegalStateException.class, old::requireCurrent); next.requireCurrent();
        assertTrue(tracker.capture(snapshot()).observations().isEmpty());
        assertThrows(IllegalStateException.class, next::requireCurrent);
        assertThrows(IllegalArgumentException.class, () -> tracker.capture(snapshot(sample(clock, 999, START))));
        assertThrows(IllegalArgumentException.class, () -> tracker.capture(snapshot(sample(clock, Family.IPV4, 2, 1, 0, "8.8.8.8", 43000, START))));
    }

    @Test void sequenceHighWaterAlsoSurvivesExpiryAndEmptyAssociation() {
        Clock clock = new Clock(); var tracker = clock.tracker(); tracker.capture(snapshot(sample(clock, 7, START)));
        clock.advance(300000); tracker.capture(snapshot());
        assertThrows(IllegalArgumentException.class, () -> tracker.capture(snapshot(sample(clock, 6, clock.nano))));
        // The original response cannot be made young again, even after current candidates are empty.
        assertTrue(tracker.capture(snapshot(sample(clock, 7, clock.nano))).observations().isEmpty());
    }

    @Test void failedReadIneligibleMappingAndUnavailableLaneWithdrawOnlyThatFamily() {
        for (var state : List.of(EndpointConnectivityController.State.MONITOR_FAILED, EndpointConnectivityController.State.STUN_INELIGIBLE,
                EndpointConnectivityController.State.STUN_STALE, EndpointConnectivityController.State.CLOSED)) {
            Clock clock = new Clock(); var tracker = clock.tracker(); var v4 = sample(clock, 1, START);
            var v6 = sample(clock, Family.IPV6, 1, 1, 1, "2606:4700:4700::1001", 43001, START);
            var both = tracker.capture(snapshot(v4, v6));
            var lanes = new EnumMap<Family, EndpointConnectivityController.FamilySnapshot>(Family.class);
            lanes.put(Family.IPV4, lane(v4, state)); lanes.put(Family.IPV6, lane(v6, EndpointConnectivityController.State.STUN_FRESH));
            var retained = tracker.capture(new EndpointConnectivityController.Snapshot(2, lanes));
            assertEquals(List.of("ipv6"), retained.observations().stream().map(CandidateLeaseCodec.Observation::family).toList());
            assertThrows(IllegalStateException.class, both::requireCurrent); retained.requireCurrent();
        }
    }

    @Test void independentFamiliesHaveCanonicalOrderAndTheirOwnOriginalExpiry() {
        Clock clock = new Clock(); var tracker = clock.tracker(); var v4 = sample(clock, 1, START);
        clock.advance(15000); var v6 = sample(clock, Family.IPV6, 4, 2, 9, "2606:4700:4700::1001", 43001, clock.nano);
        var both = tracker.capture(snapshot(v6, v4));
        assertEquals(List.of("ipv4", "ipv6"), both.observations().stream().map(CandidateLeaseCodec.Observation::family).toList());
        var leases = both.bind(profile(both.observations(), "A001"), OWNER); assertEquals(WALL + 269999, leases.expiresAt());
        clock.advance(254999);
        assertThrows(IllegalStateException.class, both::requireCurrent);
        var remaining = tracker.capture(snapshot(v4, v6));
        assertEquals(List.of("ipv6"), remaining.observations().stream().map(CandidateLeaseCodec.Observation::family).toList());
    }

    @Test void oneMillisecondReserveAndFloorDivisionNeverRoundObservationUp() {
        for (long offset : List.of(123456L, 1123456L)) {
            Clock clock = new Clock(); var tracker = clock.tracker(); clock.nano += offset;
            var capture = tracker.capture(snapshot(sample(clock, 1, clock.nano)));
            assertEquals(WALL + Math.floorDiv(offset - MILLIS, MILLIS), capture.observations().get(0).observedAt());
        }
    }

    @Test void preemptionBetweenInitialWallAndSecondMonotonicSampleOnlyShortensLease() {
        Clock clock = new Clock(); AtomicInteger calls = new AtomicInteger();
        var tracker = new ObservationLeaseTracker(INCARNATION, () -> {
            long sampled = clock.wall; if (calls.getAndIncrement() == 0) clock.advance(2500); return sampled;
        }, () -> clock.nano);
        var capture = tracker.capture(snapshot(sample(clock, 1, clock.nano)));
        assertEquals(WALL - 1, capture.observations().get(0).observedAt());
        assertEquals(WALL + 269999, capture.observations().get(0).expiresAt());
        clock.advance(267499); assertThrows(IllegalStateException.class, capture::requireCurrent);
    }

    @Test void smallWallChangesNeverMoveAnchorAndRawForwardTimeCanExpireEarlier() {
        Clock clock = new Clock(); var tracker = clock.tracker(); var first = tracker.capture(snapshot(sample(clock, 1, START)));
        clock.wall -= 30000; first.requireCurrent();
        clock.advance(15000); var second = tracker.capture(snapshot(sample(clock, 2, clock.nano)));
        assertEquals(WALL + 14999, second.observations().get(0).observedAt());
        clock.wall += 60000; first.requireCurrent();
        clock.advance(224999); assertThrows(IllegalStateException.class, first::requireCurrent); second.requireCurrent();
        assertTrue(tracker.clockValid());
    }

    @Test void permittedWallRollbackCannotReviveAnExpiredCaptureOrRepeatedSuccess() {
        Clock clock = new Clock(); var tracker = clock.tracker(); var original = sample(clock, 1, START);
        var capture = tracker.capture(snapshot(original));
        clock.advance(259999); capture.requireCurrent(); // Ten seconds before its original expiry.
        clock.wall += 20000;
        assertThrows(IllegalStateException.class, capture::requireCurrent);
        assertTrue(tracker.clockValid()); // The raw adjustment is inside the allowed skew budget.
        clock.wall -= 20000;
        assertThrows(IllegalStateException.class, capture::requireCurrent);
        assertTrue(tracker.capture(snapshot(original)).observations().isEmpty());
        var newer = tracker.capture(snapshot(sample(clock, 2, clock.nano)));
        assertEquals(2, newer.observations().get(0).observationSequence()); newer.requireCurrent();
        assertThrows(IllegalStateException.class, capture::requireCurrent);
        assertTrue(tracker.clockValid());
    }

    @Test void concurrentOlderClockSampleCannotLowerAnAlreadyExpiredWallBound() throws Exception {
        Clock clock = new Clock();
        var sampled = new java.util.concurrent.CountDownLatch(1); var resume = new java.util.concurrent.CountDownLatch(1);
        var pause = new java.util.concurrent.atomic.AtomicBoolean(false);
        var tracker = new ObservationLeaseTracker(INCARNATION, () -> {
            long wall = clock.wall;
            if (Thread.currentThread().getName().equals("lease-guard") && pause.compareAndSet(true, false)) {
                sampled.countDown();
                try { if (!resume.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("guard timeout"); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            }
            return wall;
        }, () -> clock.nano);
        var capture = tracker.capture(snapshot(sample(clock, 1, START)));
        clock.advance(259999);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "lease-guard"));
        try {
            pause.set(true); var pending = executor.submit(capture::requireCurrent);
            assertTrue(sampled.await(2, java.util.concurrent.TimeUnit.SECONDS));
            clock.wall += 20000;
            assertThrows(IllegalStateException.class, capture::requireCurrent);
            clock.wall -= 20000; resume.countDown();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertThrows(IllegalStateException.class, capture::requireCurrent);
            assertTrue(tracker.clockValid());
        } finally { resume.countDown(); executor.shutdownNow(); tracker.close(); }
    }

    @Test void eitherDirectionOfLargeWallDiscontinuityLatchesAndDoesNotRecoverByItself() {
        for (long jump : List.of(-30001L, 30001L)) {
            Clock clock = new Clock(); var tracker = clock.tracker(); var capture = tracker.capture(snapshot(sample(clock, 1, START)));
            clock.wall += jump;
            assertThrows(IllegalStateException.class, capture::requireCurrent); assertFalse(tracker.clockValid());
            clock.wall -= jump;
            assertThrows(IllegalStateException.class, capture::requireCurrent);
            assertThrows(IllegalStateException.class, () -> tracker.capture(snapshot(sample(clock, 2, START))));
        }
    }

    @Test void deliberateRecoveryRequiresNewSuccessAndCanNeverReviveOldClockCapture() {
        Clock clock = new Clock(); var tracker = clock.tracker(); var original = sample(clock, 7, START);
        var old = tracker.capture(snapshot(original)); clock.wall += 30001;
        assertThrows(IllegalStateException.class, old::requireCurrent); clock.wall -= 30001;
        tracker.recoverAfterSuccessfulControlSynchronization();
        assertTrue(tracker.clockValid()); assertTrue(tracker.capture(snapshot(original)).observations().isEmpty());
        assertThrows(IllegalStateException.class, old::requireCurrent);
        clock.advance(1); var fresh = tracker.capture(snapshot(sample(clock, 8, clock.nano))); fresh.requireCurrent();
        assertEquals(8, fresh.observations().get(0).observationSequence());
        assertThrows(IllegalStateException.class, old::requireCurrent);
        assertThrows(IllegalStateException.class, tracker::recoverAfterSuccessfulControlSynchronization);
        assertThrows(IllegalArgumentException.class, () -> tracker.capture(snapshot(original)));
    }

    @Test void monotonicReversalInvalidRawWallAndArithmeticOverflowLatchClockFailure() {
        for (int scenario = 0; scenario < 3; scenario++) {
            Clock clock = new Clock(); var tracker = clock.tracker(); var capture = tracker.capture(snapshot(sample(clock, 1, START)));
            if (scenario == 0) clock.nano--; else if (scenario == 1) clock.wall = -1; else clock.wall = 9007199254740992L;
            assertThrows(IllegalStateException.class, capture::requireCurrent); assertFalse(tracker.clockValid());
        }
        Clock clock = new Clock(); clock.nano = Long.MIN_VALUE + 500000;
        var tracker = clock.tracker();
        assertThrows(IllegalStateException.class, () -> tracker.capture(snapshot(sample(clock, 1, clock.nano))));
        assertFalse(tracker.clockValid());
    }

    @Test void captureTimePreemptionPastOriginalExpiryCannotProduceAuthority() {
        Clock clock = new Clock(); AtomicInteger reads = new AtomicInteger();
        var tracker = new ObservationLeaseTracker(INCARNATION, () -> {
            long wall = clock.wall; if (reads.incrementAndGet() == 2) clock.advance(270000); return wall;
        }, () -> clock.nano);
        assertThrows(IllegalStateException.class, () -> tracker.capture(snapshot(sample(clock, 1, START))));
        assertFalse(tracker.clockValid());
    }

    @Test void unrepresentableDerivedWallTimeLatchesBeforeAResponseCanBeRetimestamped() {
        for (long wall : List.of(0L, 9007199254740991L)) {
            Clock clock = new Clock(); clock.wall = wall; var tracker = clock.tracker();
            assertThrows(IllegalStateException.class, () -> tracker.capture(snapshot(sample(clock, 1, START))));
            assertFalse(tracker.clockValid());
            clock.wall = WALL; tracker.recoverAfterSuccessfulControlSynchronization();
            assertTrue(tracker.capture(snapshot(sample(clock, 1, START))).observations().isEmpty());
            tracker.capture(snapshot(sample(clock, 2, START))).requireCurrent();
        }
    }

    @Test void rejectedFirstFamilyStillConsumesOtherFamilysRemapBeforeReturning() {
        Clock clock = new Clock(); var tracker = clock.tracker();
        var v4 = sample(clock, 1, START);
        var v6 = sample(clock, Family.IPV6, 1, 1, 1, "2606:4700:4700::1001", 43001, START);
        var onlyV6 = tracker.capture(snapshot(v6)); tracker.capture(snapshot(v4, v6));
        var invalidV4 = sample(clock, Family.IPV4, 1, 1, 1, "8.8.4.4", 43000, START);
        var remappedV6 = sample(clock, Family.IPV6, 1, 2, 2, "2606:4700:4700::1001", 43002, START);
        assertThrows(IllegalArgumentException.class, () -> tracker.capture(snapshot(invalidV4, remappedV6)));
        assertThrows(IllegalStateException.class, onlyV6::requireCurrent);
    }

    @Test void concurrentGuardResamplesWithoutFalseClockReversalAndStillChecksFinalMaterial() throws Exception {
        for (boolean remap : List.of(false, true)) {
            Clock clock = new Clock();
            var sampled = new java.util.concurrent.CountDownLatch(1); var resume = new java.util.concurrent.CountDownLatch(1);
            var pause = new java.util.concurrent.atomic.AtomicBoolean(false);
            var tracker = new ObservationLeaseTracker(INCARNATION, () -> {
                long wall = clock.wall;
                if (Thread.currentThread().getName().equals("lease-guard") && pause.compareAndSet(true, false)) {
                    sampled.countDown();
                    try { if (!resume.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("guard timeout"); }
                    catch (InterruptedException failure) { throw new AssertionError(failure); }
                }
                return wall;
            }, () -> clock.nano);
            var old = tracker.capture(snapshot(sample(clock, 1, START)));
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "lease-guard"));
            try {
                pause.set(true); var result = executor.submit(old::requireCurrent);
                assertTrue(sampled.await(2, java.util.concurrent.TimeUnit.SECONDS));
                clock.advance(1);
                tracker.capture(snapshot(sample(clock, Family.IPV4, 1, remap ? 2 : 1, 2, "8.8.8.8", remap ? 43001 : 43000, clock.nano)));
                resume.countDown();
                if (remap) {
                    var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(2, java.util.concurrent.TimeUnit.SECONDS));
                    assertInstanceOf(IllegalStateException.class, failure.getCause());
                } else result.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(tracker.clockValid());
            } finally { resume.countDown(); executor.shutdownNow(); tracker.close(); }
        }
    }

    @Test void guardPerformsNoControllerNativeReadAndClosureCannotReviveInAnotherOwner() {
        Clock clock = new Clock(); var tracker = clock.tracker(); var capture = tracker.capture(snapshot(sample(clock, 1, START)));
        // Only time and retained owner state are available to a guard; there is no monitor callback.
        for (int i = 0; i < 100; i++) capture.requireCurrent();
        tracker.close(); assertThrows(IllegalStateException.class, capture::requireCurrent);
        assertThrows(IllegalStateException.class, () -> tracker.capture(snapshot()));
        try (var another = new ObservationLeaseTracker("f".repeat(32), () -> clock.wall, () -> clock.nano)) {
            another.capture(snapshot(sample(clock, 1, START))).requireCurrent();
            assertThrows(IllegalStateException.class, capture::requireCurrent);
        }
    }

    @Test void ineligibleAndUnsafeNativeInputsCannotPublishLeases() {
        Clock clock = new Clock(); var tracker = clock.tracker();
        assertTrue(tracker.capture(snapshot(sample(clock, Family.IPV4, 1, 1, 1, "192.168.1.1", 43000, START))).observations().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> tracker.capture(snapshot(sample(clock, Family.IPV4, 1, 2, 9007199254740992L,
                "8.8.8.8", 43000, START))));
        var impossible = new EndpointConnectivityController.Observation(endpoint("1.1.1.1", 3478), endpoint("8.8.8.8", 43000),
                EndpointConnectivityController.TransactionState.SUCCEEDED, 2, 1, 1, 0, 0, clock.nano + AGE + 1);
        assertThrows(IllegalArgumentException.class, () -> tracker.capture(snapshot(impossible)));
    }
}
