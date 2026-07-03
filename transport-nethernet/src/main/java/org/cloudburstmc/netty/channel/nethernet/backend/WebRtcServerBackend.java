package org.cloudburstmc.netty.channel.nethernet.backend;

import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling.IceServerInfo;

import java.util.List;

/**
 * The seam between the NetherNet transport and a WebRTC engine. Everything the
 * transport needs from WebRTC goes through this interface and its companions
 * {@link WebRtcSession} and {@link WebRtcSessionListener}; no WebRTC engine
 * types appear above it. NetherNet uses a deliberately tiny slice of WebRTC
 * (data channels only, no media, no renegotiation), which keeps this surface
 * small, makes the transport testable without native libraries, and keeps a
 * future engine swap contained to one implementation class.
 */
public interface WebRtcServerBackend extends AutoCloseable {

    /**
     * Accepts an incoming connection offer and starts negotiating a session
     * for it with trickle ICE: the answer is delivered as soon as the local
     * description applies and candidates follow individually. Returns
     * immediately; negotiation progress, inbound data, and lifecycle events
     * are reported through the listener, potentially from engine threads.
     *
     * @param offerSdp   the remote peer's SDP offer
     * @param iceServers STUN/TURN servers to use for this session, may be
     *                   empty
     * @param listener   receives negotiation and data events for the session
     * @return the session handle
     */
    default WebRtcSession accept(String offerSdp, List<IceServerInfo> iceServers, WebRtcSessionListener listener) {
        return accept(offerSdp, iceServers, listener, false);
    }

    /**
     * Accepts an incoming connection offer and starts negotiating a session
     * for it. With {@code fullIceAnswer} the session gathers every local ICE
     * candidate before reporting the answer:
     * {@link WebRtcSessionListener#onAnswerReady} fires once, after gathering
     * completes, with an answer containing all candidates, and no
     * {@link WebRtcSessionListener#onLocalCandidate} calls are made. Required
     * by request/response signaling (HTTP), where the whole SDP exchange must
     * fit one round trip.
     *
     * @param offerSdp      the remote peer's SDP offer
     * @param iceServers    STUN/TURN servers to use for this session, may be
     *                      empty
     * @param listener      receives negotiation and data events for the session
     * @param fullIceAnswer true to deliver a single complete answer after
     *                      candidate gathering finishes, false for trickle ICE
     * @return the session handle
     */
    WebRtcSession accept(String offerSdp, List<IceServerInfo> iceServers, WebRtcSessionListener listener, boolean fullIceAnswer);

    /**
     * Releases every engine resource this backend owns. Live sessions are
     * closed. Idempotent.
     */
    @Override
    void close();
}
