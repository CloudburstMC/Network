/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.util.nethernet;

import java.security.PublicKey;

/** One login decision, owned by a transport channel. Never exposes admission secrets. */
public abstract class IdentityKeyVerifier implements AutoCloseable {
    private enum State {
        PENDING, ACCEPTED, REJECTED, CLOSED
    }

    private State state = State.PENDING;

    /**
     * Spends the binding on one login, whatever the outcome.
     *
     * @param key The key the login chain is signed with
     * @return Why the login must be rejected, or null when it may proceed
     */
    public final synchronized String mismatch(PublicKey key) {
        if (state != State.PENDING) {
            return "the transport identity binding has already been consumed";
        }
        boolean accepted = false;
        try {
            accepted = usable() && matches(IdentityPublicKey.canonical(key));
            return accepted ? null : "the login key does not match the admitted transport identity";
        } catch (Exception invalid) {
            return "the transport or login identity key is invalid";
        } finally {
            state = accepted ? State.ACCEPTED : State.REJECTED;
            release();
        }
    }

    /**
     * Spends the binding without a key of its own, for an application's already authenticated and
     * explicitly trusted forwarding path.
     *
     * @return Why the login must be rejected, or null when it may proceed
     */
    public final synchronized String acceptForwardedIdentity() {
        if (state != State.PENDING) {
            return "the transport identity binding has already been consumed";
        }
        boolean accepted = usable();
        state = accepted ? State.ACCEPTED : State.REJECTED;
        release();
        return accepted ? null : "the transport identity binding has expired or been revoked";
    }

    /** @return Whether a login may still be decided against this binding */
    public final synchronized boolean pending() {
        return state == State.PENDING;
    }

    /** @return Whether nothing can be admitted against this binding any more */
    public final synchronized boolean rejected() {
        return state == State.REJECTED || state == State.CLOSED || (state == State.PENDING && !usable());
    }

    @Override
    public final synchronized void close() {
        if (state == State.PENDING) {
            state = State.CLOSED;
            release();
        }
    }

    /** @return Whether the admission behind this binding is still live */
    protected boolean usable() {
        return true;
    }

    /**
     * @param canonicalKey The login key, canonicalised by {@link IdentityPublicKey}
     * @return Whether it is the key the transport admitted
     */
    protected abstract boolean matches(byte[] canonicalKey);

    /** Erases whatever the binding held. Called once, whichever way it is spent. */
    protected abstract void release();
}
