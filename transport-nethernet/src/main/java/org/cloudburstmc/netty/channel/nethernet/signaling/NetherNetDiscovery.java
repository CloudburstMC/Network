package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetServerSignaling.PongData;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling.SignalHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public class NetherNetDiscovery extends SimpleChannelInboundHandler<DatagramPacket> {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetDiscovery.class);

    private final long networkId;
    private final Map<Long, SignalHandler> signalHandlers = new ConcurrentHashMap<>();
    private final DiscoveryPeerRegistry peerAddresses;
    private final Object lifecycleLock = new Object();
    private final Supplier<? extends EventLoopGroup> eventLoopFactory;
    private final AtomicReference<DiscoveryCallback> discoveryCallback = new AtomicReference<>();
    private EventLoopGroup eventLoops;
    private volatile Channel channel;
    private volatile byte[] pongData;
    private volatile NetherNetServerSignaling.NewConnectionHandler newConnectionHandler;
    private volatile boolean closed;

    /**
     * Creates a NetherNetDiscovery instance with the specified Network ID.
     *
     * @param networkId The Network ID to use for discovery.
     */
    public NetherNetDiscovery(long networkId) {
        this(networkId, () -> new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory()));
    }

    NetherNetDiscovery(long networkId, Supplier<? extends EventLoopGroup> eventLoopFactory) {
        this(networkId, eventLoopFactory, new DiscoveryPeerRegistry());
    }

    NetherNetDiscovery(long networkId, Supplier<? extends EventLoopGroup> eventLoopFactory, DiscoveryPeerRegistry peerAddresses) {
        this.networkId = networkId;
        this.eventLoopFactory = Objects.requireNonNull(eventLoopFactory, "eventLoopFactory");
        this.peerAddresses = Objects.requireNonNull(peerAddresses, "peerAddresses");
    }

    public void bind() {
        bind(NetherNetConstants.DISCOVERY_PORT);
    }

    public void bind(int port) {
        bind(new InetSocketAddress(port));
    }

    public void bind(InetSocketAddress address) {
        Objects.requireNonNull(address, "address");
        EventLoopGroup group;
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("Discovery is closed");
            }
            if (eventLoops != null) {
                throw new IllegalStateException("Discovery is already bound or binding");
            }
            group = Objects.requireNonNull(eventLoopFactory.get(), "eventLoopFactory returned null");
            eventLoops = group;
        }

        ChannelFuture bind = null;
        boolean bound = false;
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
             .channel(NioDatagramChannel.class)
             .option(ChannelOption.SO_BROADCAST, true)
             .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(65535))
             .handler(this);

            bind = bootstrap.bind(address);
            synchronized (lifecycleLock) {
                if (closed) {
                    throw new IllegalStateException("Discovery closed during bind");
                }
                channel = bind.channel();
            }
            bind.sync();
            synchronized (lifecycleLock) {
                if (closed) {
                    throw new IllegalStateException("Discovery closed during bind");
                }
                bound = true;
            }
            log.info("NetherNet Discovery listening on {}", channel.localAddress());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while binding discovery", e);
        } finally {
            if (!bound) {
                try {
                    if (bind != null) {
                        bind.channel().close();
                    }
                } finally {
                    close();
                }
            }
        }
    }

    /**
     * Sends a discovery request. The callback receives every response
     * until replaced or closed and must release each payload buffer it receives.
     */
    public void sendDiscoveryRequest(InetSocketAddress target, BiConsumer<Long, ByteBuf> onServerFound) {
        sendDiscoveryRequest(target, onServerFound, null);
    }

    void sendDiscoveryRequestToPeer(InetSocketAddress target, BiConsumer<Long, ByteBuf> onServerFound) {
        sendDiscoveryRequest(target, onServerFound, Objects.requireNonNull(target, "target"));
    }

    private void sendDiscoveryRequest(InetSocketAddress target, BiConsumer<Long, ByteBuf> onServerFound,
                                      InetSocketAddress expectedSender) {
        DiscoveryCallback callback = new DiscoveryCallback(
                Objects.requireNonNull(onServerFound, "onServerFound"), expectedSender);
        this.discoveryCallback.set(callback);

        ByteBuf buf = Unpooled.buffer();
        buf.writeShortLE(NetherNetConstants.ID_DISCOVERY_REQUEST);
        buf.writeLongLE(this.networkId);
        buf.writeZero(8); // Padding

        try {
            sendPacket(buf, target);
        } catch (RuntimeException e) {
            discoveryCallback.compareAndSet(callback, null);
            throw e;
        }
    }

    void clearDiscoveryCallback(BiConsumer<Long, ByteBuf> callback) {
        DiscoveryCallback current = discoveryCallback.get();
        if (current != null && current.consumer() == callback) {
            discoveryCallback.compareAndSet(current, null);
        }
    }

    private record DiscoveryCallback(BiConsumer<Long, ByteBuf> consumer, InetSocketAddress expectedSender) { }

    public void setPongData(PongData data) {
        ByteBuf buf = Unpooled.buffer();
        byte[] binaryData;
        try {
            buf.writeByte(4); // Version
            writeString(buf, data.serverName());
            writeString(buf, data.levelName());
            writeSignedVarInt(buf, data.gameType());
            buf.writeIntLE(data.playerCount());
            buf.writeIntLE(data.maxPlayerCount());
            buf.writeBoolean(data.isEditorWorld());
            buf.writeBoolean(data.isHardcore());
            writeSignedVarInt(buf, data.transportLayer());
            writeSignedVarInt(buf, data.connectionType());
            binaryData = new byte[buf.readableBytes()];
            buf.readBytes(binaryData);
        } finally {
            buf.release();
        }

        String hex = HexFormat.of().formatHex(binaryData);
        byte[] hexBytes = hex.getBytes(StandardCharsets.UTF_8);

        ByteBuf response = Unpooled.buffer();
        try {
            response.writeIntLE(hexBytes.length);
            response.writeBytes(hexBytes);
            byte[] pongData = new byte[response.readableBytes()];
            response.readBytes(pongData);
            this.pongData = pongData;
        } finally {
            response.release();
        }
    }

    public void registerSignalHandler(long connectionId, SignalHandler handler) {
        Objects.requireNonNull(handler, "handler");
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("Discovery is closed");
            }
            this.signalHandlers.put(connectionId, handler);
            this.peerAddresses.register(connectionId);
        }
    }

    public void unregisterSignalHandler(long connectionId) {
        synchronized (lifecycleLock) {
            this.signalHandlers.remove(connectionId);
            this.peerAddresses.unregister(connectionId);
        }
    }

    public void setNewConnectionHandler(NetherNetServerSignaling.NewConnectionHandler handler) {
        this.newConnectionHandler = handler;
    }

    /**
     * Sends a signal immediately and schedules it to be resent periodically
     * until the returned ScheduledFuture is cancelled.
     */
    public ScheduledFuture<?> sendSignalRetrying(InetSocketAddress recipient, long targetNetworkId, String data, long delayMs) {
        return channel.eventLoop().scheduleAtFixedRate(() -> {
            log.debug("Resending signal to {}: {}", recipient, data);
            sendSignal(recipient, targetNetworkId, data);
        }, 0, delayMs, TimeUnit.MILLISECONDS);
    }

    public void sendSignal(InetSocketAddress recipient, long targetNetworkId, String data) {
        byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.buffer();
        buf.writeShortLE(NetherNetConstants.ID_DISCOVERY_MESSAGE);
        buf.writeLongLE(this.networkId); // Sender ID
        buf.writeZero(8); // Padding

        buf.writeLongLE(targetNetworkId); // Recipient ID
        buf.writeIntLE(dataBytes.length);
        buf.writeBytes(dataBytes);

        sendPacket(buf, recipient);
    }

    // New sendSignal looking up Address from ID
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
            Objects.requireNonNull(target, "target");
            Channel channel = this.channel;
            if (closed || channel == null || !channel.isActive()) {
                throw new IllegalStateException("Discovery is not bound");
            }
            byte[] encrypted = NetherNetConstants.encryptDiscoveryPacket(packetData);
            channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(encrypted), target));
        } catch (Exception e) {
            throw new RuntimeException("Failed to send discovery packet", e);
        } finally {
            packetData.release();
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        if (closed) {
            return;
        }
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
            if (!decrypted.isReadable(18)) {
                return;
            }
            int packetId = decrypted.readUnsignedShortLE();
            long senderId = decrypted.readLongLE();

            decrypted.skipBytes(8); // Padding

            if (senderId == this.networkId) {
                log.debug("Ignoring own discovery packet");
                return;
            }

            switch (packetId) {
                case NetherNetConstants.ID_DISCOVERY_REQUEST -> {
                    if (decrypted.isReadable() || !rememberPeer(senderId, packet.sender(), null)) {
                        return;
                    }
                    log.trace("Handled discovery request from {}", packet.sender());
                    handleRequest(senderId, packet.sender());
                }
                case NetherNetConstants.ID_DISCOVERY_MESSAGE -> {
                    log.trace("Handled discovery message from {}", packet.sender());
                    if (log.isTraceEnabled()) {
                        log.trace("Message Data: {}", decrypted.toString(StandardCharsets.UTF_8));
                    }
                    handleMessage(decrypted, senderId, packet.sender());
                }
                case NetherNetConstants.ID_DISCOVERY_RESPONSE -> {
                    log.trace("Handled discovery response from {}", packet.sender());
                    DiscoveryCallback callback = discoveryCallback.get();
                    if (callback != null && (callback.expectedSender() == null
                            || callback.expectedSender().equals(packet.sender()))
                            && validResponse(decrypted) && rememberPeer(senderId, packet.sender(), null)) {
                        if (log.isTraceEnabled()) {
                            log.trace("Response Data: {}", decrypted.toString(StandardCharsets.UTF_8));
                        }
                        // Pass the payload (decrypted buffer) to the callback
                        // We retain it because we are passing it out of the pipeline handler
                        callback.consumer().accept(senderId, decrypted.retain());
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

    private void handleRequest(long senderId, InetSocketAddress sender) {
        byte[] pongData = this.pongData;
        if (pongData == null) return;

        ByteBuf buf = Unpooled.buffer();
        buf.writeShortLE(NetherNetConstants.ID_DISCOVERY_RESPONSE);
        buf.writeLongLE(this.networkId);
        buf.writeZero(8);
        buf.writeBytes(pongData);

        sendPacket(buf, sender);
    }

    private void handleMessage(ByteBuf data, long senderId, InetSocketAddress sender) {
        if (!data.isReadable(12)) {
            return;
        }
        long recipientId = data.readLongLE();

        if (recipientId != this.networkId && recipientId != 0) {
            log.trace("Ignoring message intended for {}, but I am {}", recipientId, this.networkId);
            return;
        }

        int len = data.readIntLE();
        if (len < 0 || data.readableBytes() < len) {
            log.trace("Malformed message: claimed length {} but only has {}", len, data.readableBytes());
            return;
        }

        // Vanilla can understate this inner length; the outer envelope already bounds the full datagram.
        String messageData = data.readCharSequence(data.readableBytes(), StandardCharsets.UTF_8).toString();
        if ("Ping".equals(messageData)) {
            rememberPeer(senderId, sender, null);
            return;
        }

        String[] parts = messageData.split(" ", 3);
        if (parts.length < 2) return;

        try {
            String type = parts[0];
            if (!NetherNetConstants.RTC_NEGOTIATION_CONNECT_REQUEST.equals(type)
                    && !NetherNetConstants.RTC_NEGOTIATION_CONNECT_RESPONSE.equals(type)
                    && !NetherNetConstants.RTC_NEGOTIATION_CANDIDATE_ADD.equals(type)
                    && !NetherNetConstants.RTC_NEGOTIATION_CONNECT_ERROR.equals(type)) {
                return;
            }
            if (!NetherNetConstants.RTC_NEGOTIATION_CONNECT_ERROR.equals(type)
                    && (parts.length < 3 || parts[2].isEmpty())) {
                return;
            }
            long connectionId = Long.parseUnsignedLong(parts[1]);
            if (!rememberPeer(senderId, sender, connectionId)) {
                return;
            }

            SignalHandler handler = signalHandlers.get(connectionId);

            if (handler != null) {
                handler.onSignal(messageData);
            } else if (NetherNetConstants.RTC_NEGOTIATION_CONNECT_REQUEST.equals(type)) {
                NetherNetServerSignaling.NewConnectionHandler connectionHandler = newConnectionHandler;
                if (connectionHandler != null) {
                    String payload = parts.length > 2 ? parts[2] : "";
                    log.trace("Dispatching New Connection: ID={} Sender={}", Long.toUnsignedString(connectionId), Long.toUnsignedString(senderId));
                    connectionHandler.onConnect(connectionId, Long.toUnsignedString(senderId), payload);
                } else {
                    log.debug("Received CONNECT_REQUEST but no NewConnectionHandler is set!");
                }
            } else {
                log.debug("Unhandled signal type: {}", type);
            }
        } catch (NumberFormatException e) {
            log.debug("Invalid connection ID format in message: {}", messageData);
        }
    }

    private boolean rememberPeer(long peerId, InetSocketAddress address, Long connectionId) {
        synchronized (lifecycleLock) {
            if (closed) {
                return false;
            }
            if (connectionId == null) {
                peerAddresses.remember(peerId, address);
            } else {
                peerAddresses.rememberSignal(connectionId, peerId, address);
            }
            return true;
        }
    }

    private static boolean validResponse(ByteBuf data) {
        if (!data.isReadable(4)) {
            return false;
        }
        int index = data.readerIndex();
        int length = data.getIntLE(index);
        if (length < 0 || length != data.readableBytes() - 4 || (length & 1) != 0) {
            return false;
        }
        for (int i = index + 4; i < data.writerIndex(); i++) {
            int value = data.getUnsignedByte(i);
            if (!(value >= '0' && value <= '9') && !(value >= 'a' && value <= 'f')
                    && !(value >= 'A' && value <= 'F')) {
                return false;
            }
        }
        return true;
    }

    /** Closes the socket and its event loop. A closed instance cannot be rebound. */
    public void close() {
        Channel channel;
        EventLoopGroup group;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            channel = this.channel;
            group = eventLoops;
            signalHandlers.clear();
            peerAddresses.clear();
        }
        discoveryCallback.set(null);
        newConnectionHandler = null;
        try {
            if (channel != null) {
                channel.close();
            }
        } finally {
            if (group != null) {
                group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            }
        }
    }

    public boolean isActive() {
        Channel channel = this.channel;
        return !closed && channel != null && channel.isActive();
    }

    private void writeString(ByteBuf buf, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        this.writeUnsignedVarInt(buf, b.length);
        buf.writeBytes(b);
    }

    private void writeUnsignedVarInt(ByteBuf buf, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            buf.writeByte((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.writeByte((byte) value);
    }

    private void writeSignedVarInt(ByteBuf buf, int value) {
        writeUnsignedVarInt(buf, (value << 1) ^ (value >> 31));
    }
}
