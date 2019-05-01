package com.nukkitx.network.raknet;

import com.nukkitx.network.raknet.util.BitQueue;
import com.nukkitx.network.raknet.util.IntRange;
import com.nukkitx.network.raknet.util.RoundRobinArray;
import com.nukkitx.network.raknet.util.SplitPacketHelper;
import com.nukkitx.network.util.DisconnectReason;
import com.nukkitx.network.util.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnegative;
import javax.annotation.ParametersAreNonnullByDefault;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@ParametersAreNonnullByDefault
public abstract class RakNetSession {
    static final InternalLogger log = InternalLoggerFactory.getInstance(RakNetSession.class);
    private static final AtomicIntegerFieldUpdater<RakNetSession> splitIndexUpdater =
            AtomicIntegerFieldUpdater.newUpdater(RakNetSession.class, "splitIndex");
    private static final AtomicIntegerFieldUpdater<RakNetSession> datagramReadIndexUpdater =
            AtomicIntegerFieldUpdater.newUpdater(RakNetSession.class, "datagramReadIndex");
    private static final AtomicIntegerFieldUpdater<RakNetSession> datagramWriteIndexUpdater =
            AtomicIntegerFieldUpdater.newUpdater(RakNetSession.class, "datagramWriteIndex");
    private static final AtomicIntegerFieldUpdater<RakNetSession> reliabilityWriteIndexUpdater =
            AtomicIntegerFieldUpdater.newUpdater(RakNetSession.class, "reliabilityWriteIndex");
    final InetSocketAddress address;
    private final Channel channel;
    private final ChannelPromise voidPromise;
    int mtu;
    long guid;
    private volatile RakNetState state = RakNetState.UNCONNECTED;
    private volatile long lastTouched = System.currentTimeMillis();
    @Getter
    @Setter
    private RakNetSessionListener listener = null;

    // Reliability, Ordering, Sequencing and datagram indexes
    private volatile int splitIndex;
    private volatile int datagramReadIndex;
    private volatile int datagramWriteIndex;
    private Lock reliabilityReadLock;
    private int reliabilityReadIndex;
    private volatile int reliabilityWriteIndex;
    private AtomicIntegerArray orderReadIndex;
    private AtomicIntegerArray orderWriteIndex;
    private AtomicIntegerArray sequenceReadIndex;
    private AtomicIntegerArray sequenceWriteIndex;

    private RoundRobinArray<SplitPacketHelper> splitPackets;
    private BitQueue reliableDatagramQueue;

    private EncapsulatedBinaryHeap[] orderingHeaps;
    private volatile boolean closed = false;
    private volatile long currentPingTime = -1;
    private volatile long lastPongTime = -1;
    private RoundRobinArray<RakNetDatagram> sentDatagrams;

    RakNetSession(InetSocketAddress address, Channel channel, int mtu) {
        this.address = address;
        this.channel = channel;
        this.mtu = mtu;
        // We can reuse this instead of creating a new one each time
        this.voidPromise = channel.voidPromise();
    }

    final void initialize() {
        Preconditions.checkState(this.state == RakNetState.INITIALIZING);

        reliableDatagramQueue = new BitQueue(512);
        reliabilityReadLock = new ReentrantLock(true);
        orderReadIndex = new AtomicIntegerArray(RakNetConstants.MAXIMUM_ORDERING_CHANNELS);
        orderWriteIndex = new AtomicIntegerArray(RakNetConstants.MAXIMUM_ORDERING_CHANNELS);
        sequenceReadIndex = new AtomicIntegerArray(RakNetConstants.MAXIMUM_ORDERING_CHANNELS);
        sequenceWriteIndex = new AtomicIntegerArray(RakNetConstants.MAXIMUM_ORDERING_CHANNELS);

        orderingHeaps = new EncapsulatedBinaryHeap[RakNetConstants.MAXIMUM_ORDERING_CHANNELS];
        splitPackets = new RoundRobinArray<>(RakNetConstants.MAXIMUM_SPLIT_COUNT);
        sentDatagrams = new RoundRobinArray<>(512);
        for (int i = 0; i < RakNetConstants.MAXIMUM_ORDERING_CHANNELS; i++) {
            orderingHeaps[i] = new EncapsulatedBinaryHeap(64);
        }
    }

