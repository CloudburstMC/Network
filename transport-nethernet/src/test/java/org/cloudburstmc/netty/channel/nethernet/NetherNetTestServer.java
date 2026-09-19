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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import tel.schich.libdatachannel.*;

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** A real Netty acceptor and native peer pair, with in-process SDP signaling. */
final class NetherNetTestServer implements AutoCloseable {
    static final PeerConnectionConfiguration CONFIG =
            PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true);
    final Signaling signaling = new Signaling();
    final NetherNetServerChannel server = new NetherNetServerChannel(signaling);
    private final DefaultEventLoopGroup group = new DefaultEventLoopGroup(1);
    private final CompletableFuture<Accepted> accepted = new CompletableFuture<>();
    private final CompletableFuture<NetherNetChildChannel> active = new CompletableFuture<>();

    record Accepted(NetherNetChildChannel child, PeerConnection peer) { }

    void bind() throws InterruptedException {
        new ServerBootstrap().group(group).channelFactory(() -> server)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object message) {
                        NetherNetChildChannel child = (NetherNetChildChannel) message;
                        accepted.complete(new Accepted(child, child.peerConnection));
                        ctx.fireChannelRead(message);
                    }
                })
                .childHandler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelActive(ChannelHandlerContext ctx) {
                        active.complete((NetherNetChildChannel) ctx.channel());
                        ctx.fireChannelActive();
                    }
                }).bind("127.0.0.1", 0).sync();
    }

    Accepted accept(String offer) throws Exception {
        server.eventLoop().submit(() -> server.acceptConnection(1, offer, "client")).sync();
        return accepted.get(5, TimeUnit.SECONDS);
    }

    NetherNetChildChannel connect(PeerConnection client) throws Exception {
        accept(offer(client));
        client.setRemoteDescription(signaling.answer.get(5, TimeUnit.SECONDS), SessionDescriptionType.ANSWER);
        return active.get(5, TimeUnit.SECONDS);
    }

    static String offer(PeerConnection client) throws Exception {
        var gathered = new CompletableFuture<String>();
        client.onGatheringStateChange.register((peer, state) -> {
            if (state == GatheringState.RTC_GATHERING_COMPLETE) gathered.complete(peer.localDescription());
        });
        client.setLocalDescription("offer");
        return gathered.get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws Exception {
        if (accepted.isDone()) accepted.join().child.close().sync();
        server.close().sync();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }

    static final class Signaling implements NetherNetServerSignaling {
        final CompletableFuture<String> answer = new CompletableFuture<>();
        final CompletableFuture<Void> removed = new CompletableFuture<>();
        boolean failSetup, failRemoval;

        public void setSignalHandler(long id, SignalHandler handler) {
            if (failSetup) throw new IllegalStateException("signaling setup failed");
        }
        public void removeSignalHandler(long id) {
            removed.complete(null);
            if (failRemoval) throw new IllegalStateException("signaling cleanup failed");
        }
        public void sendFullSdp(String remoteNetworkId, String sdp) { answer.complete(sdp); }
        public boolean usesTrickleIce() { return false; }
        public void bind(SocketAddress address, EventLoop loop) { }
        public void setNewConnectionHandler(NewConnectionHandler handler) { }
        public void setAdvertisementData(PongData data) { }
        public String getLocalNetworkId() { return "server"; }
        public boolean isActive() { return true; }
        public void close() { }
    }
}
