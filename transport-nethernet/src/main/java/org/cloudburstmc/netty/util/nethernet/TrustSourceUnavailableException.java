package org.cloudburstmc.netty.util.nethernet;

import java.security.GeneralSecurityException;

/**
 * The offer could not be validated because the trusted key source could not be reached,
 * as opposed to the assertion itself being invalid. HTTP signaling answers such offers
 * with 503 instead of 400.
 */
public final class TrustSourceUnavailableException extends GeneralSecurityException {
    public TrustSourceUnavailableException(String message) {
        super(message);
    }
}
