/*
 * Copyright 2022 CloudburstMC
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

package org.cloudburstmc.netty.channel.raknet;

import io.netty.buffer.ByteBuf;

import java.net.InetSocketAddress;

public class RakPing {

    private final long pingTime;
    private final InetSocketAddress sender;
    private final InetSocketAddress recipient;

    public RakPing(long pingTime, InetSocketAddress sender) {
        this(pingTime, sender, null);
    }

    public RakPing(long pingTime, InetSocketAddress sender, InetSocketAddress recipient) {
        this.pingTime = pingTime;
        this.sender = sender;
        this.recipient = recipient;
    }

    public long getPingTime() {
        return this.pingTime;
    }


    public InetSocketAddress getSender() {
        return this.sender;
    }

    public InetSocketAddress getRecipient() {
        return this.recipient;
    }

    public RakPong reply(long guid, ByteBuf pongData) {
        return new RakPong(this.pingTime, guid, pongData, this.sender, this.recipient);
    }
}
