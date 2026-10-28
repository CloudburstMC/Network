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

package org.cloudburstmc.netty.signaling.admission;

import io.netty.channel.Channel;
import org.cloudburstmc.netty.channel.nethernet.AddressSource;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.netty.channel.nethernet.SourcedAddress;
import org.cloudburstmc.netty.util.nethernet.IceProxyProtocol;
import tel.schich.libdatachannel.PeerConnection;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * A child admitted through NXS. Its native peer is torn down synchronously, and {@link #nativeTermination()}
 * completes once it is gone, which is when its admission may be released.
 */
public final class AdmittedNetherNetChildChannel extends NetherNetChildChannel {
    private final CompletableFuture<Void> nativeTermination = new CompletableFuture<>();
    private final Consumer<PeerConnection> nativeCloser;

    /**
     * @param admittedFrom The source of the first STUN check the admission accepted
     * @param relays       The PROXY protocol relays on the listener's port, or null when none are set
     */
    public AdmittedNetherNetChildChannel(Channel parent, PeerConnection peer, InetSocketAddress admittedFrom,
                                         InetSocketAddress local, IceProxyProtocol relays) {
        this(parent, peer, admittedFrom, local, relays, AdmittedNetherNetChildChannel::closeNativePeer);
    }

    AdmittedNetherNetChildChannel(Channel parent, PeerConnection peer, InetSocketAddress admittedFrom,
                                  InetSocketAddress local, IceProxyProtocol relays,
                                  Consumer<PeerConnection> nativeCloser) {
        super(parent, peer, new SourcedAddress(admittedFrom, AddressSource.NXS_ADMISSION, null), local);
        this.iceRelays = relays;
        this.nativeCloser = nativeCloser;
    }

    @Override
    protected void closePeer(PeerConnection peer) {
        try {
            nativeCloser.accept(peer);
        } catch (RuntimeException | Error failure) {
            nativeTermination.completeExceptionally(failure);
            throw failure;
        }
        nativeTermination.complete(null);
    }

    private static void closeNativePeer(PeerConnection peer) {
        if (peer != null && !peer.closeAndAwait(Duration.ofSeconds(5))) {
            peer.close();
            throw new IllegalStateException("Native transport teardown did not complete within its deadline");
        }
    }

    public CompletionStage<Void> nativeTermination() {
        return nativeTermination;
    }

    void closeUnregistered() throws Exception {
        doClose();
    }
}
