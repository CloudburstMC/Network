package org.cloudburstmc.netty.channel.nethernet.backend;

import java.nio.ByteBuffer;
import java.util.function.DoubleConsumer;

/**
 * One negotiated WebRTC connection as seen by the NetherNet transport: a
 * reliable ordered outbound stream plus inbound data and lifecycle control.
 * Counterpart events arrive on the {@link WebRtcSessionListener} passed to
 * {@link WebRtcServerBackend#accept}.
 */
public interface WebRtcSession {

    /**
     * Queues the bytes between the buffer's position and limit for
     * transmission on the reliable data channel without blocking on the
     * engine's network processing. The data is copied out of the buffer
     * before this method returns, so the caller may reuse the buffer
     * immediately. A known closed or unavailable transport must fail the call;
     * synchronous binding failures propagate to the caller. Returning normally
     * means the binding call completed, not that the native engine accepted the
     * message or the peer received it. The current binding logs asynchronous
     * send rejections; channel closures are reported separately through the listener.
     *
     * @param data the message payload, already NetherNet framed
     * @throws IllegalStateException if the session is known to be closed or its data channel is unavailable
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
    default void requestRtt(DoubleConsumer callback) {
        callback.accept(-1);
    }

    /**
     * Tears the session down. Idempotent; does not fire
     * {@link WebRtcSessionListener#onTransportClosed()}.
     */
    void close();
}