    public InetSocketAddress getAddress() {
        return this.address;
    }

    public int getMtu() {
        return this.mtu;
    }

    public ByteBuf allocateBuffer(int capacity) {
        return this.channel.alloc().directBuffer(capacity);
    }

    private EncapsulatedPacket getReassembledPacket(EncapsulatedPacket splitPacket) {
        this.checkForClosed();

        SplitPacketHelper helper = this.splitPackets.get(splitPacket.getPartId());
        if (helper == null) {
            this.splitPackets.set(splitPacket.getPartId(), helper = new SplitPacketHelper(splitPacket.getPartCount()));
        }

        // Try reassembling the packet.
        EncapsulatedPacket result = helper.add(splitPacket, this);
        if (result != null) {
            // Packet reassembled. Remove the helper
            this.splitPackets.remove(splitPacket.getPartId());
            helper.release();
        }

        return result;
    }

    void onDatagram(DatagramPacket datagram) {
        if (!datagram.sender().equals(address)) {
            // Somehow we have received a datagram from the wrong peer...
            return;
        }

        this.touch();

        ByteBuf buffer = datagram.content();

        byte potentialFlags = buffer.readByte();

        boolean rakNetDatagram = (potentialFlags & RakNetConstants.FLAG_VALID) != 0;

        // Potential RakNet datagram
        if (rakNetDatagram) {
            // Block RakNet datagrams if we haven't initialized the session yet.
            if (this.state.ordinal() >= RakNetState.INITIALIZED.ordinal()) {
                if ((potentialFlags & RakNetConstants.FLAG_ACK) != 0) {
                    this.onAck(buffer);
                } else if ((potentialFlags & RakNetConstants.FLAG_NACK) != 0) {
                    this.onNak(buffer);
                } else {
                    buffer.readerIndex(0);
                    this.onRakNetDatagram(buffer);
                }
            }
        } else {
            // Direct packet
            buffer.readerIndex(0);
            this.onPacketInternal(buffer);
        }
    }

    private void onPacketInternal(ByteBuf buffer) {
        short packetId = buffer.readUnsignedByte();
        switch (packetId) {
            case RakNetConstants.ID_CONNECTED_PING:
                this.onConnectedPing(buffer);
                break;
            case RakNetConstants.ID_CONNECTED_PONG:
                this.onConnectedPong(buffer);
                break;
            case RakNetConstants.ID_DISCONNECTION_NOTIFICATION:
                this.onDisconnectionNotification();
                break;
            default:
                buffer.readerIndex(0);
                if (packetId >= RakNetConstants.ID_USER_PACKET_ENUM) {
                    // Forward to user
                    if (this.listener != null) {
                        this.listener.onUserPacket(buffer);
                    } else {
                        log.debug("Unhandled RakNet user packet");
                    }
                } else {
                    this.onPacket(buffer);
                }
                break;
        }
    }

    protected abstract void onPacket(ByteBuf buffer);

    private void onRakNetDatagram(ByteBuf buffer) {
        if (this.state == RakNetState.DISCONNECTED) {
            return;
        }

        RakNetDatagram datagram = new RakNetDatagram();
        datagram.decode(buffer);

        int missedDatagrams = datagram.sequenceIndex - datagramReadIndexUpdater.getAndAccumulate(this,
                datagram.sequenceIndex, (prev, newIndex) -> newIndex + 1);

        if (missedDatagrams > 0) {
            this.sendNack(new IntRange[]{new IntRange(datagram.sequenceIndex - missedDatagrams, datagram.sequenceIndex)});
        }

        this.sendAck(new IntRange[]{new IntRange(datagram.sequenceIndex, datagram.sequenceIndex)});

        for (final EncapsulatedPacket encapsulated : datagram.packets) {
            if (encapsulated.reliability.isReliable()) {
                reliabilityReadLock.lock();
                try {
                    int missed = encapsulated.reliabilityIndex - this.reliabilityReadIndex;

                    if (missed > 0) {
                        if (missed < this.reliableDatagramQueue.size()) {
                            if (this.reliableDatagramQueue.get(missed)) {
                                this.reliableDatagramQueue.set(missed, false);
                            } else {
                                // Duplicate packet
                                continue;
                            }
                        } else {
                            int count = (missed - this.reliableDatagramQueue.size());
                            for (int i = 0; i < count; i++) {
                                this.reliableDatagramQueue.add(true);
                            }

                            this.reliableDatagramQueue.add(false);
                        }
                    } else if (missed == 0) {
                        this.reliabilityReadIndex++;
                        if (!this.reliableDatagramQueue.isEmpty()) this.reliableDatagramQueue.poll();
                    } else {
                        // Duplicate packet
                        continue;
                    }
                } finally {
                    reliabilityReadLock.unlock();
                }
            }


            if (encapsulated.split) {
                final EncapsulatedPacket reassembled = this.getReassembledPacket(encapsulated);
                try {
                    if (reassembled == null) {
                        // Not reassembled
                        continue;
                    }
                    this.onEncapsulated(reassembled);
                } finally {
                    if (reassembled != null) {
                        reassembled.release();
                    }
                }
            } else {
                this.onEncapsulated(encapsulated);
            }
        }
    }

