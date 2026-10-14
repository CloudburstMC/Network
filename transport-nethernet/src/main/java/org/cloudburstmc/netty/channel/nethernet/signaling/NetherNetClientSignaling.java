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

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface NetherNetClientSignaling extends NetherNetSignaling {
    /**
     * Connects to the signaling medium (Client mode).
     *
     * @param remoteAddress The address of the signaling server to connect to.
     */
    CompletableFuture<List<IceServerInfo>> connect(SocketAddress remoteAddress);

    /**
     * Sets a handler to be called when the signaling cannot deliver the connection: the target is
     * not found or offline, or an endpoint refused the offer.
     *
     * @param handler The handler for signaling failures.
     */
    void setFailureHandler(FailureHandler handler);

    /**
     * Functional interface for handling signaling failures.
     */
    @FunctionalInterface
    interface FailureHandler {
        /**
         * Called when the signaling reports that the connection cannot be made.
         *
         * @param reason The reason or raw message payload regarding the failure.
         */
        void onFailure(String reason);
    }
}
