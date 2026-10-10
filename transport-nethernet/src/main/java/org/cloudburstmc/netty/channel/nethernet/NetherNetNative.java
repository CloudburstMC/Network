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

package org.cloudburstmc.netty.channel.nethernet;

import tel.schich.libdatachannel.LibDataChannel;
import tel.schich.libdatachannel.SctpSettings;

/** Loads libdatachannel with the SCTP settings NetherNet needs, before the first peer exists. */
final class NetherNetNative {

    /**
     * Bedrock clients hold a SACK up to 200 ms, which is also libdatachannel's minimum RTO, so in-flight data was
     * retransmitted just as its SACK was due and the congestion window collapsed. 400 ms is what the client and
     * BDS use themselves.
     */
    static final SctpSettings SCTP_SETTINGS = SctpSettings.builder()
            .minRetransmitTimeoutMs(400)
            .build();

    private static boolean initialized;

    private NetherNetNative() {
    }

    /**
     * @throws LinkageError if the native library is not available
     */
    static synchronized void initialize() {
        if (initialized) {
            return;
        }
        LibDataChannel.setSctpSettings(SCTP_SETTINGS);
        LibDataChannel.initialize();
        initialized = true;
    }
}
