package org.cloudburstmc.netty.channel.nethernet.backend;

import java.nio.ByteBuffer;

/**
 * One negotiated WebRTC connection as seen by the NetherNet transport: a
 * reliable ordered byte-message stream plus lifecycle control. Counterpart
 * events arrive on the {@link WebRtcSessionListener} passed to
 * {@link WebRtcServerBackend#accept}.
 */
public interface WebRtcSession {

    /**
     * Queues the bytes between the buffer's position and limit for
     * transmission on the reliable data channel without blocking on the
     * engine's network processing. The data is copied out of the buffer
     * before this method returns, so the caller may reuse the buffer
     * immediately. Messages sent before the transport is open, or after it
     * has closed, are dropped.
     *
     * @param data the message payload, already NetherNet framed
     */
    void send(ByteBuffer data);

    /**
     * Applies a remote ICE candidate signaled for this session. Candidates
     * received before negotiation has applied the remote description are
     * buffered and applied in order once it has; callers may hand candidates
     * over as they arrive without worrying about negotiation timing.
     *
     * @param candidateSdp the candidate SDP string
     */
    void addRemoteCandidate(String candidateSdp);

    /**
     * Requests the current ICE round trip time. The callback is invoked
     * asynchronously, possibly on an engine thread, with the RTT in
     * milliseconds, or a negative value when no measurement is available.
     * The default implementation reports no measurement, so backends
     * without an RTT source need not implement this.
     *
     * @param callback receives the RTT in milliseconds or a negative value
     */
    default void requestRtt(java.util.function.DoubleConsumer callback) {
        callback.accept(-1);
    }

    /**
     * Tears the session down. Idempotent; does not fire
     * {@link WebRtcSessionListener#onTransportClosed()}.
     */
    void close();
}
