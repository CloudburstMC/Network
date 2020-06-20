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
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.nukkitx.network.raknet.RakNetConstants.*;

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
    final int protocolVersion;
    final EventLoop eventLoop;
    private int mtu;
    private int adjustedMtu; // Used in datagram calculations
    long guid;
    private volatile RakNetState state = RakNetState.UNCONNECTED;
    private volatile long lastTouched = System.currentTimeMillis();
    volatile boolean closed = false;
    private final ReadWriteLock sessionLock = new ReentrantReadWriteLock(true);

    // Reliability, Ordering, Sequencing and datagram indexes
    private RakNetSlidingWindow slidingWindow;
    private volatile int splitIndex;
    private volatile int datagramReadIndex;
    private volatile int datagramWriteIndex;
    private Lock reliabilityReadLock;
    private int reliabilityReadIndex;
    private volatile int reliabilityWriteIndex;
    private int[] orderReadIndex;
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
    @Getter
    @Setter
    private volatile RakNetSessionListener listener = null;
    private volatile long currentPingTime = -1;
    private volatile long lastPingTime = -1;
    private volatile long lastPongTime = -1;
    private ConcurrentMap<Integer, RakNetDatagram> sentDatagrams;
    private Queue<IntRange> incomingAcks;
    private Queue<IntRange> incomingNaks;
    private Queue<IntRange> outgoingAcks;
    private Queue<IntRange> outgoingNaks;
    private volatile int unackedBytes;
    private volatile long lastMinWeight;

    RakNetSession(InetSocketAddress address, Channel channel, int mtu, int protocolVersion, EventLoop eventLoop) {
        this.address = address;
        this.channel = channel;
        this.setMtu(mtu);
        this.protocolVersion = protocolVersion;
        this.eventLoop = eventLoop;
        // We can reuse this instead of creating a new one each time
        this.voidPromise = channel.voidPromise();
    }

    final void initialize() {
        Preconditions.checkState(this.state == RakNetState.INITIALIZING);

        this.slidingWindow = new RakNetSlidingWindow(this.mtu);

        this.reliableDatagramQueue = new BitQueue(512);
        this.reliabilityReadLock = new ReentrantLock(true);
        this.orderReadIndex = new int[MAXIMUM_ORDERING_CHANNELS];
        this.orderWriteIndex = new AtomicIntegerArray(MAXIMUM_ORDERING_CHANNELS);
        this.sequenceReadIndex = new AtomicIntegerArray(MAXIMUM_ORDERING_CHANNELS);
        this.sequenceWriteIndex = new AtomicIntegerArray(MAXIMUM_ORDERING_CHANNELS);

        //noinspection unchecked
        this.orderingHeaps = new FastBinaryMinHeap[MAXIMUM_ORDERING_CHANNELS];
        this.orderingLock = new ReentrantLock(true);
        this.splitPackets = new RoundRobinArray<>(256);
        this.sentDatagrams = new ConcurrentSkipListMap<>();
        for (int i = 0; i < MAXIMUM_ORDERING_CHANNELS; i++) {
            orderingHeaps[i] = new FastBinaryMinHeap<>(64);
        }

        this.outgoingLock = new ReentrantLock(true);
        this.outgoingPackets = new FastBinaryMinHeap<>(8);

        this.incomingAcks = PlatformDependent.newMpscQueue();
        this.incomingNaks = PlatformDependent.newMpscQueue();
        this.outgoingAcks = PlatformDependent.newMpscQueue();
        this.outgoingNaks = PlatformDependent.newMpscQueue();

        this.outgoingPacketNextWeights = new long[4];
        this.initHeapWeights();
    }

    private void deinitialize() {
        // Perform resource clean up.
        if (this.splitPackets != null) {
            this.splitPackets.forEach(ReferenceCountUtil::release);
        }
        if (this.sentDatagrams != null) {
            this.sentDatagrams.values().forEach(ReferenceCountUtil::release);
        }
        if (this.orderingLock != null) {
            this.orderingLock.lock();
            try {
                FastBinaryMinHeap<EncapsulatedPacket>[] orderingHeaps = this.orderingHeaps;
                this.orderingHeaps = null;
                if (orderingHeaps != null) {
                    for (FastBinaryMinHeap<EncapsulatedPacket> orderingHeap : orderingHeaps) {
                        EncapsulatedPacket packet;
                        while ((packet = orderingHeap.poll()) != null) {
                            packet.release();
                        }
                    }
                }
            } finally {
                this.orderingLock.unlock();
            }
        }

        if (this.outgoingLock != null) {
            this.outgoingLock.lock();
            try {
                FastBinaryMinHeap<EncapsulatedPacket> outgoingPackets = this.outgoingPackets;
                this.outgoingPackets = null;
                if (outgoingPackets != null) {
                    EncapsulatedPacket packet;
                    while ((packet = outgoingPackets.poll()) != null) {
                        packet.release();
                    }
                }
                this.initHeapWeights();
            } finally {
                this.outgoingLock.unlock();
            }
        }
    }

    public InetSocketAddress getAddress() {
        return this.address;
    }

    public int getMtu() {
        return this.mtu;
    }

    void setMtu(int mtu) {
        this.mtu = RakNetUtils.clamp(mtu, MINIMUM_MTU_SIZE, MAXIMUM_MTU_SIZE);
        this.adjustedMtu = (this.mtu - UDP_HEADER_SIZE) - (this.address.getAddress() instanceof Inet6Address ? 40 : 20);
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public long getPing() {
        return this.lastPongTime - this.lastPingTime;
    }

    public double getRTT() {
        return this.slidingWindow.getRTT();
    }

    public ByteBuf allocateBuffer(int capacity) {
        return this.channel.alloc().ioBuffer(capacity);
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
        if (!datagram.sender().equals(this.address) || this.closed) {
            // Somehow we have received a datagram from the wrong peer...
            return;
        }

        this.touch();

        ByteBuf buffer = datagram.content();

        byte potentialFlags = buffer.readByte();

        boolean rakNetDatagram = (potentialFlags & FLAG_VALID) != 0;

        // Potential RakNet datagram
        if (rakNetDatagram) {
            // Block RakNet datagrams if we haven't initialized the session yet.
            if (this.state.ordinal() >= RakNetState.INITIALIZED.ordinal()) {
                if ((potentialFlags & FLAG_ACK) != 0) {
                    this.onAcknowledge(buffer, this.incomingAcks);
                } else if ((potentialFlags & FLAG_NACK) != 0) {
                    this.onAcknowledge(buffer, this.incomingNaks);
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
            case ID_CONNECTED_PING:
                this.onConnectedPing(buffer);
                break;
            case ID_CONNECTED_PONG:
                this.onConnectedPong(buffer);
                break;
            case ID_DISCONNECTION_NOTIFICATION:
                this.onDisconnectionNotification();
                break;
            default:
                buffer.readerIndex(0);
                if (packetId >= ID_USER_PACKET_ENUM) {
                    // Forward to user
                    if (this.listener != null) {
                        this.listener.onEncapsulated(packet);
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
        if (packetId >= ID_USER_PACKET_ENUM) {
            // Forward to user
            if (this.listener != null) {
                this.listener.onDirect(buffer);
            }
        } else {
            this.onPacket(buffer);
        }
    }

    protected abstract void onPacket(ByteBuf buffer);

    private void onRakNetDatagram(ByteBuf buffer) {
        if (this.state == null || RakNetState.INITIALIZED.compareTo(this.state) > 0) {
            return;
        }

        RakNetDatagram datagram = new RakNetDatagram(System.currentTimeMillis());
        datagram.decode(buffer);

        this.slidingWindow.onPacketReceived(datagram.sendTime);

        int prevSequenceIndex = datagramReadIndexUpdater.getAndAccumulate(this, datagram.sequenceIndex,
                (prev, newIndex) -> prev <= newIndex ? newIndex + 1 : prev);
        int missedDatagrams = datagram.sequenceIndex - prevSequenceIndex;

        if (missedDatagrams > 0) {
            this.outgoingNaks.offer(new IntRange(datagram.sequenceIndex - missedDatagrams, datagram.sequenceIndex));
        }

        this.outgoingAcks.offer(new IntRange(datagram.sequenceIndex, datagram.sequenceIndex));

        for (final EncapsulatedPacket encapsulated : datagram.packets) {
            if (encapsulated.reliability.isReliable()) {
                this.reliabilityReadLock.lock();
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
                        if (!this.reliableDatagramQueue.isEmpty()) {
                            this.reliableDatagramQueue.poll();
                        }
                    } else {
                        // Duplicate packet
                        continue;
                    }

                    while (!this.reliableDatagramQueue.isEmpty() && !this.reliableDatagramQueue.peek()) {
                        this.reliableDatagramQueue.poll();
                        ++this.reliabilityReadIndex;
                    }
                } finally {
                    this.reliabilityReadLock.unlock();
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

            if (this.orderReadIndex[packet.orderingChannel] < packet.orderingIndex) {
                // Not next in line so add to queue.
                binaryHeap.insert(packet.orderingIndex, packet.retain());
                return;
            } else if (this.orderReadIndex[packet.orderingChannel] > packet.orderingIndex) {
                // We already have this
                return;
            }
            this.orderReadIndex[packet.orderingChannel]++;

            // Can be handled
            this.onEncapsulatedInternal(packet);

            EncapsulatedPacket queuedPacket;
            while ((queuedPacket = binaryHeap.peek()) != null) {
                if (queuedPacket.orderingIndex == this.orderReadIndex[packet.orderingChannel]) {
                    try {
                        // We got the expected packet
                        binaryHeap.remove();
                        this.orderReadIndex[packet.orderingChannel]++;

                        this.onEncapsulatedInternal(queuedPacket);
                    } finally {
                        queuedPacket.release();
                    }
                } else {
                    // Found a gap. Wait till we start receive another ordered packet.
                    break;
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
        if (this.isTimedOut(curTime)) {
            this.close(DisconnectReason.TIMED_OUT);
            return;
        }

        if (this.state == null || this.state.ordinal() < RakNetState.INITIALIZED.ordinal()) {
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
                    RakNetDatagram datagram = this.sentDatagrams.remove(i);
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
                    RakNetDatagram datagram = this.sentDatagrams.remove(i);
                    if (datagram != null) {
                        if (log.isTraceEnabled()) {
                            log.trace("NAK'ed datagram {} from {}", datagram.sequenceIndex, this.address);
                        }
                        this.sendDatagram(datagram, curTime);
                    }
                }
            }
        }

        // Outgoing queues

        final int mtu = this.adjustedMtu - RAKNET_DATAGRAM_HEADER_SIZE;

        while (!this.outgoingNaks.isEmpty()) {
            ByteBuf buffer = this.allocateBuffer(mtu);
            buffer.writeByte(FLAG_VALID | FLAG_NACK);
            RakNetUtils.writeIntRanges(buffer, this.outgoingNaks, mtu - 1);

            this.sendDirect(buffer);
        }

        if (this.slidingWindow.shouldSendAcks(curTime)) {
            while (!this.outgoingAcks.isEmpty()) {
                ByteBuf buffer = this.allocateBuffer(mtu);
                buffer.writeByte(FLAG_VALID | FLAG_ACK);
                RakNetUtils.writeIntRanges(buffer, this.outgoingAcks, mtu - 1);

                this.sendDirect(buffer);

                this.slidingWindow.onSendAck();
            }
        }

        int transmissionBandwidth;
        // Send packets that are stale first

        if (!this.sentDatagrams.isEmpty()) {
            transmissionBandwidth = this.slidingWindow.getRetransmissionBandwidth(this.unackedBytes);
            boolean hasResent = false;

            for (RakNetDatagram datagram : this.sentDatagrams.values()) {
                if (datagram.nextSend <= curTime) {
                    int size = datagram.getSize();
                    if (transmissionBandwidth < size) {
                        break;
                    }
                    transmissionBandwidth -= size;

                    if (!hasResent) {
                        hasResent = true;
                    }
                    if (log.isTraceEnabled()) {
                        log.trace("Stale datagram {} from {}", datagram.sequenceIndex,
                                this.address);
                    }
                    this.sendDatagram(datagram, curTime);
                }
            }

            if (hasResent) {
                this.slidingWindow.onResend(curTime);
            }
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

                    if (!datagram.tryAddPacket(packet, this.adjustedMtu)) {
                        // Send full datagram
                        this.sendDatagram(datagram, curTime);

                        datagram = new RakNetDatagram(curTime);

                        Preconditions.checkArgument(datagram.tryAddPacket(packet, this.adjustedMtu),
                                "Packet too large to fit in MTU (size: %s, MTU: %s)",
                                packet.getSize(), this.adjustedMtu);
                    }
                }

                if (!datagram.packets.isEmpty()) {
                    this.sendDatagram(datagram, curTime);
                }
            }
        } finally {
            this.outgoingLock.unlock();
        }
        this.channel.flush();
    }

    @Override
    public void disconnect() {
        disconnect(DisconnectReason.DISCONNECTED);
    }

    @Override
    public void disconnect(DisconnectReason reason) {
        if (this.closed) {
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
        this.sessionLock.writeLock().lock();
        try {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.state = RakNetState.UNCONNECTED;
            this.onClose();
            if (log.isTraceEnabled()) {
                log.trace("RakNet Session ({} => {}) closed: {}", this.getRakNet().bindAddress, this.address, reason);
            }

            this.deinitialize();

            if (this.listener != null) {
                this.listener.onDisconnect(reason);
            }
        } finally {
            this.sessionLock.writeLock().unlock();
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
        this.sessionLock.readLock().lock();
        try {
            if (closed || state == null || state.ordinal() < RakNetState.INITIALIZED.ordinal()) {
                // Session is not ready for RakNet datagrams.
                return;
            }
            EncapsulatedPacket[] packets = this.createEncapsulated(buf, priority, reliability, orderingChannel);

            if (priority == RakNetPriority.IMMEDIATE) {
                this.sendImmediate(packets);
                return;
            }

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
        } finally {
            this.sessionLock.readLock().unlock();
            buf.release();
        }
    }

    private void sendImmediate(EncapsulatedPacket[] packets) {
        long curTime = System.currentTimeMillis();

        for (EncapsulatedPacket packet : packets) {
            RakNetDatagram datagram = new RakNetDatagram(curTime);

            if (!datagram.tryAddPacket(packet, this.adjustedMtu)) {
                throw new IllegalArgumentException("Packet too large to fit in MTU (size: " + packet.getSize() +
                        ", MTU: " + this.adjustedMtu + ")");
            }
            this.sendDatagram(datagram, curTime);
        }
        this.channel.flush();
    }

    private EncapsulatedPacket[] createEncapsulated(ByteBuf buffer, RakNetPriority priority, RakNetReliability reliability,
                                                    int orderingChannel) {
        int maxLength = this.adjustedMtu - MAXIMUM_ENCAPSULATED_HEADER_SIZE - RAKNET_DATAGRAM_HEADER_SIZE;

        ByteBuf[] buffers;
        int splitId = 0;

        if (buffer.readableBytes() > maxLength) {
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

            int split = ((buffer.readableBytes() - 1) / maxLength) + 1;
            buffer.retain(split);

            buffers = new ByteBuf[split];
            for (int i = 0; i < split; i++) {
                buffers[i] = buffer.readSlice(Math.min(maxLength, buffer.readableBytes()));
            }
            if (buffer.isReadable()) {
                throw new IllegalStateException("Buffer still has bytes to read!");
            }

            // Allocate split ID
            splitId = splitIndexUpdater.getAndIncrement(this);
        } else {
            buffers = new ByteBuf[]{buffer.readRetainedSlice(buffer.readableBytes())};
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
        EncapsulatedPacket[] packets = new EncapsulatedPacket[buffers.length];
        for (int i = 0, parts = buffers.length; i < parts; i++) {
            EncapsulatedPacket packet = new EncapsulatedPacket();
            packet.buffer = buffers[i];
            packet.orderingChannel = (short) orderingChannel;
            packet.orderingIndex = orderingIndex;
            //packet.setSequenceIndex(sequencingIndex);
            packet.reliability = reliability;
            packet.priority = priority;
            if (reliability.isReliable()) {
                packet.reliabilityIndex = reliabilityWriteIndexUpdater.getAndIncrement(this);
            }

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

    private void sendDatagram(RakNetDatagram datagram, long time) {
        Preconditions.checkArgument(!datagram.packets.isEmpty(), "RakNetDatagram with no packets");
        try {
            int oldIndex = datagram.sequenceIndex;
            datagram.sequenceIndex = datagramWriteIndexUpdater.getAndIncrement(this);

            for (EncapsulatedPacket packet : datagram.packets) {
                // check if packet is reliable so it can be resent later if a NAK is received.
                if (packet.reliability != RakNetReliability.UNRELIABLE &&
                        packet.reliability != RakNetReliability.UNRELIABLE_SEQUENCED) {
                    datagram.nextSend = time + this.slidingWindow.getRtoForRetransmission();
                    if (oldIndex == -1) {
                        unackedBytesUpdater.addAndGet(this, datagram.getSize());
                    } else {
                        this.sentDatagrams.remove(oldIndex, datagram);
                    }
                    this.sentDatagrams.put(datagram.sequenceIndex, datagram.retain()); // Keep for resending
                    break;
                }
            }
            ByteBuf buf = this.allocateBuffer(datagram.getSize());
            Preconditions.checkArgument(buf.writerIndex() < this.adjustedMtu, "Packet length was %s but expected %s", buf.writerIndex(), this.adjustedMtu);
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

    private void onAcknowledge(ByteBuf buffer, Queue<IntRange> queue) {
        this.checkForClosed();

        int size = buffer.readUnsignedShort();
        for (int i = 0; i < size; i++) {
            boolean singleton = buffer.readBoolean();
            int start = buffer.readUnsignedMediumLE();
            // We don't need the upper limit if it's a singleton
            int end = singleton ? start : buffer.readMediumLE();
            if (start > end) {
                if (log.isTraceEnabled()) {
                    log.trace("{} sent an IntRange with a start value {} greater than an end value of {}", this.address,
                            start, end);
                }
                this.disconnect(DisconnectReason.BAD_PACKET);
                return;
            }
            queue.offer(new IntRange(start, end));
        }
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

        buffer.writeByte(ID_CONNECTED_PING);
        buffer.writeLong(pingTime);

        this.send(buffer, RakNetPriority.IMMEDIATE, RakNetReliability.RELIABLE);

        this.currentPingTime = pingTime;
    }

    private void sendConnectedPong(long pingTime) {
        ByteBuf buffer = this.allocateBuffer(17);

        buffer.writeByte(ID_CONNECTED_PONG);
        buffer.writeLong(pingTime);
        buffer.writeLong(System.currentTimeMillis());

        this.send(buffer, RakNetPriority.IMMEDIATE, RakNetReliability.RELIABLE);
    }

    private void sendDisconnectionNotification() {
        ByteBuf buffer = this.allocateBuffer(1);

        buffer.writeByte(ID_DISCONNECTION_NOTIFICATION);

        this.send(buffer, RakNetPriority.IMMEDIATE, RakNetReliability.RELIABLE_ORDERED);
    }

    private void sendDetectLostConnection() {
        ByteBuf buffer = this.allocateBuffer(1);
        buffer.writeByte(ID_DETECT_LOST_CONNECTION);

        this.send(buffer, RakNetPriority.IMMEDIATE);
    }

    private void touch() {
        this.checkForClosed();
        this.lastTouched = System.currentTimeMillis();
    }

    public boolean isStale(long curTime) {
        return curTime - this.lastTouched >= SESSION_STALE_MS;
    }
    
    public boolean isStale() {
        return isStale(System.currentTimeMillis());
    }

    public boolean isTimedOut(long curTime) {
        return curTime - this.lastTouched >= SESSION_TIMEOUT_MS;
    }

    public boolean isTimedOut() {
        return isTimedOut(System.currentTimeMillis());
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

    void setState(@Nullable RakNetState state) {
        if (this.state != state) {
            this.state = state;
            if (this.listener != null) {
                this.listener.onSessionChangeState(this.state);
            }
        }
    }
}
