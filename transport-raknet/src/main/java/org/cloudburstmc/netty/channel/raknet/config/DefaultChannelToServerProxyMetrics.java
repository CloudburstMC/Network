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

package org.cloudburstmc.netty.channel.raknet.config;

import org.cloudburstmc.netty.channel.raknet.RakChildChannel;
import org.cloudburstmc.netty.channel.raknet.RakServerChannel;
import org.cloudburstmc.netty.channel.raknet.RakState;

public class DefaultChannelToServerProxyMetrics implements RakChannelMetrics {
    
    public RakServerChannel parent;
    public RakChildChannel channel;

    public DefaultChannelToServerProxyMetrics(RakServerChannel parent, RakChildChannel channel) {
        this.parent = parent;
        this.channel = channel;
    }

    public void bytesIn(int count) {
        this.parent.config().getMetrics().bytesIn(channel, count);
    }

    public void bytesOut(int count) {
        this.parent.config().getMetrics().bytesOut(channel, count);
    }

    public void rakDatagramsIn(int count) {
        this.parent.config().getMetrics().rakDatagramsIn(channel, count);
    }

    public void rakDatagramsOut(int count) {
        this.parent.config().getMetrics().rakDatagramsOut(channel, count);
    }

    public void encapsulatedIn(int count) {
        this.parent.config().getMetrics().encapsulatedIn(channel, count);
    }

    public void encapsulatedOut(int count) {
        this.parent.config().getMetrics().encapsulatedOut(channel, count);
    }

    public void rakStaleDatagrams(int count) {
        this.parent.config().getMetrics().rakStaleDatagrams(channel, count);
    }

    public void ackIn(int count) {
        this.parent.config().getMetrics().ackIn(channel, count);
    }

    public void ackOut(int count) {
        this.parent.config().getMetrics().ackOut(channel, count);
    }

    public void nackOut(int count) {
        this.parent.config().getMetrics().nackOut(channel, count);
    }

    public void nackIn(int count) {
        this.parent.config().getMetrics().nackIn(channel, count);
    }

    public void stateChange(RakState state) {
        this.parent.config().getMetrics().stateChange(channel, state);
    }

    public void queuedPacketBytes(int count) {
        this.parent.config().getMetrics().queuedPacketBytes(channel, count);
    }
}
