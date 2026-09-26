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

package org.cloudburstmc.netty.channel.nethernet.signaling;



public interface NetherNetSignaling extends AutoCloseable {

    /**
     * Sends a signaling message to the remote peer. Required when {@link #usesTrickleIce} is
     * true, which is the default.
     *
     * @param targetNetworkId The Network ID of the destination (String to support Realms).
     * @param data            The raw signaling payload.
     */
    default void sendSignal(String targetNetworkId, String data) {
        throw new UnsupportedOperationException(getClass().getName()
                + " trickles candidates but does not send signals");
    }

    /**
     * Sends a complete session description, every gathered candidate included, to the remote peer.
     * This is how a description travels when {@link #usesTrickleIce} is false, on either side.
     *
     * @param targetNetworkId The Network ID of the destination (String to support Realms).
     * @param sdp             The complete description.
     */
    default void sendDescription(String targetNetworkId, String sdp) {
        throw new UnsupportedOperationException(getClass().getName()
                + " does not trickle candidates but does not send descriptions");
    }

    /**
     * Whether this signaling delivers ICE candidates one by one as they are gathered. Signaling
     * that cannot, such as a single HTTP exchange, receives one {@link #sendDescription} once
     * gathering is complete and no candidate signals at all.
     *
     * @return true if candidates are trickled as they are gathered
     */
    default boolean usesTrickleIce() {
        return true;
    }

    /**
     * Sets a handler to receive signaling messages for a specific connection ID.
     *
     * @param connectionId The connection ID to listen for, the token the initiator chose.
     * @param handler      The handler to process incoming signaling messages.
     */
    void setSignalHandler(String connectionId, SignalHandler handler);

    /**
     * Removes the signaling handler for a specific connection ID.
     *
     * @param connectionId The connection ID whose handler should be removed.
     */
    void removeSignalHandler(String connectionId);

    /**
     * Returns the Local Network ID this side is addressed by. A client puts it in its join, a
     * server that is addressed by host name rather than network id, such as HTTP signaling,
     * returns an empty string.
     */
    String getLocalNetworkId();

    /**
     * Whether the signaling channel is open. A connection that died without closing still counts
     * as open: Xbox signaling detects that with {@link AbstractNetherNetXboxSignaling#isChannelAlive(long)},
     * and a registration that died on an open socket with {@link NetherNetXboxRpcSignaling#isRouteAlive(long)}.
     */
    boolean isChannelAlive();

    /**
     * Closes the signaling channel and releases any associated resources.
     */
    @Override
    void close();

    /**
     * Functional interface for handling incoming signals.
     */
    @FunctionalInterface
    interface SignalHandler {
        /**
         * Called when a signal is received for the registered connection ID, on whichever thread
         * the signaling reads from. The channels move themselves back onto their event loop.
         *
         * @param signal The raw signal payload.
         */
        void onSignal(String signal);
    }
}
