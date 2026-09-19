package org.cloudburstmc.netty.signaling.provider.connectivity;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;

import static org.cloudburstmc.netty.signaling.provider.connectivity.EndpointSelection.*;
import static org.junit.jupiter.api.Assertions.*;

class EndpointSelectionTest {
    static InetSocketAddress endpoint(String address, int port) { return new InetSocketAddress(address, port); }
    static Candidate hint(String address, Provenance source) { return new Candidate(endpoint(address, 19133), source); }

    @Test void configuredSetExcludesBothFamiliesOfAutomaticDiscovery() {
        var configured = List.of(endpoint("8.8.8.8", 29133));
        var hints = List.of(hint("1.1.1.1", Provenance.SERVER_PROPERTIES), hint("2606:4700:4700::1111", Provenance.NATIVE_HOST));
        var selected = select(endpoint("::", 19133), configured, hints);
        assertTrue(selected.configured());
        assertEquals(List.of(new Candidate(configured.get(0), Provenance.CONFIGURED)), selected.candidates());
        assertTrue(selected.candidates(Family.IPV6).isEmpty());
        var v6Only = select(endpoint("0.0.0.0", 19133), List.of(endpoint("2606:4700:4700::1111", 29133)), hints);
        assertTrue(v6Only.candidates(Family.IPV4).isEmpty(), "Explicit family translation is allowed, without automatic fallback");
        assertEquals(1, v6Only.candidates(Family.IPV6).size());
    }

    @Test void invalidExplicitValueCannotFallBackToPublicLocalCandidates() {
        for (String address : List.of("0.0.0.0", "::", "127.0.0.1", "203.0.113.1", "fe80::1")) {
            assertThrows(IllegalArgumentException.class, () -> select(endpoint("1.1.1.1", 19133),
                    List.of(endpoint(address, 19133)), List.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> select(endpoint("1.1.1.1", 19133),
                List.of(InetSocketAddress.createUnresolved("example.com", 19133)), List.of()));
    }

    @Test void localHintsRetainPrivateFallbackAndAreDeterministicAndFamilyAware() {
        var hints = List.of(hint("8.8.8.8", Provenance.NATIVE_HOST), hint("8.8.8.8", Provenance.SERVER_PROPERTIES),
                hint("2606:4700:4700::1111", Provenance.LOCAL_INTERFACE), hint("10.0.0.1", Provenance.LOCAL_INTERFACE),
                hint("fd00::1", Provenance.NATIVE_HOST));
        var dual = select(endpoint("::", 19133), List.of(), hints);
        assertFalse(dual.configured());
        assertEquals(4, dual.candidates().size());
        assertEquals(Provenance.SERVER_PROPERTIES, dual.publicCandidates(Family.IPV4).get(0).provenance());
        assertEquals(2, select(endpoint("0.0.0.0", 19133), List.of(), hints).candidates().size());
        assertEquals(1, select(endpoint("192.168.1.1", 19133), List.of(), List.of()).candidates().size());
        assertEquals(Provenance.LOCAL_BIND, select(endpoint("8.8.8.8", 19133), List.of(), hints).candidates().get(0).provenance());
    }

    @Test void noEphemeralOrBackendSocketHintsAndNoMutableCollections() {
        assertThrows(IllegalArgumentException.class, () -> select(endpoint("::", 19133), List.of(),
                List.of(new Candidate(endpoint("8.8.8.8", 25565), Provenance.SERVER_PROPERTIES))));
        var hints = new java.util.ArrayList<>(List.of(hint("8.8.8.8", Provenance.NATIVE_HOST)));
        var selection = select(endpoint("::", 19133), List.of(), hints);
        hints.clear();
        assertEquals(1, selection.candidates().size());
        assertThrows(UnsupportedOperationException.class, () -> selection.candidates().clear());
        assertThrows(IllegalArgumentException.class, () -> select(endpoint("::", 0), List.of(), List.of()));
    }
}
