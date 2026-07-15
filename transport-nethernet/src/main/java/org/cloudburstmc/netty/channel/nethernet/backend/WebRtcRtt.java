package org.cloudburstmc.netty.channel.nethernet.backend;

import dev.kastle.webrtc.RTCPeerConnection;
import dev.kastle.webrtc.RTCStats;
import dev.kastle.webrtc.RTCStatsReport;
import dev.kastle.webrtc.RTCStatsType;

import java.util.Map;
import java.util.function.DoubleConsumer;

/**
 * Extracts the active candidate pair's round trip time from a WebRTC stats
 * report. libwebrtc refreshes the value with its periodic STUN checks, so it
 * measures the pure network path below any client processing.
 */
public final class WebRtcRtt {

    private WebRtcRtt() {
    }

    /**
     * Requests a one shot RTT sample from the peer connection, reporting a
     * negative value when the connection is absent or the stats request races
     * teardown. Shared by the server backend session and the client channel so
     * their teardown behavior cannot drift.
     */
    public static void requestRtt(RTCPeerConnection pc, DoubleConsumer callback) {
        if (pc == null) {
            callback.accept(-1);
            return;
        }
        try {
            pc.getStats(report -> callback.accept(extractRttMillis(report)));
        } catch (Exception e) {
            // A stats request on a closing connection just reports no measurement.
            callback.accept(-1);
        }
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
