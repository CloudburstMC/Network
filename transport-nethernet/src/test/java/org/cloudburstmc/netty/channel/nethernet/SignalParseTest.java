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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SignalParseTest {

    @Test
    void splitsTypeIdAndPayload() {
        NetherNetConstants.Signal signal = NetherNetConstants.parseSignal("CONNECTREQUEST 12345 v=0\r\na=b c");
        assertEquals("CONNECTREQUEST", signal.type());
        assertEquals("12345", signal.connectionId());
        assertEquals("v=0\r\na=b c", signal.payload());
    }

    @Test
    void keepsTheIdAsThePeerWroteIt() {
        assertEquals("18446744073709551615",
                NetherNetConstants.parseSignal("CONNECTRESPONSE 18446744073709551615").connectionId());
        assertEquals("Zm9v-bar_baz.1", NetherNetConstants.parseSignal("CANDIDATEADD Zm9v-bar_baz.1").connectionId());
        assertEquals("", NetherNetConstants.parseSignal("CONNECTERROR 7").payload());
    }

    @Test
    void refusesSignalsWithoutAnId() {
        assertNull(NetherNetConstants.parseSignal(""));
        assertNull(NetherNetConstants.parseSignal("CONNECTREQUEST"));
        assertNull(NetherNetConstants.parseSignal("CONNECTREQUEST  v=0"));
    }

    @Test
    void refusesIdsThatCouldNotBeLoggedAsIs() {
        assertNull(NetherNetConstants.parseSignal("CONNECTREQUEST 12\n34 v=0"));
        assertNull(NetherNetConstants.parseSignal("CONNECTREQUEST 12\u001b[31m34 v=0"));
        assertNull(NetherNetConstants.parseSignal("CONNECTREQUEST 12\t34 v=0"));
        assertNull(NetherNetConstants.parseSignal("CONNECTREQUEST é v=0"));
    }
}