    private void onEncapsulated(EncapsulatedPacket packet) {
        if (packet.getReliability().isOrdered()) {
            this.onOrderedReceived(packet);
        } else {
            this.onPacketInternal(packet.buffer);
        }
    }

    private void onOrderedReceived(EncapsulatedPacket packet) {
        EncapsulatedBinaryHeap binaryHeap = this.orderingHeaps[packet.orderingChannel];

        if (this.orderReadIndex.get(packet.orderingChannel) < packet.orderingIndex) {
            // Not next in line so add to queue.
            packet.retain();
            binaryHeap.insert(packet);
            return;
        } else if (this.orderReadIndex.get(packet.orderingChannel) > packet.orderingIndex) {
            // We already have this
            return;
        }
        this.orderReadIndex.incrementAndGet(packet.orderingChannel);

        // Send this packet
        this.onPacketInternal(packet.buffer);

        EncapsulatedPacket queuedPacket;
        while ((queuedPacket = binaryHeap.peek()) != null) {
            if (queuedPacket.orderingIndex == this.orderReadIndex.get(packet.orderingChannel)) {
                // We got the expected packet
                binaryHeap.remove();
                this.orderReadIndex.incrementAndGet(packet.orderingChannel);

                try {
                    this.onPacketInternal(packet.buffer);
                } finally {
                    packet.release();
                }
            }
        }
    }

    final void onTick() {
        if (this.closed) {
            return;
        }
        this.tick();
    }

    protected void tick() {
        if (this.isTimedOut()) {
            this.close(DisconnectReason.TIMED_OUT);
        }

        if (this.isStale() && this.state.ordinal() >= RakNetState.INITIALIZED.ordinal()) {
            this.sendConnectedPing(System.currentTimeMillis());
        }
    }

    public void disconnect() {
        disconnect(DisconnectReason.DISCONNECTED);
    }

    public void disconnect(DisconnectReason reason) {
        if (this.isClosed()) {
            return;
        }
        this.sendDisconnectionNotification();
        this.close(reason);
    }

    public void close(DisconnectReason reason) {
        this.checkForClosed();
        this.closed = true;
        this.state = RakNetState.UNCONNECTED;
        this.onClose();
        log.trace("RakNet Session ({} => {}) closed: {}", this.getRakNet().bindAddress, this.address, reason);

        // Perform resource clean up.
        if (this.splitPackets != null) {
            this.splitPackets.forEach(ReferenceCountUtil::release);
        }
        if (this.sentDatagrams != null) {
            this.sentDatagrams.forEach(ReferenceCountUtil::release);
        }

        if (this.listener != null) {
            this.listener.onDisconnect(reason);
        }
    }

    protected void onClose() {
    }

    public void send(ByteBuf buf) {
        this.send(buf, RakNetReliability.RELIABLE);
    }

    public void send(ByteBuf buf, RakNetReliability reliability) {
        this.send(buf, reliability, 0);
    }

