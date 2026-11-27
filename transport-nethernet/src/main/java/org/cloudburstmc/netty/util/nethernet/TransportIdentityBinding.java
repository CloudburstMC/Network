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

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Arrays;

/**
 * Ties the Bedrock login chain to the identity that opened the transport.
 * <p>
 * On RakNet the encryption handshake does this on its own: the session key comes out of an ECDH
 * against the chain's identity key, so only its holder can read what follows. NetherNet runs over
 * DTLS and skips that handshake, which leaves the chain unbound, and a chain is replayable until
 * something binds it. The signaling assertion binds it here, because the peer proved it holds the
 * key the assertion names before the transport was accepted, and that is the same key the chain is
 * signed with.
 * <p>
 * Checking the two agree is what makes a login chain meaningful rather than merely well formed.
 *
 * @see <a href="https://github.com/Mojang/bedrock-protocol-docs/blob/main/NetherNetOnboardingGuide.md">NetherNet onboarding guide, section 5</a>
 */
public final class TransportIdentityBinding {

    private static final AttributeKey<IdentityKeyVerifier> KEY =
            AttributeKey.valueOf(TransportIdentityBinding.class, "verifier");

    private TransportIdentityBinding() {
    }

    /** Install before publishing the child. The channel and admission owner may both close it. */
    public static void install(Channel channel, IdentityKeyVerifier verifier) {
        if (verifier == null) {
            throw new IllegalArgumentException("verifier");
        }
        if (channel.attr(KEY).setIfAbsent(verifier) != null) {
            verifier.close();
            throw new IllegalStateException("Identity binding already installed");
        }
        channel.closeFuture().addListener(ignored -> verifier.close());
    }

    public static IdentityKeyVerifier forPlayer(PlayerInfo player) {
        final byte[] expected;
        try {
            expected = IdentityPublicKey.canonical(player.clientPublicKey());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid signaling identity", invalid);
        }
        return new IdentityKeyVerifier() {
            @Override
            protected boolean matches(byte[] key) {
                return MessageDigest.isEqual(expected, key);
            }

            @Override
            protected void release() {
                Arrays.fill(expected, (byte) 0);
            }
        };
    }

    /** Where the binding on a channel stands, for diagnostics. */
    public enum State {
        /** The transport validated no identity, so nothing binds the login chain */
        NONE,
        /** Validated, and no login has been decided against it yet */
        PENDING,
        /** A login was accepted against it */
        ACCEPTED,
        /** Spent on a refused login, closed, or the admission behind it has lapsed */
        REJECTED
    }

    public static State state(Channel channel) {
        IdentityKeyVerifier verifier = channel.attr(KEY).get();
        if (verifier == null) {
            return State.NONE;
        }
        if (verifier.pending()) {
            return State.PENDING;
        }
        return verifier.rejected() ? State.REJECTED : State.ACCEPTED;
    }

    /** Release only after the application has accepted its existing trusted forwarding identity. */
    public static String acceptForwardedIdentity(Channel channel) {
        if (!(channel instanceof NetherNetChildChannel)) {
            return null;
        }
        IdentityKeyVerifier verifier = channel.attr(KEY).get();
        return verifier == null ? null : verifier.acceptForwardedIdentity();
    }

    /**
     * @param channel           The channel the login arrived on
     * @param identityPublicKey The key the login chain is signed with
     * @return Why the login must be rejected, or null when the two agree
     */
    public static String mismatch(Channel channel, PublicKey identityPublicKey) {
        if (!(channel instanceof NetherNetChildChannel)) {
            // RakNet binds the chain through the encryption handshake instead
            return null;
        }
        IdentityKeyVerifier verifier = channel.attr(KEY).get();
        if (verifier == null) {
            return "the transport carries no validated identity binding";
        }
        return verifier.mismatch(identityPublicKey);
    }

    /**
     * Not for callers outside the transport: it sees no channel, so it applies none of the checks
     * above it and a host reaching for it would skip them.
     *
     * @param player            The identity the transport validated, or null if it has none
     * @param identityPublicKey The key the login chain is signed with
     * @return Why the login must be rejected, or null when the two agree
     */
    static String mismatch(PlayerInfo player, PublicKey identityPublicKey) {
        if (player == null) {
            return "the transport carries no validated identity to bind the login chain to";
        }

        try {
            byte[] admitted = IdentityPublicKey.canonical(player.clientPublicKey());
            if (!MessageDigest.isEqual(admitted, IdentityPublicKey.canonical(identityPublicKey))) {
                return "the login chain is signed with a different key than the one that opened the transport";
            }
        } catch (Exception e) {
            return "the transport identity has no usable key: " + e.getMessage();
        }
        return null;
    }
}
