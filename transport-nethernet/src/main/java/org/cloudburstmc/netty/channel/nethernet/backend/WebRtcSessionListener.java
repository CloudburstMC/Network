package org.cloudburstmc.netty.channel.nethernet.backend;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * Receives negotiation, data, and lifecycle events for one
 * {@link WebRtcSession}. Callbacks are invoked from engine threads and must
 * return promptly; heavy work belongs on the consumer's own executor.
 */
public interface WebRtcSessionListener {

    /**
     * The local SDP answer is ready to be signaled back to the remote peer.
     * Local ICE candidates follow via {@link #onLocalCandidate(String)}.
     *
     * @param answerSdp the local SDP answer
     */
    void onAnswerReady(String answerSdp);

    /**
     * A local ICE candidate was gathered and should be signaled to the
     * remote peer (trickle ICE).
     *
     * @param candidateSdp the candidate SDP string
     */
    void onLocalCandidate(String candidateSdp);

    /**
     * The data channels are open; the session can now carry traffic.
     */
    void onTransportOpen();

    /**
     * A message arrived on the reliable data channel, still carrying its
     * NetherNet framing header. The buffer wraps engine memory that is only
     * valid for the duration of this callback; the consumer must copy the
     * bytes before returning.
     *
     * @param data the raw framed message
     */
    void onMessage(ByteBuffer data);

    /**
     * A raw message arrived on the unreliable data channel. The buffer is only
     * valid during this callback and must be copied before returning. It must
     * be decoded separately from reliable fragments. Existing listeners ignore
     * this channel unless they override this callback.
     *
     * @param data the raw framed message
     */
    default void onUnreliableMessage(ByteBuffer data) {
    }

    /**
     * ICE selected (or re-selected) a candidate pair; the given address is
     * the remote transport address actually exchanging packets with us. For
     * relayed connections this is the TURN relay. Fires before
     * {@link #onTransportOpen()} on current engines, and may fire again on
     * re-nomination.
     *
     * @param address       the remote candidate's address
     * @param candidateType the remote candidate type: host, srflx, prflx, or relay
     */
    void onRemoteAddress(InetSocketAddress address, String candidateType);

    /**
     * The engine wrote previously queued message bytes to the wire, shrinking
     * its internal send buffer by the given amount. Consumers use this to
     * bound how much data they queue into the engine
     * (see {@link WebRtcSession#send}): pause above a high water mark of
     * unsent bytes, resume when this callback drains below a low water mark.
     * Default is a no-op for consumers that do not track engine buffering.
     *
     * @param bytes the number of buffered bytes handed to the wire
     */
    default void onBytesSent(long bytes) {
    }

    /**
     * SDP negotiation failed and the session has been closed. The default
     * preserves the transport-close notification for existing listeners.
     *
     * @param reason the negotiation step that failed
     */
    default void onNegotiationFailed(String reason) {
        onTransportClosed();
    }

    /**
     * The transport failed or was closed by the remote peer. Not fired for
     * local {@link WebRtcSession#close()} calls.
     */
    void onTransportClosed();
}
