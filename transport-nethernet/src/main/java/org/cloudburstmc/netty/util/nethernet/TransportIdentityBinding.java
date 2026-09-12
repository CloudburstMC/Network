package org.cloudburstmc.netty.util.nethernet;

import io.netty.channel.Channel;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;

import java.security.PublicKey;

/**
 * Ties the Bedrock login chain to the identity that opened the transport.
 * <p>
 * On RakNet the encryption handshake does this on its own: the session key comes out of an ECDH
 * against the chain's identity key, so only its holder can read what follows. NetherNet runs over
 * DTLS and skips that handshake, which leaves the chain unbound, and a chain is replayable until
 * something binds it. The signalling assertion binds it here, because the peer proved it holds the
 * key the assertion names before the transport was accepted, and that is the same key the chain is
 * signed with.
 * <p>
 * Checking the two agree is what makes a login chain meaningful rather than merely well formed.
 *
 * @see <a href="https://github.com/Mojang/bedrock-protocol-docs/blob/main/NetherNetOnboardingGuide.md">NetherNet onboarding guide, section 5</a>
 */
public final class TransportIdentityBinding {

    private TransportIdentityBinding() {
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

        PlayerInfo player = channel.attr(NetherNetChildChannel.PLAYER_INFO).get();
        if (player == null) {
            return "the transport carries no validated identity to bind the login chain to";
        }

        try {
            if (!player.clientPublicKey().equals(identityPublicKey)) {
                return "the login chain is signed with a different key than the one that opened the transport";
            }
        } catch (Exception e) {
            return "the transport identity has no usable key: " + e.getMessage();
        }
        return null;
    }
}
