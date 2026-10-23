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
import org.cloudburstmc.netty.channel.nethernet.NetherNetChildChannel;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;
import tel.schich.libdatachannel.DataChannel;
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

    public AdmittedNetherNetChildChannel(Channel parent, PeerConnection peer, InetSocketAddress remote,
                                         InetSocketAddress local) {
        this(parent, peer, remote, local, AdmittedNetherNetChildChannel::closeNativePeer);
    }

    AdmittedNetherNetChildChannel(Channel parent, PeerConnection peer, InetSocketAddress remote,
                                  InetSocketAddress local, Consumer<PeerConnection> nativeCloser) {
        super(parent, peer, remote, local);
        this.nativeCloser = nativeCloser;
    }

    @Override
    protected void activate(DataChannel reliable, DataChannel unreliable) {
        // The selected pair is where the player really is, the admission only saw its first check
        PeerConnection peer = peerConnection;
        if (peer != null) {
            try {
                InetSocketAddress selected = peer.remoteAddress();
                setRemoteAddress(new InetSocketAddress(EndpointAddress.parse(selected.getHostString()),
                        selected.getPort()));
            } catch (Exception unavailable) {
                // Keeps the address it was admitted from
            }
        }
        super.activate(reliable, unreliable);
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
