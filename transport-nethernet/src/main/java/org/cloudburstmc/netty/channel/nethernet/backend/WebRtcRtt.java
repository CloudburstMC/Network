package org.cloudburstmc.netty.channel.nethernet.backend;

import dev.kastle.webrtc.RTCStats;
import dev.kastle.webrtc.RTCStatsReport;
import dev.kastle.webrtc.RTCStatsType;

import java.util.Map;

/**
 * Extracts the active candidate pair's round trip time from a WebRTC stats
 * report. libwebrtc refreshes the value with its periodic STUN checks, so it
 * measures the pure network path below any client processing.
 */
public final class WebRtcRtt {

    private WebRtcRtt() {
    }

    /**
     * @return the selected candidate pair's current RTT in milliseconds, or
     *         a negative value when the report carries no measurement
     */
    public static double extractRttMillis(RTCStatsReport report) {
        Map<String, RTCStats> stats = report.getStats();

        RTCStats pair = null;
        for (RTCStats s : stats.values()) {
            if (s.getType() == RTCStatsType.TRANSPORT) {
                Object id = s.getAttributes().get("selectedCandidatePairId");
                if (id instanceof String selected) {
                    pair = stats.get(selected);
                }
                break;
            }
        }

        if (pair == null) {
            for (RTCStats s : stats.values()) {
                if (s.getType() != RTCStatsType.CANDIDATE_PAIR) {
                    continue;
                }
                Map<String, Object> attrs = s.getAttributes();
                Object state = attrs.get("state");
                boolean succeeded = state == null || "succeeded".equalsIgnoreCase(String.valueOf(state));
                if (Boolean.TRUE.equals(attrs.get("nominated")) && succeeded) {
                    pair = s;
                    break;
                }
            }
        }

        if (pair == null) {
            return -1;
        }
        Object rtt = pair.getAttributes().get("currentRoundTripTime");
        if (rtt instanceof Number number) {
            return number.doubleValue() * 1000.0;
        }
        return -1;
    }
}
