package dev.kastle.webrtc;

import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcRtt;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebRtcRttTest {

    @Test
    void usesTransportSelectedCandidatePair() {
        Map<String, RTCStats> stats = new LinkedHashMap<>();
        stats.put("fallback", candidatePair("fallback", true, "succeeded", 0.100));
        stats.put("selected", candidatePair("selected", false, "succeeded", 0.0255));
        stats.put("transport", stat(RTCStatsType.TRANSPORT, "transport",
                Map.of("selectedCandidatePairId", "selected")));

        assertEquals(25.5, WebRtcRtt.extractRttMillis(report(stats)), 0.0001);
    }

    @Test
    void fallsBackToNominatedSucceededCandidatePair() {
        Map<String, RTCStats> stats = new LinkedHashMap<>();
        stats.put("failed", candidatePair("failed", true, "failed", 0.100));
        stats.put("not-nominated", candidatePair("not-nominated", false, "succeeded", 0.200));
        stats.put("active", candidatePair("active", true, "SuCcEeDeD", 0.0175));

        assertEquals(17.5, WebRtcRtt.extractRttMillis(report(stats)), 0.0001);
    }

    @Test
    void returnsUnavailableWithoutRoundTripTime() {
        Map<String, RTCStats> stats = Map.of(
                "transport", stat(RTCStatsType.TRANSPORT, "transport",
                        Map.of("selectedCandidatePairId", "selected")),
                "selected", stat(RTCStatsType.CANDIDATE_PAIR, "selected", Map.of()));

        assertEquals(-1, WebRtcRtt.extractRttMillis(report(stats)));
    }

    private static RTCStats candidatePair(String id, boolean nominated, String state, double rttSeconds) {
        return stat(RTCStatsType.CANDIDATE_PAIR, id, Map.of(
                "nominated", nominated,
                "state", state,
                "currentRoundTripTime", rttSeconds));
    }

    private static RTCStats stat(RTCStatsType type, String id, Map<String, Object> attributes) {
        return new RTCStats(0, type, id, attributes);
    }

    private static RTCStatsReport report(Map<String, RTCStats> stats) {
        return new RTCStatsReport(stats, 0);
    }
}
