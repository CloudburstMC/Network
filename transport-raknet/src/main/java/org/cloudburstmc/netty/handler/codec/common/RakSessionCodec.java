package org.cloudburstmc.netty.handler.codec.common;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.netty.*;
import org.cloudburstmc.netty.channel.raknet.*;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.packet.*;
import org.cloudburstmc.netty.util.*;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.cloudburstmc.netty.RakNetConstants.*;

public abstract class RakSessionCodec extends MessageToMessageCodec<RakCodecPacket, RakMessage> {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakSessionCodec.class);
    public static final String NAME = "rak-session-codec";

    private final Channel channel;
    private volatile RakState state = RakState.UNCONNECTED;
    private volatile long lastTouched = System.currentTimeMillis();
    private volatile boolean closed = false;

    // Reliability, Ordering, Sequencing and datagram indexes
    private RakNetSlidingWindow slidingWindow;
    private int splitIndex;
    private int datagramReadIndex;
    private int datagramWriteIndex;
    private int reliabilityReadIndex;
    private int reliabilityWriteIndex;
    private int[] orderReadIndex;
    private int[] orderWriteIndex;
    private int[] sequenceReadIndex;
    private int[] sequenceWriteIndex;

    private RoundRobinArray<SplitPacketHelper> splitPackets;
    private BitQueue reliableDatagramQueue;

    private FastBinaryMinHeap<EncapsulatedPacket> outgoingPackets;
    private long[] outgoingPacketNextWeights;
    private FastBinaryMinHeap<EncapsulatedPacket>[] orderingHeaps;
    private long currentPingTime = -1;
    private long lastPingTime = -1;
    private long lastPongTime = -1;
    private IntObjectMap<RakDatagramPacket> sentDatagrams;
    private Queue<IntRange> incomingAcks;
    private Queue<IntRange> incomingNaks;
    private Queue<IntRange> outgoingAcks;
    private Queue<IntRange> outgoingNaks;
    private long lastMinWeight;

    RakSessionCodec(Channel channel) {
        this.channel = channel;
    }

    private static int getAdjustedMtu(Channel channel) {
        int mtu = channel.attr(RakAttributes.RAK_MTU).get();
        return (mtu - UDP_HEADER_SIZE) - (((InetSocketAddress) channel.remoteAddress()).getAddress() instanceof Inet6Address ? 40 : 20);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        int mtu = ctx.channel().config().getOption(RakChannelOption.RAK_MTU);

        this.slidingWindow = new RakNetSlidingWindow(mtu);

        this.outgoingPacketNextWeights = new long[4];
        this.initHeapWeights();

        // TODO: find better name for config option name
        // int maxChannels = ((RakChannelConfig) ctx.channel().config()).getMaxChannels();

        this.orderReadIndex = new int[maxChannels];
        this.orderWriteIndex = new int[maxChannels];
        this.sequenceReadIndex = new int[maxChannels];
        this.sequenceWriteIndex = new int[maxChannels];

        //noinspection unchecked
        this.orderingHeaps = new FastBinaryMinHeap[maxChannels];
        for (int i = 0; i < maxChannels; i++) {
            orderingHeaps[i] = new FastBinaryMinHeap<EncapsulatedPacket>(64);
        }

        this.outgoingPackets = new FastBinaryMinHeap<EncapsulatedPacket>(8);
        this.sentDatagrams = new IntObjectHashMap<RakDatagramPacket>();

        this.incomingAcks = new ArrayDeque<IntRange>();
        this.incomingNaks = new ArrayDeque<IntRange>();
        this.outgoingAcks = new ArrayDeque<IntRange>();
        this.outgoingNaks = new ArrayDeque<IntRange>();

        this.reliableDatagramQueue = new BitQueue(512);

        this.splitPackets = new RoundRobinArray<SplitPacketHelper>(256);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);

        // Perform resource clean up.
        for (SplitPacketHelper helper : this.splitPackets) {
            helper.release();
        }
        this.splitPackets = null;

        for (RakDatagramPacket packet : this.sentDatagrams.values()) {
            packet.release();
        }
        this.sentDatagrams = null;

        FastBinaryMinHeap<EncapsulatedPacket>[] orderingHeaps = this.orderingHeaps;
        this.orderingHeaps = null;
        if (orderingHeaps != null) {
            for (FastBinaryMinHeap<EncapsulatedPacket> orderingHeap : orderingHeaps) {
                EncapsulatedPacket packet;
                while ((packet = orderingHeap.poll()) != null) {
                    packet.release();
                }
                orderingHeap.release();
            }
        }

        FastBinaryMinHeap<EncapsulatedPacket> outgoingPackets = this.outgoingPackets;
        this.outgoingPackets = null;
        if (outgoingPackets != null) {
            EncapsulatedPacket packet;
            while ((packet = outgoingPackets.poll()) != null) {
                packet.release();
            }
            outgoingPackets.release();
        }
    }

    private void initHeapWeights() {
        for (int priorityLevel = 0; priorityLevel < 4; priorityLevel++) {
            this.outgoingPacketNextWeights[priorityLevel] = (1 << priorityLevel) * priorityLevel + priorityLevel;
        }
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, RakMessage rakMessage, List<Object> list) throws Exception {
        EncapsulatedPacket[] packets = this.createEncapsulated(rakMessage);

        if (rakMessage.priority() == RakPriority.IMMEDIATE) {
            this.sendImmediate(packets);
            return;
        }

        long weight = this.getNextWeight(rakMessage.priority());
        if (packets.length == 1) {
            this.outgoingPackets.insert(weight, packets[0]);
        } else {
            this.outgoingPackets.insertSeries(weight, packets);
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, RakCodecPacket packet, List<Object> list) throws Exception {
        if (this.state == null || RakState.INITIALIZED.compareTo(this.state) > 0) {
            return;
        }

        if (packet instanceof RakDatagramPacket) {
            this.handleDatagram(ctx, (RakDatagramPacket) packet);
        } else if (packet instanceof AcknowledgedPacket) {
            this.handleAcknowledged(ctx, (AcknowledgedPacket) packet);
        }
    }

    private void handleDatagram(ChannelHandlerContext ctx, RakDatagramPacket datagram) {
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
            }


            if (encapsulated.split) {
                final EncapsulatedPacket reassembled = this.getReassembledPacket(encapsulated);
                if (reassembled == null) {
                    // Not reassembled
                    continue;
                }
                try {
                    this.checkForOrdered(ctx, reassembled);
                } finally {
                    reassembled.release();
                }
            } else {
                this.checkForOrdered(ctx, encapsulated);
            }
        }
    }

    private void handleAcknowledged(ChannelHandlerContext ctx, AcknowledgedPacket packet) {
        this.checkForClosed();

        Queue<IntRange> queue = packet.isNack() ? this.incomingNaks : this.incomingAcks;
        for (IntRange range : packet.getEntries()) {
            if (range.start > range.end) {
                if (log.isTraceEnabled()) {
                    log.trace("{} sent an IntRange with a start value {} greater than an end value of {}", this.address, range.start, range.end);
                }
                this.disconnect(RakDisconnectReason.BAD_PACKET);
                return;
            }
            queue.offer(range);
        }
    }

    private void checkForOrdered(ChannelHandlerContext ctx, EncapsulatedPacket packet) {
        if (packet.reliability.isOrdered()) {
            this.onOrderedReceived(ctx, packet);
        } else {
            ctx.fireChannelRead(packet);
        }
    }

    private void onOrderedReceived(ChannelHandlerContext ctx, EncapsulatedPacket packet) {
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
        ctx.fireChannelRead(packet);

        EncapsulatedPacket queuedPacket;
        while ((queuedPacket = binaryHeap.peek()) != null) {
            if (queuedPacket.orderingIndex == this.orderReadIndex[packet.orderingChannel]) {
                try {
                    // We got the expected packet
                    binaryHeap.remove();
                    this.orderReadIndex[packet.orderingChannel]++;
                    ctx.fireChannelRead(queuedPacket);
                } finally {
                    queuedPacket.release();
                }
            } else {
                // Found a gap. Wait till we start receive another ordered packet.
                break;
            }
        }
    }

    private long getNextWeight(RakPriority priority) {
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

        SplitPacketHelper helper = this.splitPackets.get(splitPacket.partId);
        if (helper == null) {
            this.splitPackets.set(splitPacket.partId, helper = new SplitPacketHelper(splitPacket.partCount));
        }

        // Try reassembling the packet.
        EncapsulatedPacket result = helper.add(splitPacket, this);
        if (result != null) {
            // Packet reassembled. Remove the helper
            if (this.splitPackets.remove(splitPacket.partId, helper)) {
                helper.release();
            }
        }

        return result;
    }

    private void sendImmediate(EncapsulatedPacket[] packets) {
        long curTime = System.currentTimeMillis();

        for (EncapsulatedPacket packet : packets) {
            RakDatagramPacket datagram = RakDatagramPacket.newInstance();
            datagram.sendTime = curTime;

            if (!datagram.tryAddPacket(packet, this.adjustedMtu)) {
                throw new IllegalArgumentException("Packet too large to fit in MTU (size: " + packet.getSize() +
                        ", MTU: " + this.adjustedMtu + ")");
            }
            this.sendDatagram(datagram, curTime);
        }
        this.channel.flush();
    }


    private final void onTick(long curTime) {
        if (!this.closed) {
            this.tick(curTime);
        }
    }

    protected void tick(long curTime) {
        if (this.isTimedOut(curTime)) {
            this.close(RakDisconnectReason.TIMED_OUT);
            return;
        }

        if (this.state == null || this.state.ordinal() < RakState.INITIALIZED.ordinal()) {
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
                    RakDatagramPacket datagram = this.sentDatagrams.remove(i);
                    if (datagram != null) {
                        datagram.release();
                        this.slidingWindow.onAck(curTime, datagram, this.datagramReadIndex);
                    }
                }
            }
        }

        if (!this.incomingNaks.isEmpty()) {
            this.slidingWindow.onNak();
            IntRange range;
            while ((range = this.incomingNaks.poll()) != null) {
                for (int i = range.start; i <= range.end; i++) {
                    RakDatagramPacket datagram = this.sentDatagrams.remove(i);
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
            AcknowledgedPacket packet = new AcknowledgedPacket();
            packet.setNack(true);
            packet.setEntries(RakNetUtils.createAckEntries(this.outgoingNaks, mtu));
            this.channel.writeAndFlush(packet);
        }

        if (this.slidingWindow.shouldSendAcks(curTime)) {
            while (!this.outgoingAcks.isEmpty()) {
                AcknowledgedPacket packet = new AcknowledgedPacket();
                packet.setNack(false);
                packet.setEntries(RakNetUtils.createAckEntries(this.outgoingNaks, mtu));
                this.channel.writeAndFlush(packet);
                this.slidingWindow.onSendAck();
            }
        }

        int transmissionBandwidth;
        // Send packets that are stale first

        if (!this.sentDatagrams.isEmpty()) {
            transmissionBandwidth = this.slidingWindow.getRetransmissionBandwidth();
            boolean hasResent = false;

            for (RakDatagramPacket datagram : this.sentDatagrams.values()) {
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

        // Now send usual packets
        if (!this.outgoingPackets.isEmpty()) {
            transmissionBandwidth = this.slidingWindow.getTransmissionBandwidth();
            RakDatagramPacket datagram = RakDatagramPacket.newInstance();
            datagram.sendTime = curTime;
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

                    datagram = RakDatagramPacket.newInstance();
                    datagram.sendTime = curTime;

                    Preconditions.checkArgument(datagram.tryAddPacket(packet, this.adjustedMtu),
                            "Packet too large to fit in MTU (size: %s, MTU: %s)",
                            packet.getSize(), this.adjustedMtu);
                }
            }

            if (!datagram.packets.isEmpty()) {
                this.sendDatagram(datagram, curTime);
            }
        }
        this.channel.flush();
    }


    @Override
    public void disconnect() {
        this.disconnect(RakDisconnectReason.DISCONNECTED);
    }

    @Override
    public void disconnect(RakDisconnectReason reason) {
        if (this.closed) {
            return;
        }
        this.sendDisconnectionNotification();
        this.close(reason);
    }

    @Override
    public void close() {
        this.close(RakDisconnectReason.DISCONNECTED);
    }

    @Override
    public void close(RakDisconnectReason reason) {
        this.sessionLock.writeLock().lock();
        try {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.state = RakState.UNCONNECTED;
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

    private EncapsulatedPacket[] createEncapsulated(RakMessage rakMessage) {
        int maxLength = this.adjustedMtu - MAXIMUM_ENCAPSULATED_HEADER_SIZE - RAKNET_DATAGRAM_HEADER_SIZE;

        ByteBuf[] buffers;
        int splitId = 0;
        RakReliability reliability = rakMessage.reliability();
        ByteBuf buffer = rakMessage.content();
        int orderingChannel = rakMessage.channel();

        if (buffer.readableBytes() > maxLength) {
            // Packet requires splitting
            // Adjust reliability
            switch (reliability) {
                case UNRELIABLE:
                    reliability = RakReliability.RELIABLE;
                    break;
                case UNRELIABLE_SEQUENCED:
                    reliability = RakReliability.RELIABLE_SEQUENCED;
                    break;
                case UNRELIABLE_WITH_ACK_RECEIPT:
                    reliability = RakReliability.RELIABLE_WITH_ACK_RECEIPT;
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
            splitId = this.splitIndex++;
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
            EncapsulatedPacket packet = EncapsulatedPacket.newInstance();
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

    /*
        Packet Handlers
     */

    private void sendDatagram(RakDatagramPacket datagram, long time) {
        if (datagram.packets.isEmpty()) {
            throw new IllegalArgumentException("RakNetDatagram with no packets");
        }

        int oldIndex = datagram.sequenceIndex;
        datagram.sequenceIndex = datagramWriteIndexUpdater.getAndIncrement(this);

        for (EncapsulatedPacket packet : datagram.packets) {
            // check if packet is reliable so it can be resent later if a NAK is received.
            if (packet.reliability != RakReliability.UNRELIABLE &&
                    packet.reliability != RakReliability.UNRELIABLE_SEQUENCED) {
                datagram.nextSend = time + this.slidingWindow.getRtoForRetransmission();
                if (oldIndex == -1) {
                    this.slidingWindow.onReliableSend(datagram);
                } else {
                    this.sentDatagrams.remove(oldIndex, datagram);
                }
                this.sentDatagrams.put(datagram.sequenceIndex, datagram.retain()); // Keep for resending
                break;
            }
        }
        this.channel.write(datagram);
    }

    private void onDisconnectionNotification() {
        this.close(RakDisconnectReason.CLOSED_BY_REMOTE_PEER);
    }


    public void recalculatePongTime(long pingTime) {
        if (this.currentPingTime == pingTime) {
            this.lastPingTime = this.currentPingTime;
            this.lastPongTime = System.currentTimeMillis();
        }
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

    public long getPing() {
        return this.lastPongTime - this.lastPingTime;
    }

    public double getRTT() {
        return this.slidingWindow.getRTT();
    }
}
