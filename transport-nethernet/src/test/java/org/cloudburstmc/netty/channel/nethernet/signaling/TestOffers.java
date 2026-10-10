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

import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;

/** Builds offers that carry a self signed identity, for tests that are not about the trust anchor. */
final class TestOffers {

    private static final String SDP = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n"
            + "a=fingerprint:sha-256 AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
            + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99\r\n"
            + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";

    private TestOffers() {
    }

    static String selfSigned() throws Exception {
        return OperatorIdentity.generate("example.test").forPlayer("2535000000000000", "Probe").withAssertion(SDP);
    }
}