    public void send(ByteBuf buf, RakNetReliability reliability, @Nonnegative int orderingChannel) {
        int readerIndex = buf.readerIndex();
        buf.readerIndex(readerIndex);
        if (state.ordinal() < RakNetState.INITIALIZED.ordinal()) {
            // Session is not ready for RakNet datagrams.
            return;
        }
        int maxLength = (this.mtu - RakNetConstants.MAXIMUM_ENCAPSULATED_HEADER_SIZE) - RakNetConstants.MAXIMUM_UDP_HEADER_SIZE;

        ByteBuf[] bufs;
        int splitId = 0;

        if (buf.readableBytes() > maxLength) {
            // Packet requires splitting
            // Adjust reliability
            switch (reliability) {
                case UNRELIABLE:
                    reliability = RakNetReliability.RELIABLE;
                    break;
                case UNRELIABLE_SEQUENCED:
                    reliability = RakNetReliability.RELIABLE_SEQUENCED;
                    break;
                case UNRELIABLE_WITH_ACK_RECEIPT:
                    reliability = RakNetReliability.RELIABLE_WITH_ACK_RECEIPT;
                    break;
            }

            int split = ((buf.readableBytes() - 1) / maxLength) + 1;
            bufs = new ByteBuf[split];
            buf.retain(split - 1); // All derived ByteBufs share the same reference counter.
            for (int i = 0; i < split; i++) {
                bufs[i] = buf.readSlice(Math.min(maxLength, buf.readableBytes()));
            }

            // Allocate split ID
            splitId = splitIndexUpdater.getAndIncrement(this);
        } else {
            bufs = new ByteBuf[]{buf};
        }

        // Set meta
        int orderingIndex = 0;
        /*int sequencingIndex = 0;
        if (reliability.isSequenced()) {
            sequencingIndex = this.sequenceWriteIndex.getAndIncrement(orderingChannel);
        } todo: sequencing */
        if (reliability.isOrdered()) {
            orderingIndex = this.orderWriteIndex.getAndIncrement(orderingChannel);
        }

        // Now create the packets.
        for (int i = 0, parts = bufs.length; i < parts; i++) {
            EncapsulatedPacket packet = new EncapsulatedPacket();
            packet.setBuffer(bufs[i]);
            packet.setOrderingChannel((short) orderingChannel);
            packet.setOrderingIndex(orderingIndex);
            //packet.setSequenceIndex(sequencingIndex);
            if (reliability.isReliable()) {
                packet.setReliabilityIndex(reliabilityWriteIndexUpdater.getAndIncrement(this));
            }
            packet.setReliability(reliability);

            if (parts > 1) {
                packet.setSplit(true);
                packet.setPartIndex(i);
                packet.setPartCount(parts);
                packet.setPartId(splitId);
            }

            RakNetDatagram datagram = new RakNetDatagram();
            datagram.setSequenceIndex(datagramWriteIndexUpdater.getAndIncrement(this));
            if (!datagram.tryAddPacket(packet, this.mtu)) {
                throw new IllegalArgumentException("Packet too large to fit in MTU (size: " + packet.getSize() + ", MTU: " + this.mtu + ")");
            }

            this.sendDirect(datagram);
            this.sentDatagrams.set(datagram.getSequenceIndex(), datagram.retain()); // retain in case we need to resend it
        }
        this.channel.flush();
    }

    private void sendDirect(RakNetDatagram datagram) {
        ByteBuf buf = this.channel.alloc().directBuffer();
        datagram.encode(buf);
        this.channel.write(new DatagramPacket(buf, this.address), this.voidPromise);
    }

    void sendDirect(ByteBuf buffer) {
        this.channel.writeAndFlush(new DatagramPacket(buffer, this.address), this.voidPromise);
    }

    /*
        Packet Handlers
     */

    private void onAck(ByteBuf buffer) {
        this.checkForClosed();

        IntRange[] acked = RakNetUtils.readIntRanges(buffer);

        for (IntRange range : acked) {
            for (int i = range.start; i <= range.end; i++) {
                RakNetDatagram datagram = this.sentDatagrams.remove(i);
                if (datagram != null && datagram.sequenceIndex == i) {
                    // Release the native buffers so we don't cause memory leaks
                    datagram.release();
                }
            }
        }
    }

