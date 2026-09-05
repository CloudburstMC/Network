package org.cloudburstmc.netty.channel.nethernet.admission;

/** Local-only validation against a bounded background key/profile snapshot. No network calls. */
@FunctionalInterface
public interface AdmissionValidator {
    /** Return null on rejection. Must authenticate the token AND raw STUN integrity. */
    VerifiedAdmission validate(byte[] packet, StunBinding binding, long nowMillis);
}
