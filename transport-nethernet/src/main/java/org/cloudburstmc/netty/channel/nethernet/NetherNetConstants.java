package org.cloudburstmc.netty.channel.nethernet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;

public class NetherNetConstants {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetConstants.class);

    public static final int DISCOVERY_PORT = 7551;
    public static final long APPLICATION_ID = 0xDEADBEEFL;

    // Packet IDs
    public static final int ID_DISCOVERY_REQUEST = 0x00;
    public static final int ID_DISCOVERY_RESPONSE = 0x01;
    public static final int ID_DISCOVERY_MESSAGE = 0x02;

    // WebRTC Negotiation Message Types
    public static final String RTC_NEGOTIATION_CONNECT_REQUEST = "CONNECTREQUEST";
    public static final String RTC_NEGOTIATION_CONNECT_RESPONSE = "CONNECTRESPONSE";
    public static final String RTC_NEGOTIATION_CANDIDATE_ADD = "CANDIDATEADD";
    public static final String RTC_NEGOTIATION_CONNECT_ERROR = "CONNECTERROR";

    // Signaling User Agent String
    public static final String SIGNALING_USER_AGENT = "libHttpClient/1.0.0.0";

    // Xbox Signaling Message Types
    public static final int XBOX_SIGNAL_NOT_FOUND = 0;
    public static final int XBOX_SIGNAL_SIGNAL = 1;
    public static final int XBOX_SIGNAL_CREDENTIALS = 2;
    public static final int XBOX_SIGNAL_ACCEPTED = 3;
    public static final int XBOX_SIGNAL_ACK = 4;

    // Xbox JSON-RPC Signaling Method Names
    public static final String XBOX_RPC_METHOD_TURN_AUTH = "Signaling_TurnAuth_v1_0";
    public static final String XBOX_RPC_METHOD_SEND_MESSAGE = "Signaling_SendClientMessage_v1_0";
    public static final String XBOX_RPC_METHOD_RECEIVE_MESSAGE = "Signaling_ReceiveMessage_v1_0";
    public static final String XBOX_RPC_METHOD_PING = "System_Ping_v1_0";
    public static final String XBOX_RPC_METHOD_PONG = "System_Pong_v1_0";
    public static final String XBOX_RPC_INNER_METHOD_WEBRTC = "Signaling_WebRtc_v1_0";
    public static final String XBOX_RPC_INNER_METHOD_DELIVERY = "Signaling_DeliveryNotification_V1_0";
    /** Fork addition: a self addressed message that proves our registration is routable. */
    public static final String XBOX_RPC_INNER_METHOD_ROUTE_PROBE = "RouteProbe_v1_0";

    // SCTP Constants
    public static final int MAX_SCTP_MESSAGE_SIZE = 10000;
    public static final String RELIABLE_CHANNEL_LABEL = "ReliableDataChannel";
    public static final String UNRELIABLE_CHANNEL_LABEL = "UnreliableDataChannel";

    private static final byte[] KEY_BYTES;
    private static final SecretKeySpec ENCRYPTION_KEY;
    private static final SecretKeySpec INTEGRITY_KEY;
    private static final int SIGNATURE_SIZE = 32;
    private static final FastThreadLocal<DiscoveryCrypto> DISCOVERY_CRYPTO = new FastThreadLocal<>() {
        @Override
        protected DiscoveryCrypto initialValue() throws Exception {
            return new DiscoveryCrypto();
        }
    };

    static {
        try {
            byte[] input = new byte[8];
            for (int i = 0; i < input.length; i++) {
                input[i] = (byte) (APPLICATION_ID >>> (i * 8));
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            KEY_BYTES = digest.digest(input);
            ENCRYPTION_KEY = new SecretKeySpec(KEY_BYTES, "AES");
            INTEGRITY_KEY = new SecretKeySpec(KEY_BYTES, "HmacSHA256");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Encrypts a discovery packet using AES encryption and HMAC-SHA256 for integrity.
     *
     * @param packet The ByteBuf containing the discovery packet to encrypt.
     * @return The encrypted byte array ready for transmission.
     * @throws Exception if encryption fails.
     */
    public static byte[] encryptDiscoveryPacket(ByteBuf packet) throws Exception {
        if (packet.readableBytes() > 0xffff - 2) {
            throw new IllegalArgumentException("Discovery payload exceeds its 16-bit length field");
        }
        int len = packet.readableBytes() + 2;
        byte[] payload = new byte[len];
        payload[0] = (byte) len;
        payload[1] = (byte) (len >>> 8);
        packet.readBytes(payload, 2, len - 2);

        DiscoveryCrypto crypto = DISCOVERY_CRYPTO.get();
        crypto.cipher.init(Cipher.ENCRYPT_MODE, ENCRYPTION_KEY);
        byte[] result = new byte[SIGNATURE_SIZE + crypto.cipher.getOutputSize(len)];
        crypto.cipher.doFinal(payload, 0, len, result, SIGNATURE_SIZE);
        crypto.mac.reset();
        crypto.mac.update(payload);
        crypto.mac.doFinal(result, 0);
        return result;
    }

    /**
     * Decrypts a discovery packet and verifies its integrity.
     *
     * @param input The ByteBuf containing the received discovery packet.
     * @return A ByteBuf with the decrypted payload, or null if verification fails.
     * @throws Exception if decryption fails.
     */
    public static ByteBuf decryptDiscoveryPacket(ByteBuf input) throws Exception {
        int encryptedLength = input.readableBytes() - SIGNATURE_SIZE;
        if (encryptedLength < 16 || (encryptedLength & 15) != 0 || encryptedLength > 0x10000) {
            log.debug("Invalid discovery ciphertext length: {}", encryptedLength);
            return null;
        }

        byte[] signature = new byte[SIGNATURE_SIZE];
        input.readBytes(signature);

        byte[] encrypted = new byte[encryptedLength];
        input.readBytes(encrypted);

        DiscoveryCrypto crypto = DISCOVERY_CRYPTO.get();
        crypto.cipher.init(Cipher.DECRYPT_MODE, ENCRYPTION_KEY);
        byte[] payloadBytes = crypto.cipher.doFinal(encrypted);
        if (payloadBytes.length < 2) {
            return null;
        }
        crypto.mac.reset();
        byte[] calculatedSignature = crypto.mac.doFinal(payloadBytes);

        if (!MessageDigest.isEqual(signature, calculatedSignature)) {
            log.debug("Invalid discovery packet signature");
            return null;
        }

        int declaredLength = (payloadBytes[0] & 0xff) | ((payloadBytes[1] & 0xff) << 8);
        // Match vanilla's minimum-length check; parse the complete datagram even when understated.
        if (declaredLength > payloadBytes.length) {
            log.debug("Invalid discovery plaintext length: {} (actual: {})", declaredLength, payloadBytes.length);
            return null;
        }
        ByteBuf payload = Unpooled.wrappedBuffer(payloadBytes);
        payload.skipBytes(2);

        return payload;
    }

    // JCE engines are mutable. Reuse them per thread and reset before each operation,
    // including after a malformed packet caused the preceding operation to fail.
    private static final class DiscoveryCrypto {
        final Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        final Mac mac = Mac.getInstance("HmacSHA256");

        DiscoveryCrypto() throws Exception {
            mac.init(INTEGRITY_KEY);
        }
    }

    /**
     * Builds a signaling message for a CONNECTREQUEST.
     *
     * @param connectionId   The unique connection ID.
     * @param sdp            The SDP payload.
     * @return The formatted signaling message.
     */
    public static String buildSignalConnectRequest(long connectionId, String sdp) {
        return RTC_NEGOTIATION_CONNECT_REQUEST + " " + Long.toUnsignedString(connectionId) + " " + sdp;
    }

    /**
     * Builds a signaling message for a CONNECTRESPONSE.
     *
     * @param connectionId   The unique connection ID.
     * @param sdp            The SDP payload.
     * @return The formatted signaling message.
     */
    public static String buildSignalConnectResponse(long connectionId, String sdp) {
        return RTC_NEGOTIATION_CONNECT_RESPONSE + " " + Long.toUnsignedString(connectionId) + " " + sdp;
    }

    /**
     * Builds a signaling message for a CANDIDATEADD.
     *
     * @param connectionId   The unique connection ID.
     * @param candidateSdp   The candidate SDP string.
     * @return The formatted signaling message.
     */
    public static String buildSignalCandidateAdd(long connectionId, String candidateSdp) {
        return RTC_NEGOTIATION_CANDIDATE_ADD + " " + Long.toUnsignedString(connectionId) + " " + candidateSdp;
    }

    /**
     * Parses the {@code a=max-message-size} attribute from an SDP description.
     * This is the maximum SCTP user message size, in bytes, that the describing
     * endpoint is willing to receive, so outbound fragmentation must never
     * exceed the value advertised by the remote peer.
     *
     * @param sdp      the SDP description to scan, may be null
     * @param fallback the value to return when the attribute is absent or invalid
     * @return the advertised maximum message size, or {@code fallback} if not found
     */
    public static int parseMaxMessageSize(String sdp, int fallback) {
        if (sdp == null) {
            return fallback;
        }
        for (String line : sdp.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("a=max-message-size:")) {
                try {
                    int value = Integer.parseInt(trimmed.substring("a=max-message-size:".length()).trim());
                    if (value > 1) {
                        return value;
                    }
                } catch (NumberFormatException ignored) {
                    // Fall through to the fallback for a malformed attribute.
                }
            }
        }
        return fallback;
    }
}
