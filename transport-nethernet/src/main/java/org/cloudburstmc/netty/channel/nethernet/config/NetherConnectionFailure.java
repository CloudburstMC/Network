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

package org.cloudburstmc.netty.channel.nethernet.config;

/**
 * Why a connection that got as far as a peer connection never carried traffic. A join refused at
 * signaling never reaches this point, and is reported by {@link NetherServerMetrics#joinRefused}.
 */
public enum NetherConnectionFailure {
    /** The data channels were not both open in time, which usually means ICE found no path. */
    HANDSHAKE_TIMEOUT,
    /** The peer gave up on its side and said so over signaling. */
    CONNECT_ERROR,
    /** ICE finished its checks without a usable candidate pair. */
    PEER_FAILED,
    /** The peer connection closed before the channel became active. */
    PEER_CLOSED
}
