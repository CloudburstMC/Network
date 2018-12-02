package com.nukkitx.network.raknet.session;

import com.nukkitx.network.SessionConnection;
import com.nukkitx.network.raknet.RakNet;
import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.datagram.EncapsulatedRakNetPacket;
import com.nukkitx.network.raknet.datagram.RakNetDatagram;
import com.nukkitx.network.raknet.datagram.SentDatagram;
import com.nukkitx.network.raknet.enveloped.AddressedRakNetDatagram;
import com.nukkitx.network.raknet.enveloped.DirectAddressedRakNetPacket;
import com.nukkitx.network.raknet.util.IntRange;
import com.nukkitx.network.raknet.util.SplitPacketHelper;
import com.nukkitx.network.util.Preconditions;
import gnu.trove.iterator.TShortObjectIterator;
import gnu.trove.map.TShortObjectMap;
import gnu.trove.map.hash.TShortObjectHashMap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public abstract class RakNetSession implements SessionConnection<RakNetPacket> {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakNetSession.class);
    private static final int TIMEOUT_MS = 30000;
    private static final int MAX_SPLIT_COUNT = 32;
    private final InetSocketAddress remoteAddress;
    @Getter
    private final InetSocketAddress localAddress;
    private final int mtu;
    private final AtomicLong lastTouched = new AtomicLong(System.currentTimeMillis());
    private final TShortObjectMap<SplitPacketHelper> splitPackets = new TShortObjectHashMap<>();
    private final AtomicInteger datagramSequenceGenerator = new AtomicInteger();
    private final AtomicInteger reliabilitySequenceGenerator = new AtomicInteger();
    private final AtomicInteger orderSequenceGenerator = new AtomicInteger();
    private final AtomicInteger orderPacketReceived = new AtomicInteger();
    private final ConcurrentMap<Integer, SentDatagram> datagramAcks = new ConcurrentHashMap<>();
    private final Queue<QueuedRakNetPacket> orderedReceivedQueue = new PriorityBlockingQueue<>();
    private final Channel channel;
    private final RakNet rakNet;
    @Getter
    private final long remoteId;
    private boolean closed = false;
    private boolean useOrdering = false;

    public RakNetSession(InetSocketAddress remoteAddress, InetSocketAddress localAddress, int mtu, Channel channel, RakNet rakNet, long remoteId) {
        this.remoteAddress = remoteAddress;
        this.localAddress = localAddress;
        this.mtu = mtu;
        this.channel = channel;
        this.rakNet = rakNet;
        this.remoteId = remoteId;
    }

    public Optional<InetSocketAddress> getRemoteAddress() {
        return Optional.of(remoteAddress);
    }

    public int getMtu() {
        return mtu;
    }

    public AtomicInteger getDatagramSequenceGenerator() {
        return datagramSequenceGenerator;
    }

    public AtomicInteger getReliabilitySequenceGenerator() {
        return reliabilitySequenceGenerator;
    }

    public AtomicInteger getOrderSequenceGenerator() {
        return orderSequenceGenerator;
    }

    public Optional<ByteBuf> addSplitPacket(EncapsulatedRakNetPacket packet) {
        checkForClosed();

        SplitPacketHelper helper;
        synchronized (splitPackets) {
            // Make sure that we don't exceed a reasonable number of outstanding total split packets.
            if (splitPackets.size() >= MAX_SPLIT_COUNT) {
                if (!splitPackets.containsKey(packet.getPartId())) {
                    throw new IllegalStateException("Too many outstanding split packets");
                }
            }

            helper = splitPackets.get(packet.getPartId());
            if (helper == null) {
                splitPackets.put(packet.getPartId(), helper = new SplitPacketHelper(packet.getPartCount()));
            }

            // Retain the packet so it can be reassembled later.
            packet.retain();

            // Try reassembling the packet.
            Optional<ByteBuf> result = helper.add(packet);
            if (result.isPresent()) {
                splitPackets.remove(packet.getPartId());
            }

            return result;
        }
    }

    public void onAck(List<IntRange> acked) {
        checkForClosed();
        for (IntRange range : acked) {
            for (int i = range.getStart(); i <= range.getEnd(); i++) {
                SentDatagram datagram = datagramAcks.remove(i);
                if (datagram != null) {
                    datagram.tryRelease();
                }
            }
        }
    }

    public void onNak(List<IntRange> acked) {
        checkForClosed();
        for (IntRange range : acked) {
            for (int i = range.getStart(); i <= range.getEnd(); i++) {
                SentDatagram datagram = datagramAcks.get(i);
                if (datagram != null) {
                    log.trace("Resending datagram {} after NAK to {}", datagram.getDatagram().getDatagramSequenceNumber(), remoteAddress);
                    datagram.refreshForResend();
                    channel.write(new AddressedRakNetDatagram(datagram.getDatagram(), remoteAddress), channel.voidPromise());
                }
            }
        }

        channel.flush();
    }

    protected void internalSendRakNetPackage(ByteBuf encoded) {
        List<EncapsulatedRakNetPacket> addressed = EncapsulatedRakNetPacket.encapsulatePackage(encoded, this, useOrdering);
        List<RakNetDatagram> datagrams = new ArrayList<>();
        for (EncapsulatedRakNetPacket packet : addressed) {
            RakNetDatagram datagram = new RakNetDatagram();
            datagram.setDatagramSequenceNumber(datagramSequenceGenerator.getAndIncrement());
            if (!datagram.tryAddPacket(packet, mtu)) {
                throw new IllegalArgumentException("Packet too large to fit in MTU (size: " + packet.totalLength() + ", MTU: " + mtu + ")");
            }
            datagrams.add(datagram.retain()); // retain in case we need to resend it
        }

        for (RakNetDatagram datagram : datagrams) {
            channel.write(new AddressedRakNetDatagram(datagram, remoteAddress), channel.voidPromise());
            datagramAcks.put(datagram.getDatagramSequenceNumber(), new SentDatagram(datagram));
        }
        channel.flush();
    }

    public void sendDirectPacket(RakNetPacket packet) {
        checkForClosed();
        channel.writeAndFlush(new DirectAddressedRakNetPacket(packet, remoteAddress), channel.voidPromise());
    }

    public List<RakNetPacket> onOrderedReceived(int orderNumber, RakNetPacket packet) {
        orderedReceivedQueue.add(new QueuedRakNetPacket(orderNumber, packet));

        List<RakNetPacket> packets = null;

        QueuedRakNetPacket queuedPacket;
        while ((queuedPacket = orderedReceivedQueue.peek()) != null) {
            if (queuedPacket.order != orderPacketReceived.get()) {
                if (packets == null) {
                    return Collections.emptyList();
                }
                break;
            }

            // We got the expected packet
            orderedReceivedQueue.remove();
            orderPacketReceived.incrementAndGet();

            if (packets == null) {
                if (orderedReceivedQueue.isEmpty()) {
                    return Collections.singletonList(packet);
                }
                packets = new ArrayList<>();
            }

            packets.add(queuedPacket.packet);
        }

        return packets;
    }

    public void onTick() {
        if (closed) {
            return;
        }

        if (isTimedOut()) {
            close();
            rakNet.getSessionManager().get(remoteAddress).onTimeout();
        }

        resendStalePackets();
        cleanSplitPackets();
    }

    public void onPingTick() {
    }

    private void cleanSplitPackets() {
        synchronized (splitPackets) {
            TShortObjectIterator<SplitPacketHelper> it = splitPackets.iterator();
            while (it.hasNext()) {
                it.advance();
                SplitPacketHelper sph = it.value();
                if (sph.expired()) {
                    sph.release();
                    it.remove();
                }
            }
        }
    }

    private void resendStalePackets() {
        for (SentDatagram datagram : datagramAcks.values()) {
            if (datagram.isStale()) {
                log.trace("Resending stale datagram {} to {}", datagram.getDatagram().getDatagramSequenceNumber(), remoteAddress);
                datagram.refreshForResend();
                channel.write(new AddressedRakNetDatagram(datagram.getDatagram(), remoteAddress), channel.voidPromise());
            }
        }
        channel.flush();
    }

    public void close() {
        checkForClosed();
        closed = true;
        log.trace("RakNet Session ({} --> {}) closed", localAddress, remoteAddress);

        // Perform resource clean up.
        synchronized (splitPackets) {
            splitPackets.forEachValue(v -> {
                v.release();
                return true;
            });
            splitPackets.clear();
        }

        datagramAcks.values().forEach(SentDatagram::tryRelease);
        datagramAcks.clear();
    }

    @Override
    public void sendPacket(@Nonnull RakNetPacket packet) {
        internalSendRakNetPackage(rakNet.getPacketRegistry().tryEncode(packet));
    }

    public void touch() {
        checkForClosed();
        lastTouched.set(System.currentTimeMillis());
    }

    public boolean isStale() {
        return System.currentTimeMillis() - lastTouched.get() >= 5000;
    }

    public boolean isTimedOut() {
        return System.currentTimeMillis() - lastTouched.get() >= TIMEOUT_MS;
    }

    private void checkForClosed() {
        Preconditions.checkState(!closed, "Session already closed");
    }

    public boolean isClosed() {
        return closed;
    }

    public RakNet getRakNet() {
        return rakNet;
    }

    public Channel getChannel() {
        return channel;
    }

    public boolean isUseOrdering() {
        return useOrdering;
    }

    public void setUseOrdering(boolean useOrdering) {
        this.useOrdering = useOrdering;
    }

    @RequiredArgsConstructor
    private class QueuedRakNetPacket implements Comparable<QueuedRakNetPacket> {
        private final int order;
        private final RakNetPacket packet;

        @Override
        public int compareTo(@Nonnull QueuedRakNetPacket o) {
            return Integer.compare(order, o.order);
        }
    }
}
