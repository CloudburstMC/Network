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

import java.net.InetSocketAddress;

public class RakPong {

    private final long pingTime;
    private final long pongTime;
    private final long guid;
    private final byte[] pongData;
    private final InetSocketAddress sender;

    public RakPong(long pingTime, long pongTime, long guid, byte[] pongData, InetSocketAddress sender) {
        this.pingTime = pingTime;
        this.pongTime = pongTime;
        this.guid = guid;
        this.pongData = pongData;
        this.sender = sender;
    }

    public long getPingTime() {
        return this.pingTime;
    }

    public long getPongTime() {
        return this.pongTime;
    }

    public long getGuid() {
        return this.guid;
    }

    public byte[] getPongData() {
        return this.pongData;
    }

    public InetSocketAddress getSender() {
        return this.sender;
    }
}
