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

package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.AddressSource;
import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.SourcedAddress;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling.SignalHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class NetherNetDiscovery extends SimpleChannelInboundHandler<DatagramPacket> {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetDiscovery.class);
    /**
     * The longest a signal is declared, by clients and BDS alike. A longer one, such as an offer listing many
     * candidates, runs on to the end of the packet.
     */
    static final int MAX_DECLARED_SIGNAL_LENGTH = 1140;
    /** How long a peer's address is kept after its last packet. Clients ask every 2 seconds. */
    private static final long PEER_TTL_SECONDS = 30;
    /** Far more peers than a network holds, so packets anyone can craft cannot grow the table without end. */
    private static final int MAX_PEERS = 1024;

    private final long networkId;
    private final Map<String, SignalHandler> signalHandlers = new ConcurrentHashMap<>();
    private final Map<Long, InetSocketAddress> peerAddresses = ExpiringMap.builder()
            .maxSize(MAX_PEERS)
            .expiration(PEER_TTL_SECONDS, TimeUnit.SECONDS)
            .expirationPolicy(ExpirationPolicy.ACCESSED)
            .build();
    private Channel channel;
    private EventLoopGroup group;
    private volatile MotdProvider motdProvider = (host, remoteAddress, client) -> null;
    private NetherNetServerSignaling.NewConnectionHandler newConnectionHandler;
    private BiConsumer<Long, ByteBuf> discoveryCallback;

    /**
     * Creates a NetherNetDiscovery instance with the specified Network ID.
     *
     * @param networkId The Network ID to use for discovery.
     */
    public NetherNetDiscovery(long networkId) {
        this.networkId = networkId;
    }

    public void bind() {
        bind(NetherNetConstants.DISCOVERY_PORT);
    }

    public void bind(int port) {
        bind(new InetSocketAddress(port));
    }

    /**
     * Opens the discovery socket on a loop of its own, which {@link #close} shuts down.
     */
    public void bind(InetSocketAddress address) {
        EventLoopGroup group = this.group = new NioEventLoopGroup(1);
        try {
            this.channel = new Bootstrap().group(group)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .handler(this)
                    .bind(address).sync().channel();
        } catch (InterruptedException e) {
            group.shutdownGracefully();
            Thread.currentThread().interrupt();
            return;
        } catch (Throwable t) {
            // sync() rethrows a failed bind, such as a port in use
            group.shutdownGracefully();
            throw t;
        }
        log.info("NetherNet Discovery listening on {}", address);
    }

    public void sendDiscoveryRequest(InetSocketAddress target, BiConsumer<Long, ByteBuf> onServerFound) {
        this.discoveryCallback = onServerFound;

        ByteBuf buf = Unpooled.buffer();
        buf.writeShortLE(NetherNetConstants.ID_DISCOVERY_REQUEST);
        buf.writeLongLE(this.networkId);
        buf.writeZero(8); // Padding

        sendPacket(buf, target);
    }

    /** Answers every discovery request with {@code data}. */
    public void setPongData(PongData data) {
        setMotdProvider((host, remoteAddress, client) -> data);
    }

    /**
     * Asks {@code provider} for the pong on every discovery request, so it follows the player count.
     * It runs on the discovery loop with no host or client info, and a null answer leaves the request
     * unanswered.
     */
    public void setMotdProvider(MotdProvider provider) {
        this.motdProvider = Objects.requireNonNull(provider);
    }

    public void registerSignalHandler(String connectionId, SignalHandler handler) {
        this.signalHandlers.put(connectionId, handler);
    }

    public void unregisterSignalHandler(String connectionId) {
        this.signalHandlers.remove(connectionId);
    }

    public void setNewConnectionHandler(NetherNetServerSignaling.NewConnectionHandler handler) {
        this.newConnectionHandler = handler;
    }

    /**
     * Sends a signal immediately and schedules it to be resent periodically
     * until the returned ScheduledFuture is cancelled.
     */
    public ScheduledFuture<?> sendSignalRetrying(InetSocketAddress recipient, long targetNetworkId, String data,
                                                 long delayMs) {
        return channel.eventLoop().scheduleAtFixedRate(() -> {
            log.debug("Resending signal to {}: {}", recipient, data);
            sendSignal(recipient, targetNetworkId, data);
        }, 0, delayMs, TimeUnit.MILLISECONDS);
    }

    public void sendSignal(InetSocketAddress recipient, long targetNetworkId, String data) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShortLE(NetherNetConstants.ID_DISCOVERY_MESSAGE);
        buf.writeLongLE(this.networkId); // Sender ID
        buf.writeZero(8); // Padding

        buf.writeLongLE(targetNetworkId); // Recipient ID
        byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
        // Capped as clients and BDS declare it, with the whole signal following anyway
        buf.writeIntLE(Math.min(dataBytes.length, MAX_DECLARED_SIGNAL_LENGTH));
        buf.writeBytes(dataBytes);

        sendPacket(buf, recipient);
    }

    public void sendSignal(long targetNetworkId, String data) {
        InetSocketAddress recipient = peerAddresses.get(targetNetworkId);
        if (recipient != null) {
            sendSignal(recipient, targetNetworkId, data);
        } else {
            throw new IllegalArgumentException("Attempted to send signal to unknown peer: " + targetNetworkId);
        }
    }

    private void sendPacket(ByteBuf packetData, InetSocketAddress target) {
        try {
            byte[] encrypted = NetherNetConstants.encryptDiscoveryPacket(packetData);
            channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(encrypted), target));
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt discovery packet", e);
        } finally {
            packetData.release();
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf content = packet.content();
        ByteBuf decrypted = null;
        try {
            decrypted = NetherNetConstants.decryptDiscoveryPacket(content);
        } catch (Exception e) {
            log.debug("Failed to decrypt discovery packet from {}", packet.sender(), e);
            return;
        }

        if (decrypted == null) {
            log.debug("Received invalid discovery packet from {}", packet.sender());
            return;
        }

        try {
            int packetId = decrypted.readUnsignedShortLE();
            long senderId = decrypted.readLongLE();

            decrypted.skipBytes(8); // Padding

            if (senderId == this.networkId) {
                log.debug("Ignoring own discovery packet");
                return;
            }

            peerAddresses.put(senderId, packet.sender());

            switch (packetId) {
                case NetherNetConstants.ID_DISCOVERY_REQUEST -> {
                    log.trace("Handled discovery request from {}", packet.sender());
                    handleRequest(packet.sender());
                }
                case NetherNetConstants.ID_DISCOVERY_MESSAGE -> {
                    log.trace("Handled discovery message from {}", packet.sender());
                    log.trace("Message Data: {}", decrypted.toString(StandardCharsets.UTF_8));
                    handleMessage(decrypted, senderId, packet.sender());
                }
                case NetherNetConstants.ID_DISCOVERY_RESPONSE -> {
                    log.trace("Handled discovery response from {}", packet.sender());
                    if (discoveryCallback != null) {
                        log.trace("Response Data: {}", decrypted.toString(StandardCharsets.UTF_8));
                        // We retain it because we are passing it out of the pipeline handler
                        discoveryCallback.accept(senderId, decrypted.retain());
                    }
                }
                default -> {
                    log.debug("Received unknown discovery packet ID {} from {}", packetId, packet.sender());
                }
            }
        } catch (Exception e) {
            log.debug("Error processing discovery packet from {}", packet.sender(), e);
        } finally {
            decrypted.release();
        }
    }

    private void handleRequest(InetSocketAddress sender) {
        PongData pong = this.motdProvider.getMotd(null, sender, null);
        if (pong == null) {
            return;
        }

        for (int version : ServerData.VERSIONS) {
            ByteBuf buf = Unpooled.buffer();
            buf.writeShortLE(NetherNetConstants.ID_DISCOVERY_RESPONSE);
            buf.writeLongLE(this.networkId);
            buf.writeZero(8);
            buf.writeBytes(ServerData.encode(pong, version));
            sendPacket(buf, sender);
        }
    }

    private void handleMessage(ByteBuf data, long senderId, InetSocketAddress sender) {
        long recipientId = data.readLongLE();

        if (recipientId != this.networkId && recipientId != 0) {
            log.trace("Ignoring message intended for {}, but I am {}", recipientId, this.networkId);
            return;
        }

        int len = data.readIntLE();
        if (data.readableBytes() < len) {
            log.trace("Malformed message: claimed length {} but only has {}", len, data.readableBytes());
            return;
        }

        int length = len < MAX_DECLARED_SIGNAL_LENGTH ? len : data.readableBytes();
        String messageData = data.readCharSequence(length, StandardCharsets.UTF_8).toString();
        if ("Ping".equals(messageData)) {
            return;
        }

        NetherNetConstants.Signal signal = NetherNetConstants.parseSignal(messageData);
        if (signal == null) {
            log.debug("Malformed signal in message: {}", messageData);
            return;
        }
        String connectionId = signal.connectionId();

        SignalHandler handler = signalHandlers.get(connectionId);

        if (handler != null) {
            handler.onSignal(messageData);
        } else if (NetherNetConstants.RTC_NEGOTIATION_CONNECT_REQUEST.equals(signal.type())) {
            if (newConnectionHandler != null) {
                log.trace("Dispatching New Connection: ID={} Sender={}", connectionId,
                        Long.toUnsignedString(senderId));
                newConnectionHandler.onConnect(connectionId, Long.toUnsignedString(senderId), signal.payload(),
                        new SourcedAddress(sender, AddressSource.SIGNALING_LAN, null), null);
            } else {
                log.debug("Received CONNECT_REQUEST but no NewConnectionHandler is set!");
            }
        } else {
            log.debug("Unhandled signal type: {}", signal.type());
        }
    }

    public void close() {
        if (channel != null) {
            channel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    EventLoopGroup group() {
        return this.group;
    }

    /** The loop discovery reads on, or null before it is bound. */
    EventLoop eventLoop() {
        Channel channel = this.channel;
        return channel == null ? null : channel.eventLoop();
    }

    /** The handler a connection's signals go to, or null without one. */
    SignalHandler signalHandler(String connectionId) {
        return this.signalHandlers.get(connectionId);
    }

    public boolean isActive() {
        return channel != null && channel.isActive();
    }

    /** Where the socket is bound, or null before it is. */
    InetSocketAddress localAddress() {
        return channel == null ? null : (InetSocketAddress) channel.localAddress();
    }
}
