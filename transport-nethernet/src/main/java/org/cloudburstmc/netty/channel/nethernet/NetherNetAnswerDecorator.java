package org.cloudburstmc.netty.channel.nethernet;

/**
 * Transforms an SDP answer before it is signaled back to the remote peer.
 * The primary use is attaching the server identity assertion (the
 * {@code a=identity} attribute) that clients reached over HTTP signaling
 * require in every answer; consumers own the assertion format and keys,
 * the transport only provides this seam.
 *
 * Called from engine threads; implementations must be thread safe and
 * return promptly. A thrown exception makes the channel fall back to the
 * undecorated answer rather than dropping the exchange.
 */
@FunctionalInterface
public interface NetherNetAnswerDecorator {

    /**
     * Returns the answer to actually signal, derived from the negotiated
     * answer.
     *
     * @param answerSdp the negotiated SDP answer
     * @return the SDP answer to signal to the peer
     * @throws Exception if decoration fails; the undecorated answer is used
     */
    String decorate(String answerSdp) throws Exception;
}
