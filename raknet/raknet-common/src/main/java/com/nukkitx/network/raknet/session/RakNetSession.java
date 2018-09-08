package com.nukkitx.network.raknet.session;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.SessionConnection;
import com.nukkitx.network.raknet.RakNet;
import com.nukkitx.network.raknet.RakNetPacket;
import com.nukkitx.network.raknet.datagram.EncapsulatedRakNetPacket;
import com.nukkitx.network.raknet.datagram.RakNetDatagram;
import com.nukkitx.network.raknet.datagram.SentDatagram;
import com.nukkitx.network.raknet.enveloped.AddressedRakNetDatagram;
import com.nukkitx.network.raknet.enveloped.DirectAddressedRakNetPacket;
import com.nukkitx.network.raknet.handler.IRakNetPacketHandler;
import com.nukkitx.network.raknet.handler.RakNetDatagramHandler;
import com.nukkitx.network.raknet.util.IntRange;
import com.nukkitx.network.raknet.util.SplitPacketHelper;
import com.nukkitx.network.util.Preconditions;
import gnu.trove.iterator.TShortObjectIterator;
import gnu.trove.map.TShortObjectMap;
import gnu.trove.map.hash.TShortObjectHashMap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import lombok.Value;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class RakNetSession implements SessionConnection<RakNetPacket> {
    private static final int TIMEOUT_MS = 10000;
    private static final int MAX_SPLIT_COUNT = 32;
    private final InetSocketAddress remoteAddress;
    private final short mtu;
    private final AtomicLong lastTouched = new AtomicLong(System.currentTimeMillis());
    private final TShortObjectMap<SplitPacketHelper> splitPackets = new TShortObjectHashMap<>();
    private final AtomicInteger datagramSequenceGenerator = new AtomicInteger();
    private final AtomicInteger reliabilitySequenceGenerator = new AtomicInteger();
    private final AtomicInteger orderSequenceGenerator = new AtomicInteger();
    private final ConcurrentMap<Integer, SentDatagram> datagramAcks = new ConcurrentHashMap<>();
    private final Channel channel;
    private final RakNet rakNet;
    private boolean closed = false;
    private boolean useOrdering = false;
    private final AtomicInteger readPacketNumber = new AtomicInteger();
    private final Queue<QueuedRakNetPacket> queuedReceivedPackets = new PriorityBlockingQueue<>();

    public RakNetSession(InetSocketAddress remoteAddress, short mtu, Channel channel, RakNet rakNet) {
        this.remoteAddress = remoteAddress;
        this.mtu = mtu;
        this.channel = channel;
        this.rakNet = rakNet;
    }

    public Optional<InetSocketAddress> getRemoteAddress() {
        return Optional.of(remoteAddress);
    }

    public short getMtu() {
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

    public void queueReceivedPacket(int orderedNumber, RakNetPacket packet) {
        queuedReceivedPackets.add(new QueuedRakNetPacket(orderedNumber, packet));
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

        for (RakNetDatagram netDatagram : datagrams) {
            channel.write(new AddressedRakNetDatagram(netDatagram, remoteAddress), channel.voidPromise());
            datagramAcks.put(netDatagram.getDatagramSequenceNumber(), new SentDatagram(netDatagram));
        }
        channel.flush();
    }

    public void sendDirectPackage(RakNetPacket netPackage) {
        checkForClosed();
        channel.writeAndFlush(new DirectAddressedRakNetPacket(netPackage, remoteAddress), channel.voidPromise());
    }

    public void onTick() {
        if (closed) {
            return;
        }

        if (isTimedOut()) {
            close();
        }

        resendStalePackets();
        cleanSplitPackets();
        handleOrderedPackets();
    }

    private void handleOrderedPackets() {
        NetworkSession<RakNetSession> session = rakNet.getSessionManager().get(remoteAddress);

        QueuedRakNetPacket packet;
        while ((packet = queuedReceivedPackets.peek()) != null) {
            if (packet.getOrdered() != readPacketNumber.get()) {
                break;
            }

            // We got the expected packet
            queuedReceivedPackets.remove();
            readPacketNumber.incrementAndGet();

            // TODO: this is really ugly, you need to refactor this
            IRakNetPacketHandler handler = (IRakNetPacketHandler) rakNet.getChannel().pipeline().get("raknetDatagramHandler");
            try {
                handler.onPacket(packet.getPacket(), session);
            } catch (Exception e) {
                rakNet.getChannel().pipeline().fireExceptionCaught(e);
            }
        }
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
                datagram.refreshForResend();
                channel.write(new AddressedRakNetDatagram(datagram.getDatagram(), remoteAddress), channel.voidPromise());
            }
        }
        channel.flush();
    }

    public void close() {
        checkForClosed();
        closed = true;

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

    public boolean isTimedOut() {
        return System.currentTimeMillis() - lastTouched.get() >= TIMEOUT_MS;
    }

    void checkForClosed() {
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

    @Value
    private class QueuedRakNetPacket implements Comparable<QueuedRakNetPacket> {
        private final int ordered;
        private final RakNetPacket packet;

        @Override
        public int compareTo(QueuedRakNetPacket o) {
            return Integer.compare(ordered, o.ordered);
        }
    }
}
