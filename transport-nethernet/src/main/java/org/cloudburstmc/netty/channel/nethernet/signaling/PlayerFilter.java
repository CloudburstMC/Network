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

import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.jspecify.annotations.Nullable;

/**
 * Functional interface for filtering players before a connection is created for them.
 */
@FunctionalInterface
public interface PlayerFilter {
    /**
     * Called once the identity attached to an SDP offer has been validated, before
     * the connection is handed to the {@link NetherNetServerSignaling.NewConnectionHandler}.
     * <p>
     * Called on the event loop, so don't block in here. A thrown exception turns the player
     * away as {@link JoinRefusal#REJECTED} does.
     *
     * @param host   The host header from the join request, which may be used to identify the server
     * @param player The validated player attempting to join
     * @return Why to turn the player away, or null to let them in
     */
    @Nullable JoinRefusal refuse(String host, PlayerInfo player);
}
