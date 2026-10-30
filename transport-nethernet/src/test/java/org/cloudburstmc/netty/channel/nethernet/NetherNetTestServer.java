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
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
import org.cloudburstmc.netty.util.nethernet.IpRangeSet;
import tel.schich.libdatachannel.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** A real Netty acceptor and native peer pair, with in-process SDP signaling. */
final class NetherNetTestServer implements AutoCloseable {
    static final PeerConnectionConfiguration CONFIG =
            PeerConnectionConfiguration.DEFAULT.withDisableAutoNegotiation(true);
    final Signaling signaling = new Signaling();
    final NetherNetServerChannel server = new NetherNetServerChannel(signaling);
    private final DefaultEventLoopGroup group = new DefaultEventLoopGroup(1);
    private final CompletableFuture<Accepted> accepted = new CompletableFuture<>();
    private final CompletableFuture<NetherNetChildChannel> active = new CompletableFuture<>();
    /** User events and errors that reached the end of the child's pipeline. */
    final BlockingQueue<Object> childEvents = new LinkedBlockingQueue<>();
    private final InetSocketAddress iceAddress;
    /** PROXY protocol relays in front of the ICE port. */
    IpRangeSet relays = IpRangeSet.empty();
    /** The server's peer configuration, or null for the default. */
    PeerConnectionConfiguration peerConfig;
    /** See {@link NetherChannelOption#NETHER_SERVER_PREFER_PROXIED_ADDRESS}. */
    boolean preferProxied;

    NetherNetTestServer() {
        this(null);
    }

    /** @param iceAddress A fixed ICE address every peer shares, or null for an ephemeral port each. */
    NetherNetTestServer(InetSocketAddress iceAddress) {
        this.iceAddress = iceAddress;
    }

    record Accepted(NetherNetChildChannel child, PeerConnection peer) { }

    void bind() throws InterruptedException {
        new ServerBootstrap().group(group).channelFactory(() -> server)
                .option(NetherChannelOption.NETHER_SERVER_ICE_ADDRESS, iceAddress)
                .option(NetherChannelOption.NETHER_SERVER_PROXY_PROTOCOL, relays)
                .option(NetherChannelOption.NETHER_PEER_CONNECTION_CONFIG, peerConfig)
                .option(NetherChannelOption.NETHER_SERVER_PREFER_PROXIED_ADDRESS, preferProxied)
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

                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                        if (event instanceof NetherNetPacket.Delivery) {
                            // Every read has one, so it goes on to the handlers a test adds
                            ctx.fireUserEventTriggered(event);
                        } else {
                            childEvents.add(event);
                        }
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        childEvents.add(cause);
                    }
                }).bind("127.0.0.1", 0).sync();
    }

    Accepted accept(String offer) throws Exception {
        server.eventLoop().submit(() -> server.acceptConnection("1", offer, "client")).sync();
        return accepted();
    }

    /** The first child the server created. */
    Accepted accepted() throws Exception {
        return accepted.get(5, TimeUnit.SECONDS);
    }

    /** Hands a join signaled from {@code address} to the server, without waiting for a child. */
    void offer(String connectionId, String offer, InetSocketAddress address) throws Exception {
        offer(connectionId, offer, address == null ? null : new SourcedAddress(address, AddressSource.SIGNALING, null));
    }

    /** Hands a join signaled as {@code signaled} says to the server, without waiting for a child. */
    void offer(String connectionId, String offer, SourcedAddress signaled) throws Exception {
        server.eventLoop().submit(() -> server.acceptConnection(connectionId, offer, "client", signaled, null)).sync();
    }

    /** Whether the server created a child for any join so far. */
    boolean anyAccepted() {
        return accepted.isDone();
    }

    /**
     * Accepts from off the loop, as signaling does, and runs {@code beforeRegistration} while the
     * loop is held, so the child cannot have registered yet.
     */
    Accepted acceptOffLoop(String offer, Runnable beforeRegistration) throws Exception {
        var release = new CountDownLatch(1);
        server.eventLoop().execute(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            server.acceptConnection("1", offer, "client");
            beforeRegistration.run();
        } finally {
            release.countDown();
        }
        return accepted();
    }

    NetherNetChildChannel connect(PeerConnection client) throws Exception {
        accept(offer(client));
        client.setRemoteDescription(signaling.answer.get(5, TimeUnit.SECONDS), SessionDescriptionType.ANSWER);
        return active();
    }

    /** Binds, then connects {@code client} with the two data channels a Bedrock client opens. */
    NetherNetChildChannel bindAndConnect(PeerConnection client) throws Exception {
        bind();
        client.createDataChannel(NetherNetConstants.RELIABLE_CHANNEL_LABEL);
        client.createDataChannel(NetherNetConstants.UNRELIABLE_CHANNEL_LABEL);
        return connect(client);
    }

    /** The one event the child's pipeline saw by the time it closed. */
    Object eventOnceClosed(NetherNetChildChannel child) throws Exception {
        if (!child.closeFuture().await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("the channel never closed");
        }
        Object event = childEvents.poll(1, TimeUnit.SECONDS);
        if (!childEvents.isEmpty()) {
            throw new AssertionError("more than one event: " + event + ", " + childEvents);
        }
        return event;
    }

    /** Shuts the loop down under the server and its child, leaving both open. */
    void shutDownLoop() throws InterruptedException {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }

    NetherNetChildChannel active() throws Exception {
        return active.get(5, TimeUnit.SECONDS);
    }

    /** The SDP with its candidate lines removed, as a client that trickles them sends it. */
    static String withoutCandidates(String sdp) {
        return sdp.lines().filter(line -> !line.startsWith("a=candidate:") && !line.equals("a=end-of-candidates"))
                .collect(Collectors.joining("\r\n", "", "\r\n"));
    }

    /** The SDP's candidates in the form a {@code CANDIDATEADD} carries them. */
    static List<String> candidates(String sdp) {
        return sdp.lines().filter(line -> line.startsWith("a=candidate:")).map(line -> line.substring(2)).toList();
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
        /** What the server trickles, one {@code CONNECTRESPONSE} or {@code CANDIDATEADD} each. */
        final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
        boolean failSetup, failRemoval, trickle, refuseRelays;
        volatile boolean mediaRelayed;
        /** Whether the media was relayed when signaling bound, null before. */
        volatile Boolean relayedAtBind;
        volatile SignalHandler handler;

        public void setSignalHandler(String id, SignalHandler handler) {
            if (failSetup) throw new IllegalStateException("signaling setup failed");
            this.handler = handler;
        }
        public void removeSignalHandler(String id) {
            removed.complete(null);
            if (failRemoval) throw new IllegalStateException("signaling cleanup failed");
        }
        public void sendDescription(String remoteNetworkId, String sdp) { answer.complete(sdp); }
        public void sendSignal(String targetNetworkId, String data) { sent.add(data); }
        public boolean usesTrickleIce() { return trickle; }
        public void bind(SocketAddress address, InetSocketAddress iceAddress, EventLoop loop) { relayedAtBind = mediaRelayed; }
        public void setMediaRelayed(boolean relayed) {
            if (refuseRelays) NetherNetServerSignaling.super.setMediaRelayed(relayed);
            mediaRelayed = relayed;
        }
        public void setNewConnectionHandler(NewConnectionHandler handler) { }
        public void setAdvertisementData(PongData data) { }
        public String getLocalNetworkId() { return "server"; }
        public boolean isActive() { return true; }
        public void close() { }
    }
}
