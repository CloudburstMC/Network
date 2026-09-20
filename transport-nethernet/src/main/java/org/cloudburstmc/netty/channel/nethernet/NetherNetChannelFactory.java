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

import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetClientSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;

import java.util.function.Supplier;

public class NetherNetChannelFactory<T extends Channel> implements ChannelFactory<T> {

    private final Supplier<T> channelCreator;

    private NetherNetChannelFactory(Supplier<T> channelCreator) {
        this.channelCreator = channelCreator;
    }

    @Override
    public T newChannel() {
        return channelCreator.get();
    }

    /**
     * Creates a NetherNet Server Channel Factory. The channel works on any event loop: media is
     * native, and a signaling that listens on a socket brings a loop of its own for it.
     *
     * @param signaling The NetherNetServerSignaling instance for signaling.
     * @return A ChannelFactory for NetherNetServerChannel.
     */
    public static ChannelFactory<NetherNetServerChannel> server(NetherNetServerSignaling signaling) {
        return new NetherNetChannelFactory<>(() -> new NetherNetServerChannel(signaling));
    }

    /**
     * Creates a NetherNet Client Channel Factory around one signaling shared by every channel it
     * makes, which suits signaling that carries many connections at once, such as Xbox Live.
     * Signaling that serves one connection, such as {@code NetherNetHTTPClientSignaling}, goes
     * through {@link #client(Supplier)} instead.
     *
     * @param signaling The NetherNetClientSignaling instance for signaling.
     * @return A ChannelFactory for NetherNetClientChannel.
     */
    public static ChannelFactory<NetherNetClientChannel> client(NetherNetClientSignaling signaling) {
        return new NetherNetChannelFactory<>(() -> new NetherNetClientChannel(signaling));
    }

    /**
     * Creates a NetherNet Client Channel Factory that gives every channel a signaling of its own,
     * so a {@code Bootstrap} can be reused for connection after connection.
     *
     * @param signaling Makes the signaling for one channel.
     * @return A ChannelFactory for NetherNetClientChannel.
     */
    public static ChannelFactory<NetherNetClientChannel> client(
            Supplier<? extends NetherNetClientSignaling> signaling) {
        return new NetherNetChannelFactory<>(() -> new NetherNetClientChannel(signaling.get()));
    }
}
