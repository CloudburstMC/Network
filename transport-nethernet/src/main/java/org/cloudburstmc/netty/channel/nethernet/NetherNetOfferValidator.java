package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.util.nethernet.ClientIdentity;

/** Validates and authorizes an offer before a WebRTC peer is created. */
@FunctionalInterface
public interface NetherNetOfferValidator {
    /**
     * HTTP signaling runs this on its bounded validation executor, outside I/O loops.
     * Custom implementations must verify token trust and the SDP fingerprint signature.
     * Implementations may be invoked concurrently and must be thread-safe.
     *
     * @param offerSdp the original offer, including its identity assertion
     * @return the validated identity, never null
     * @throws Exception if validation or application authorization fails
     */
    ClientIdentity validate(String offerSdp) throws Exception;
}
