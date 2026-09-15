package org.cloudburstmc.netty.signaling.admission;

import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCandidateSnapshotTest {
    @Test void canonicalMaterialIsImmutableOrderIndependentAndFamilyPortTypeSensitive() {
        var v4 = new NativeCandidateSnapshot.Candidate(new InetSocketAddress("127.0.0.1", 19132), NativeCandidateSnapshot.Type.HOST);
        var v6 = new NativeCandidateSnapshot.Candidate(new InetSocketAddress("::1", 19133), NativeCandidateSnapshot.Type.HOST);
        var mutable = new ArrayList<>(List.of(v6, v4, v4));
        var first = new NativeCandidateSnapshot(mutable); mutable.clear();
        var same = new NativeCandidateSnapshot(List.of(v4, v6));
        assertEquals(first, same); assertEquals(first.materialRevision(), same.materialRevision());
        assertEquals(64, first.materialRevision().length()); assertEquals(2, first.candidates().size());
        assertThrows(UnsupportedOperationException.class, () -> first.candidates().clear());
        assertNotEquals(v4.family(), v6.family());
        for (var changed : List.of(new NativeCandidateSnapshot(List.of(v4)), new NativeCandidateSnapshot(List.of()),
                NativeCandidateSnapshot.hosts(List.of(new InetSocketAddress("127.0.0.1", 19134))),
                new NativeCandidateSnapshot(List.of(new NativeCandidateSnapshot.Candidate(v4.endpoint(), NativeCandidateSnapshot.Type.SRFLX)))))
            assertNotEquals(first.materialRevision(), changed.materialRevision());
    }
    @Test void srflxCannotOpenPublicationBeforeCandidateLeaseSupportExists() {
        var mapped = new NativeCandidateSnapshot(List.of(new NativeCandidateSnapshot.Candidate(
                new InetSocketAddress("8.8.8.8", 19132), NativeCandidateSnapshot.Type.SRFLX)));
        var failure = assertThrows(java.util.concurrent.CompletionException.class, () -> NativeProviderTransport.openControlledVersion2(
                null, null, mapped, null, null, null).toCompletableFuture().join());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("candidate lease"), "reject before binding or loading identity");
    }
    @Test void boundedResolvedConcreteEndpointsOnly() {
        for (var invalid : List.of(new InetSocketAddress("0.0.0.0", 19132), new InetSocketAddress("::", 19132),
                new InetSocketAddress("127.0.0.1", 0), InetSocketAddress.createUnresolved("host.invalid", 19132)))
            assertThrows(IllegalArgumentException.class, () -> NativeCandidateSnapshot.hosts(List.of(invalid)));
        assertThrows(IllegalArgumentException.class, () -> NativeCandidateSnapshot.hosts(Collections.nCopies(33, new InetSocketAddress("127.0.0.1", 19132))));
        assertTrue(NativeCandidateSnapshot.hosts(List.of()).candidates().isEmpty());
    }
}
