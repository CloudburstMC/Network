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

package org.cloudburstmc.netty.channel;

import io.netty.channel.Channel;

/**
 * A channel of one of the transports, carrying each message written to it whole.
 */
public interface TransportChannel extends Channel {

    /**
     * Largest message a write may carry. Larger writes fail, as the peer would drop them.
     */
    int maxMessageSize();
}