    private void onNak(ByteBuf buffer) {
        this.checkForClosed();

        IntRange[] acked = RakNetUtils.readIntRanges(buffer);

        for (IntRange range : acked) {
            for (int i = range.start; i <= range.end; i++) {
                RakNetDatagram datagram = this.sentDatagrams.get(i);
                if (datagram != null && datagram.sequenceIndex == i) {
                    log.trace("Resending datagram {} after NAK to {}", datagram.sequenceIndex, address);
                    // Retain for resending
                    datagram.retain();
                    this.sendDirect(datagram);
                }
            }
        }

        this.channel.flush();
    }

    private void onConnectedPing(ByteBuf buffer) {
        long pingTime = buffer.readLong();

        this.sendConnectedPong(pingTime);
    }

    private void onConnectedPong(ByteBuf buffer) {
        long pingTime = buffer.readLong();

        if (this.currentPingTime == pingTime) {
            this.lastPongTime = this.currentPingTime;
            this.currentPingTime = System.currentTimeMillis();
        }
    }

    private void onDisconnectionNotification() {
        this.close(DisconnectReason.CLOSED_BY_REMOTE_PEER);
    }

    /*
        Packet Dispatchers
     */

    private void sendConnectedPing(long pingTime) {
        ByteBuf buffer = this.allocateBuffer(9);
        try {
            buffer.writeByte(RakNetConstants.ID_CONNECTED_PING);
            buffer.writeLong(pingTime);

            this.send(buffer);
        } finally {
            buffer.release();
        }

        this.currentPingTime = pingTime;
    }

    private void sendConnectedPong(long pingTime) {
        ByteBuf buffer = this.allocateBuffer(17);
        try {
            buffer.writeByte(RakNetConstants.ID_CONNECTED_PONG);
            buffer.writeLong(pingTime);
            buffer.writeLong(System.currentTimeMillis());

            this.send(buffer);
        } finally {
            buffer.release();
        }
    }

    private void sendDisconnectionNotification() {
        ByteBuf buffer = this.allocateBuffer(1);
        try {
            buffer.writeByte(RakNetConstants.ID_DISCONNECTION_NOTIFICATION);

            this.send(buffer, RakNetReliability.RELIABLE_ORDERED);
        } finally {
            buffer.release();
        }
    }

    private void sendDetectLostConnection() {
        ByteBuf buffer = this.allocateBuffer(1);
        buffer.writeByte(RakNetConstants.ID_DETECT_LOST_CONNECTION);

        this.send(buffer);
    }

    private void sendAck(IntRange[] toAck) {
        int length = 3;
        for (IntRange range : toAck) {
            length += range.start == range.end ? 4 : 7;
        }

        ByteBuf buffer = this.allocateBuffer(length);
        buffer.writeByte(RakNetConstants.FLAG_VALID | RakNetConstants.FLAG_ACK);
        RakNetUtils.writeIntRanges(buffer, toAck);

        this.sendDirect(buffer);
    }

    private void sendNack(IntRange[] toNack) {
        int length = 3;
        for (IntRange range : toNack) {
            length += range.start == range.end ? 4 : 7;
        }

        ByteBuf buffer = this.allocateBuffer(length);
        buffer.writeByte(RakNetConstants.FLAG_VALID | RakNetConstants.FLAG_NACK);
        RakNetUtils.writeIntRanges(buffer, toNack);

        this.sendDirect(buffer);
    }

    private void touch() {
        this.checkForClosed();
        this.lastTouched = System.currentTimeMillis();
    }

    public boolean isStale() {
        return System.currentTimeMillis() - this.lastTouched >= RakNetConstants.SESSION_STALE_MS;
    }

    public boolean isTimedOut() {
        return System.currentTimeMillis() - this.lastTouched >= RakNetConstants.SESSION_TIMEOUT_MS;
    }

    private void checkForClosed() {
        Preconditions.checkState(!this.closed, "Session already closed");
    }

    public boolean isClosed() {
        return this.closed;
    }

    public abstract RakNet getRakNet();

    boolean isIpv6Session() {
        return this.address.getAddress() instanceof Inet6Address;
    }

    public RakNetState getState() {
        return state;
    }

    void setState(RakNetState state) {
        if (this.state != state) {
            this.state = state;
            if (this.listener != null) {
                this.listener.onSessionChangeState(this.state);
            }
        }
    }
}
