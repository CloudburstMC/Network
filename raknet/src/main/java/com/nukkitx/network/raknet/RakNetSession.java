package com.nukkitx.network.raknet;

import com.nukkitx.network.SessionConnection;
import com.nukkitx.network.raknet.util.*;
import com.nukkitx.network.util.DisconnectReason;
import com.nukkitx.network.util.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnegative;
import javax.annotation.ParametersAreNonnullByDefault;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@ParametersAreNonnullByDefault
public abstract class RakNetSession implements SessionConnection<ByteBuf> {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakNetSession.class);
    private static final AtomicIntegerFieldUpdater<RakNetSession> splitIndexUpdater =
            AtomicIntegerFieldUpdater.newUpdater(RakNetSession.class, "splitIndex");
    private static final AtomicIntegerFieldUpdater<RakNetSession> datagramReadIndexUpdater =
            AtomicIntegerFieldUpdater.newUpdater(RakNetSession.class, "datagramReadIndex");
    private static final AtomicIntegerFieldUpdater<RakNetSession> datagramWriteIndexUpdater =
            AtomicIntegerFieldUpdater.newUpdater(RakNetSession.class, "datagramWriteIndex");
    private static final AtomicIntegerFieldUpdater<RakNetSession> reliabilityWriteIndexUpdater =
            AtomicIntegerFieldUpdater.newUpdater(RakNetSession.class, "reliabilityWriteIndex");
    private static final AtomicIntegerFieldUpdater<RakNetSession> unackedBytesUpdater =
            AtomicIntegerFieldUpdater.newUpdater(RakNetSession.class, "unackedBytes");
    final InetSocketAddress address;
    private final Channel channel;
    private final ChannelPromise voidPromise;
    final EventLoop eventLoop;
    int mtu;
    long guid;
    private volatile RakNetState state = RakNetState.UNCONNECTED;
    private volatile long lastTouched = System.currentTimeMillis();
    @Getter
    @Setter
    private RakNetSessionListener listener = null;

    // Reliability, Ordering, Sequencing and datagram indexes
    private RakNetSlidingWindow slidingWindow;
    private volatile int splitIndex = 1;
    private volatile int datagramReadIndex;
    private volatile int datagramWriteIndex;
    private Lock reliabilityReadLock;
    private int reliabilityReadIndex;
    private volatile int reliabilityWriteIndex;
    private AtomicIntegerArray orderReadIndex;
    private AtomicIntegerArray orderWriteIndex;
    private AtomicIntegerArray sequenceReadIndex;
    private AtomicIntegerArray sequenceWriteIndex;
    private Lock outgoingLock;

    private RoundRobinArray<SplitPacketHelper> splitPackets;
    private BitQueue reliableDatagramQueue;

    private FastBinaryMinHeap<EncapsulatedPacket> outgoingPackets;
    private long[] outgoingPacketNextWeights;
    private FastBinaryMinHeap<EncapsulatedPacket>[] orderingHeaps;
    private Lock orderingLock;
    private volatile boolean closed = false;
    private volatile long currentPingTime = -1;
    private volatile long lastPingTime = -1;
    private volatile long lastPongTime = -1;
    private ConcurrentMap<Integer, RakNetDatagram> resendQueue;
    private Queue<IntRange> incomingAcks;
    private Queue<IntRange> incomingNaks;
    private volatile int unackedBytes;
    private volatile long lastMinWeight;

    RakNetSession(InetSocketAddress address, Channel channel, int mtu, EventLoop eventLoop) {
        this.address = address;
        this.channel = channel;
        this.mtu = mtu;
        this.eventLoop = eventLoop;
        // We can reuse this instead of creating a new one each time
        this.voidPromise = channel.voidPromise();
    }

    final void initialize() {
        Preconditions.checkState(this.state == RakNetState.INITIALIZING);

        this.slidingWindow = new RakNetSlidingWindow(this.mtu);

        this.reliableDatagramQueue = new BitQueue(512);
        this.reliabilityReadLock = new ReentrantLock(true);
        this.orderReadIndex = new AtomicIntegerArray(RakNetConstants.MAXIMUM_ORDERING_CHANNELS);
        this.orderWriteIndex = new AtomicIntegerArray(RakNetConstants.MAXIMUM_ORDERING_CHANNELS);
        this.sequenceReadIndex = new AtomicIntegerArray(RakNetConstants.MAXIMUM_ORDERING_CHANNELS);
        this.sequenceWriteIndex = new AtomicIntegerArray(RakNetConstants.MAXIMUM_ORDERING_CHANNELS);

        //noinspection unchecked
        this.orderingHeaps = new FastBinaryMinHeap[RakNetConstants.MAXIMUM_ORDERING_CHANNELS];
        this.orderingLock = new ReentrantLock(true);
        this.splitPackets = new RoundRobinArray<>(256);
        this.resendQueue = new ConcurrentHashMap<>(512);
        for (int i = 0; i < RakNetConstants.MAXIMUM_ORDERING_CHANNELS; i++) {
            orderingHeaps[i] = new FastBinaryMinHeap<>(64);
        }

        this.outgoingPackets = new FastBinaryMinHeap<>(8);
        this.outgoingLock = new ReentrantLock();
        this.incomingAcks = PlatformDependent.newMpscQueue();
        this.incomingNaks = PlatformDependent.newMpscQueue();
        this.outgoingPacketNextWeights = new long[4];
        this.initHeapWeights();
    }

    public InetSocketAddress getAddress() {
        return this.address;
    }

    public int getMtu() {
        return this.mtu;
    }

    public long getPing() {
        return this.lastPongTime - this.lastPingTime;
    }

    public double getRTT() {
        return this.slidingWindow.getRTT();
    }

    public ByteBuf allocateBuffer(int capacity) {
        return this.channel.alloc().directBuffer(capacity);
    }

    private void initHeapWeights() {
        for (int priorityLevel = 0; priorityLevel < 4; priorityLevel++) {
            this.outgoingPacketNextWeights[priorityLevel] = (1 << priorityLevel) * priorityLevel + priorityLevel;
        }
    }

    private long getNextWeight(RakNetPriority priority) {
        int priorityLevel = priority.ordinal();
        long next = this.outgoingPacketNextWeights[priorityLevel];

        if (!this.outgoingPackets.isEmpty()) {
            if (next >= this.lastMinWeight) {
                next = this.lastMinWeight + (1 << priorityLevel) * priorityLevel + priorityLevel;
                this.outgoingPacketNextWeights[priorityLevel] = next + (1 << priorityLevel) * (priorityLevel + 1) + priorityLevel;
            }
        } else {
            this.initHeapWeights();
        }
        this.lastMinWeight = next - (1 << priorityLevel) * priorityLevel + priorityLevel;
        return next;
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
            if (this.splitPackets.remove(splitPacket.getPartId(), helper)) {
                helper.release();
            }
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

    private void onEncapsulatedInternal(EncapsulatedPacket packet) {
        ByteBuf buffer = packet.buffer;
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
                        this.listener.onEncapsulated(packet);
                    } else {
                        log.debug("Unhandled RakNet user packet");
                    }
                } else {
                    this.onPacket(buffer);
                }
                break;
        }
    }

    private void onPacketInternal(ByteBuf buffer) {
        short packetId = buffer.getUnsignedByte(buffer.readerIndex());
        buffer.readerIndex(0);
        if (packetId >= RakNetConstants.ID_USER_PACKET_ENUM) {
            // Forward to user
            if (this.listener != null) {
                this.listener.onDirect(buffer);
            } else {
                log.debug("Unhandled RakNet user packet");
            }
        } else {
            this.onPacket(buffer);
        }
    }

    protected abstract void onPacket(ByteBuf buffer);

    private void onRakNetDatagram(ByteBuf buffer) {
        if (this.state == RakNetState.DISCONNECTED) {
            return;
        }

        RakNetDatagram datagram = new RakNetDatagram(System.currentTimeMillis());
        datagram.decode(buffer);

        this.slidingWindow.onPacketReceived(datagram.sendTime);

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

                    while (!this.reliableDatagramQueue.isEmpty() && !this.reliableDatagramQueue.peek()) {
                        this.reliableDatagramQueue.poll();
                        ++this.reliabilityReadIndex;
                    }
                } finally {
                    reliabilityReadLock.unlock();
                }
            }


            if (encapsulated.split) {
                final EncapsulatedPacket reassembled = this.getReassembledPacket(encapsulated);
                if (reassembled == null) {
                    // Not reassembled
                    continue;
                }
                try {
                    this.checkForOrdered(reassembled);
                } finally {
                    reassembled.release();
                }
            } else {
                this.checkForOrdered(encapsulated);
            }
        }
    }

    private void checkForOrdered(EncapsulatedPacket packet) {
        if (packet.getReliability().isOrdered()) {
            this.onOrderedReceived(packet);
        } else {
            this.onEncapsulatedInternal(packet);
        }
    }

    private void onOrderedReceived(EncapsulatedPacket packet) {
        this.orderingLock.lock();
        try {
            FastBinaryMinHeap<EncapsulatedPacket> binaryHeap = this.orderingHeaps[packet.orderingChannel];

            if (this.orderReadIndex.get(packet.orderingChannel) < packet.orderingIndex) {
                // Not next in line so add to queue.
                packet.retain();
                binaryHeap.insert(packet.orderingIndex, packet);
                return;
            } else if (this.orderReadIndex.get(packet.orderingChannel) > packet.orderingIndex) {
                // We already have this
                return;
            }
            this.orderReadIndex.incrementAndGet(packet.orderingChannel);

            // Can be handled
            this.onEncapsulatedInternal(packet);

            EncapsulatedPacket queuedPacket;
            while ((queuedPacket = binaryHeap.peek()) != null) {
                if (queuedPacket.orderingIndex == this.orderReadIndex.get(packet.orderingChannel)) {
                    // We got the expected packet
                    binaryHeap.remove();
                    this.orderReadIndex.incrementAndGet(packet.orderingChannel);

                    try {
                        this.onEncapsulatedInternal(queuedPacket);
                    } finally {
                        queuedPacket.release();
                    }
                }
            }
        } finally {
            this.orderingLock.unlock();
        }
    }

    final void onTick(long curTime) {
        if (this.closed) {
            return;
        }
        this.tick(curTime);
    }

    protected void tick(long curTime) {
        if (this.isTimedOut()) {
            this.close(DisconnectReason.TIMED_OUT);
            return;
        }

        if (this.state.ordinal() < RakNetState.INITIALIZED.ordinal()) {
            return;
        }

        if (this.currentPingTime + 2000L < curTime) {
            this.sendConnectedPing(curTime);
        }

        // Incoming queues

        if (!this.incomingAcks.isEmpty()) {

            IntRange range;
            while ((range = this.incomingAcks.poll()) != null) {
                for (int i = range.start; i <= range.end; i++) {
                    RakNetDatagram datagram = this.resendQueue.remove(i);
                    if (datagram != null) {
                        datagram.release();
                        unackedBytesUpdater.addAndGet(this, -datagram.getSize());
                        this.slidingWindow.onAck(curTime - datagram.sendTime, datagram.sequenceIndex, this.datagramReadIndex);
                    }
                }
            }
        }

        if (!this.incomingNaks.isEmpty()) {
            this.slidingWindow.onNak();
            IntRange range;
            while ((range = this.incomingNaks.poll()) != null) {
                for (int i = range.start; i <= range.end; i++) {
                    RakNetDatagram datagram = this.resendQueue.get(i);
                    if (datagram != null) {
                        log.trace("Resending datagram {} after NAK to {}", datagram.sequenceIndex, address);
                        // Resend this tick
                        datagram.nextSend = 0;
                    } else if (log.isTraceEnabled()) {
                        log.trace("NAK received for {} but was already ACK'ed", i);
                    }
                }
            }
        }

        // Outgoing queues

        int transmissionBandwidth = this.slidingWindow.getRetransmissionBandwidth(this.unackedBytes);
        // Send packets that were NAKed or stale first
        boolean hasResent = false;
        for (RakNetDatagram datagram : this.resendQueue.values()) {
            if (datagram.nextSend <= curTime) {
                int size = datagram.getSize();
                if (transmissionBandwidth < size) {
                    break;
                }
                transmissionBandwidth -= size;
                if (!hasResent) {
                    hasResent = true;
                }
                this.sendDatagram(datagram.retain(), curTime, false);
            }
        }
        if (hasResent) {
            this.slidingWindow.onResend(curTime);
        }

        this.outgoingLock.lock();

        try {
            // Now send usual packets
            if (!this.outgoingPackets.isEmpty()) {
                transmissionBandwidth = this.slidingWindow.getTransmissionBandwidth(this.unackedBytes);
                RakNetDatagram datagram = new RakNetDatagram(curTime);
                EncapsulatedPacket packet;

                while ((packet = this.outgoingPackets.peek()) != null) {
                    int size = packet.getSize();
                    if (transmissionBandwidth < size) {
                        break;
                    }
                    transmissionBandwidth -= size;

                    this.outgoingPackets.remove();

                    if (packet.reliability.isReliable()) {
                        packet.reliabilityIndex = reliabilityWriteIndexUpdater.getAndIncrement(this);
                    }
                    unackedBytesUpdater.addAndGet(this, packet.getSize());

                    if (!datagram.tryAddPacket(packet, this.mtu)) {
                        // Send
                        this.sendDatagram(datagram, curTime, true);

                        datagram = new RakNetDatagram(curTime);

                        if (!datagram.tryAddPacket(packet, this.mtu)) {
                            throw new IllegalArgumentException("Packet too large to fit in MTU (size: " +
                                    packet.getSize() + ", MTU: " + this.mtu + ")");
                        }
                    }
                }

                if (!datagram.packets.isEmpty()) {
                    this.sendDatagram(datagram, curTime, true);
                }
            }
            this.channel.flush();
        } finally {
            this.outgoingLock.unlock();
        }
    }

    @Override
    public void disconnect() {
        disconnect(DisconnectReason.DISCONNECTED);
    }

    @Override
    public void disconnect(DisconnectReason reason) {
        if (this.isClosed()) {
            return;
        }
        this.sendDisconnectionNotification();
        this.close(reason);
    }

    @Override
    public void close() {
        this.close(DisconnectReason.DISCONNECTED);
    }

    @Override
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
        if (this.resendQueue != null) {
            this.resendQueue.values().forEach(ReferenceCountUtil::release);
        }

        if (this.listener != null) {
            this.listener.onDisconnect(reason);
        }
    }

    protected void onClose() {
    }

    @Override
    public void sendImmediate(ByteBuf buf) {
        this.send(buf, RakNetPriority.IMMEDIATE);
    }

    @Override
    public void send(ByteBuf buf) {
        this.send(buf, RakNetPriority.MEDIUM);
    }

    public void send(ByteBuf buf, RakNetPriority priority) {
        this.send(buf, priority, RakNetReliability.RELIABLE_ORDERED);
    }

    public void send(ByteBuf buf, RakNetReliability reliability) {
        this.send(buf, RakNetPriority.MEDIUM, reliability);
    }

    public void send(ByteBuf buf, RakNetPriority priority, RakNetReliability reliability) {
        this.send(buf, priority, reliability, 0);
    }

    public void send(ByteBuf buf, RakNetPriority priority, RakNetReliability reliability, @Nonnegative int orderingChannel) {
        if (state.ordinal() < RakNetState.INITIALIZED.ordinal()) {
            // Session is not ready for RakNet datagrams.
            return;
        }
        if (priority == RakNetPriority.IMMEDIATE) {
            this.sendImmediate(buf, reliability, orderingChannel);
            return;
        }

        EncapsulatedPacket[] packets = this.createEncapsulated(buf, priority, reliability, orderingChannel);

        this.outgoingLock.lock();
        try {
            long weight = this.getNextWeight(priority);
            if (packets.length == 1) {
                this.outgoingPackets.insert(weight, packets[0]);
            } else {
                this.outgoingPackets.insertSeries(weight, packets);
            }
        } finally {
            this.outgoingLock.unlock();
        }
    }

    private void sendImmediate(ByteBuf buf, RakNetReliability reliability, @Nonnegative int orderingChannel) {
        EncapsulatedPacket[] packets = this.createEncapsulated(buf, RakNetPriority.IMMEDIATE, reliability, orderingChannel);
        long curTime = System.currentTimeMillis();

        for (EncapsulatedPacket packet : packets) {
            RakNetDatagram datagram = new RakNetDatagram(curTime);

            if (packet.reliability.isReliable()) {
                packet.reliabilityIndex = reliabilityWriteIndexUpdater.getAndIncrement(this);
            }

            if (!datagram.tryAddPacket(packet, this.mtu)) {
                throw new IllegalArgumentException("Packet too large to fit in MTU (size: " + packet.getSize() +
                        ", MTU: " + this.mtu + ")");
            }
            this.sendDatagram(datagram, curTime, true);
        }
        this.channel.flush();
    }

    private EncapsulatedPacket[] createEncapsulated(ByteBuf buf, RakNetPriority priority, RakNetReliability reliability,
                                                    int orderingChannel) {
        int maxLength = this.mtu - RakNetConstants.MAXIMUM_ENCAPSULATED_HEADER_SIZE - RakNetConstants.MAXIMUM_UDP_HEADER_SIZE;

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
            buf.retain(split - 1); // Retain all split buffers minus 1 for the existing reference count
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
        EncapsulatedPacket[] packets = new EncapsulatedPacket[bufs.length];
        for (int i = 0, parts = bufs.length; i < parts; i++) {
            EncapsulatedPacket packet = new EncapsulatedPacket();
            packet.buffer = bufs[i];
            packet.orderingChannel = (short) orderingChannel;
            packet.orderingIndex = orderingIndex;
            //packet.setSequenceIndex(sequencingIndex);
            packet.reliability = reliability;
            packet.priority = priority;

            if (parts > 1) {
                packet.split = true;
                packet.partIndex = i;
                packet.partCount = parts;
                packet.partId = splitId;
            }

            packets[i] = packet;
        }
        return packets;
    }

    private void sendDatagram(RakNetDatagram datagram, long time, boolean firstSend) {
        Preconditions.checkArgument(!datagram.packets.isEmpty(), "RakNetDatagram with no packets");
        try {
            if (datagram.sequenceIndex == -1) {
                datagram.sequenceIndex = datagramWriteIndexUpdater.getAndIncrement(this);
            }
            for (EncapsulatedPacket packet : datagram.packets) {
                // check if packet is reliable so it can be resent later if a NAK is received.
                if (packet.reliability != RakNetReliability.UNRELIABLE &&
                        packet.reliability != RakNetReliability.UNRELIABLE_SEQUENCED) {
                    datagram.nextSend = time + this.slidingWindow.getRtoForRetransmission();
                    if (firstSend) {
                        this.resendQueue.put(datagram.sequenceIndex, datagram.retain());
                    }
                    break;
                }
            }
            ByteBuf buf = this.channel.alloc().directBuffer(datagram.getSize());
            datagram.encode(buf);
            this.channel.write(new DatagramPacket(buf, this.address), this.voidPromise);
        } finally {
            datagram.release();
        }
    }

    void sendDirect(ByteBuf buffer) {
        this.channel.writeAndFlush(new DatagramPacket(buffer, this.address), this.voidPromise);
    }

    /*
        Packet Handlers
     */

    private void onAck(ByteBuf buffer) {
        this.checkForClosed();

        RakNetUtils.readIntRangesToQueue(buffer, this.incomingAcks);
    }

    private void onNak(ByteBuf buffer) {
        this.checkForClosed();

        RakNetUtils.readIntRangesToQueue(buffer, this.incomingNaks);
    }

    private void onConnectedPing(ByteBuf buffer) {
        long pingTime = buffer.readLong();

        this.sendConnectedPong(pingTime);
    }

    private void onConnectedPong(ByteBuf buffer) {
        long pingTime = buffer.readLong();

        if (this.currentPingTime == pingTime) {
            this.lastPingTime = this.currentPingTime;
            this.lastPongTime = System.currentTimeMillis();
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

        buffer.writeByte(RakNetConstants.ID_CONNECTED_PING);
        buffer.writeLong(pingTime);

        this.send(buffer, RakNetPriority.IMMEDIATE, RakNetReliability.RELIABLE);

        this.currentPingTime = pingTime;
    }

    private void sendConnectedPong(long pingTime) {
        ByteBuf buffer = this.allocateBuffer(17);

        buffer.writeByte(RakNetConstants.ID_CONNECTED_PONG);
        buffer.writeLong(pingTime);
        buffer.writeLong(System.currentTimeMillis());

        this.send(buffer, RakNetPriority.IMMEDIATE, RakNetReliability.RELIABLE);
    }

    private void sendDisconnectionNotification() {
        ByteBuf buffer = this.allocateBuffer(1);

        buffer.writeByte(RakNetConstants.ID_DISCONNECTION_NOTIFICATION);

        this.send(buffer, RakNetPriority.IMMEDIATE, RakNetReliability.RELIABLE_ORDERED);
    }

    private void sendDetectLostConnection() {
        ByteBuf buffer = this.allocateBuffer(1);
        buffer.writeByte(RakNetConstants.ID_DETECT_LOST_CONNECTION);

        this.send(buffer, RakNetPriority.IMMEDIATE);
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
