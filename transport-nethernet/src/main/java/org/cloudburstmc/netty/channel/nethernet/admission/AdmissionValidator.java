package org.cloudburstmc.netty.channel.nethernet.admission;

/**
 * Validates admission metadata using locally installed keys. No network calls.
 */
@FunctionalInterface
public interface AdmissionValidator {
    /**
     * Return connection settings, or null to reject. Native code separately verifies STUN integrity.
     */
    VerifiedAdmission validate(AdmissionRequest request, long nowMillis);
}
