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

package org.cloudburstmc.netty.util.nethernet;

import org.jspecify.annotations.Nullable;
import tel.schich.libdatachannel.IceUdpMuxListener;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PROXY protocol v2 relays read on a shared ICE UDP port until closed, so their datagrams count as
 * coming from the client each header declares. The setting belongs to the port, which reads the
 * relays of every open handle, so built-in signaling and an NXS listener on one port each keep
 * theirs until they close.
 */
public final class IceProxyProtocol implements AutoCloseable {
    private static final Map<Port, List<IceProxyProtocol>> OPEN = new HashMap<>();

    /** A wildcard and null are the same port, as the binding binds both as any. */
    private record Port(@Nullable InetAddress bindAddress, int port) {
        static Port of(@Nullable InetAddress bindAddress, int port) {
            return new Port(bindAddress == null || bindAddress.isAnyLocalAddress() ? null : bindAddress, port);
        }
    }

    private final Port port;
    private final IpRangeSet relays;

    private IceProxyProtocol(Port port, IpRangeSet relays) {
        this.port = port;
        this.relays = relays;
    }

    /**
     * @param bindAddress The address the port's peers bind, null for peers that leave it unset
     * @param port        The fixed ICE port
     * @param relays      The relays to read headers from
     * @return A handle that removes the relays, or null when there are none
     */
    public static @Nullable IceProxyProtocol set(@Nullable InetAddress bindAddress, int port, IpRangeSet relays) {
        if (relays.isEmpty()) {
            return null;
        }
        IceProxyProtocol handle = new IceProxyProtocol(Port.of(bindAddress, port), relays);
        synchronized (OPEN) {
            List<IceProxyProtocol> handles = OPEN.computeIfAbsent(handle.port, key -> new ArrayList<>());
            handles.add(handle);
            try {
                apply(handle.port, handles);
            } catch (RuntimeException e) {
                remove(handle);
                throw e;
            }
        }
        return handle;
    }

    /**
     * The relay a client comes through on this port, by the address its header declared.
     *
     * @return The relay, or null when none speaks for the client
     */
    public @Nullable InetSocketAddress relayOf(InetSocketAddress client) {
        return IceUdpMuxListener.proxyRelay(this.port.bindAddress(), this.port.port(), client);
    }

    @Override
    public void close() {
        synchronized (OPEN) {
            List<IceProxyProtocol> handles = remove(this);
            if (handles != null) {
                apply(this.port, handles);
            }
        }
    }

    /** @return The handles left on the port, or null if this one was already removed */
    private static @Nullable List<IceProxyProtocol> remove(IceProxyProtocol handle) {
        List<IceProxyProtocol> handles = OPEN.get(handle.port);
        if (handles == null || !handles.remove(handle)) {
            return null;
        }
        if (handles.isEmpty()) {
            OPEN.remove(handle.port);
        }
        return handles;
    }

    private static void apply(Port port, List<IceProxyProtocol> handles) {
        IpRangeSet relays = IpRangeSet.empty();
        for (IceProxyProtocol handle : handles) {
            relays = relays.union(handle.relays);
        }
        IceUdpMuxListener.setProxyProtocol(port.bindAddress(), port.port(), relays.entries());
    }
}
