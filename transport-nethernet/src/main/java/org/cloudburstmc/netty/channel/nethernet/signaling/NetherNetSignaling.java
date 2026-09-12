package org.cloudburstmc.netty.channel.nethernet.signaling;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public interface NetherNetSignaling extends AutoCloseable {

    /**
     * Sends a signalling message to the remote peer.
     *
     * @param targetNetworkId The Network ID of the destination (String to support Realms).
     * @param data            The raw signalling payload.
     */
    default void sendSignal(String targetNetworkId, String data) {
    }

    /**
     * Sends a full SDP message with all candidates to the remote peer
     * TODO Find a better name
     *
     * @param targetNetworkId The Network ID of the destination (String to support Realms).
     * @param sdp             The full SDP message with all candidates.
     */
    default void sendFullSdp(String targetNetworkId, String sdp) {
    }

    /**
     * Sets a handler to receive signalling messages for a specific connection ID.
     *
     * @param connectionId The connection ID to listen for.
     * @param handler      The handler to process incoming signalling messages.
     */
    void setSignalHandler(long connectionId, SignalHandler handler);

    /**
     * Removes the signalling handler for a specific connection ID.
     *
     * @param connectionId The connection ID whose handler should be removed.
     */
    void removeSignalHandler(long connectionId);

    /**
     * Returns the Local Network ID of this client as a String.
     * This is required for formatting the 'candidate:' string in SDP.
     */
    String getLocalNetworkId();

    /**
     * Whether the signalling is connected and able to carry messages
     */
    boolean isActive();

    /**
     * Closes the signalling channel and releases any associated resources.
     */
    @Override
    void close();

    /**
     * Functional interface for handling incoming signals.
     */
    @FunctionalInterface
    interface SignalHandler {
        /**
         * Called when a signal is received for the registered connection ID.
         *
         * @param signal The raw signal payload.
         */
        void onSignal(String signal);
    }

    /**
     * Data structure for ICE server information.
     *
     * @param username The username for the ICE server (if applicable).
     * @param password The password for the ICE server (if applicable).
     * @param urls     The list of URLs for the ICE server.
     */
    public record IceServerInfo(String username, String password, List<String> urls) {
        private static final InternalLogger log = InternalLoggerFactory.getInstance(IceServerInfo.class);

        /**
         * Converts this server to the URI form libdatachannel expects, which carries the credentials in
         * the authority: {@code turn:user:pass@host:port?transport=udp}. Unparseable URLs are skipped.
         *
         * @return The URIs for this server.
         */
        public List<URI> toUris() {
            List<URI> uris = new ArrayList<>();
            if (urls == null) {
                return uris;
            }

            for (String url : urls) {
                if (url == null || url.isBlank()) {
                    continue;
                }

                try {
                    uris.add(new URI(withCredentials(url.trim())));
                } catch (URISyntaxException e) {
                    log.warn("Ignoring unparseable ICE server URL {}: {}", redacted(url), e.toString());
                }
            }

            return uris;
        }

        /**
         * Inserts the credentials after the scheme, leaving STUN alone as it has no authentication.
         */
        private String withCredentials(String url) {
            int scheme = url.indexOf(':');
            if (scheme < 0 || username == null || username.isEmpty() || url.regionMatches(true, 0, "stun", 0, 4)) {
                return url;
            }

            int authority = url.startsWith("://", scheme) ? scheme + 3 : scheme + 1;
            if (url.indexOf('@', authority) >= 0) {
                return url;
            }

            return url.substring(0, authority) + encode(username) + ":" + encode(password) + "@" + url.substring(
                    authority);
        }

        /**
         * The URL without whatever credentials it carries, which is the only form worth logging. A
         * TURN URL keeps its secret in the authority, and this type is handed straight to loggers
         * and exception messages.
         *
         * @param url The URL as configured
         * @return The same URL with any userinfo replaced
         */
        private static String redacted(String url) {
            int at = url.lastIndexOf('@');
            if (at < 0) {
                return url;
            }
            int scheme = url.indexOf(':');
            int authority = scheme < 0 ? 0 : (url.startsWith("://", scheme) ? scheme + 3 : scheme + 1);
            return url.substring(0, authority) + "***@" + url.substring(at + 1);
        }

        /**
         * Never prints the credentials, since a record's own form would put them in every log line
         * and error report that touches one.
         */
        @Override
        public String toString() {
            List<String> safe = new ArrayList<>();
            if (urls != null) {
                for (String url : urls) {
                    safe.add(url == null ? null : redacted(url));
                }
            }
            return "IceServerInfo[urls=" + safe
                    + ", username=" + (username == null || username.isEmpty() ? "" : "***") + "]";
        }

        /**
         * Encodes the input using %20 for spaces instead of +, which is what libdatachannel expects.
         */
        private static String encode(String value) {
            if (value == null) {
                return "";
            }

            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        }

        public static class Builder {
            private String username = "";
            private String password = "";
            private List<String> urls = List.of();

            public Builder setUsername(String username) {
                this.username = username;
                return this;
            }

            public Builder setPassword(String password) {
                this.password = password;
                return this;
            }

            public Builder setUrls(List<String> urls) {
                this.urls = urls;
                return this;
            }

            public IceServerInfo build() {
                return new IceServerInfo(username, password, urls);
            }
        }
    }
}
