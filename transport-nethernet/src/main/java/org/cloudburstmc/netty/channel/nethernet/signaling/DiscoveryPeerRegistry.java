package org.cloudburstmc.netty.channel.nethernet.signaling;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class DiscoveryPeerRegistry {
    private final int maxPassive;
    private final long idleNanos;
    private final LongSupplier clock;
    private final Map<Long, Peer> peers = new HashMap<>();
    private final LinkedHashMap<Long, Peer> passive = new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<Long, Observation> recentConnections = new LinkedHashMap<>();
    private final Map<Long, Peer> registeredConnections = new HashMap<>();
    private boolean passiveExpiryScheduled;
    private long nextPassiveExpiry;

    DiscoveryPeerRegistry() {
        this(1024, TimeUnit.MINUTES.toNanos(5), System::nanoTime);
    }

    DiscoveryPeerRegistry(int maxPassive, long idleNanos, LongSupplier clock) {
        if (maxPassive <= 0 || idleNanos <= 0) {
            throw new IllegalArgumentException("Peer capacity and idle timeout must be positive");
        }
        this.maxPassive = maxPassive;
        this.idleNanos = idleNanos;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    synchronized void remember(long peerId, InetSocketAddress address) {
        long now = clock.getAsLong();
        expire(now);
        remember(peerId, address, now);
        trimPassive();
    }

    synchronized void rememberSignal(long connectionId, long peerId, InetSocketAddress address) {
        long now = clock.getAsLong();
        expire(now);
        Peer peer = remember(peerId, address, now);
        if (registeredConnections.containsKey(connectionId)) {
            Peer previous = registeredConnections.put(connectionId, peer);
            if (previous != peer) {
                if (previous != null) {
                    unpin(previous, now);
                }
                pin(peer);
            }
        } else {
            // Offers arrive before their callbacks register the connection's signal handler.
            recentConnections.remove(connectionId);
            recentConnections.put(connectionId, new Observation(peerId, address, now));
            while (recentConnections.size() > maxPassive) {
                recentConnections.pollFirstEntry();
            }
        }
        trimPassive();
    }

    synchronized void register(long connectionId) {
        long now = clock.getAsLong();
        expire(now);
        if (registeredConnections.containsKey(connectionId)) {
            return;
        }
        Observation observation = recentConnections.remove(connectionId);
        Peer peer = null;
        if (observation != null) {
            peer = peers.get(observation.peerId);
            if (peer == null) {
                peer = remember(observation.peerId, observation.address, observation.time);
            }
            pin(peer);
        }
        registeredConnections.put(connectionId, peer);
    }

    synchronized void unregister(long connectionId) {
        long now = clock.getAsLong();
        expire(now);
        recentConnections.remove(connectionId);
        Peer peer = registeredConnections.remove(connectionId);
        if (peer != null) {
            unpin(peer, now);
            trimPassive();
        }
    }

    synchronized InetSocketAddress get(long peerId) {
        long now = clock.getAsLong();
        expire(now);
        Peer peer = peers.get(peerId);
        if (peer != null) {
            peer.lastUsed = now;
            if (peer.pins == 0) {
                passive.get(peerId);
            }
        }
        return peer == null ? null : peer.address;
    }

    synchronized void clear() {
        registeredConnections.clear();
        recentConnections.clear();
        passive.clear();
        peers.clear();
        passiveExpiryScheduled = false;
    }

    synchronized int passiveSize() {
        expire(clock.getAsLong());
        return passive.size();
    }

    synchronized int recentConnectionCount() {
        expire(clock.getAsLong());
        return recentConnections.size();
    }

    private Peer remember(long id, InetSocketAddress address, long now) {
        Peer peer = peers.computeIfAbsent(id, Peer::new);
        peer.address = address;
        peer.lastUsed = now;
        if (peer.pins == 0) {
            passive.put(id, peer);
            considerExpiry(peer.lastUsed);
        }
        return peer;
    }

    private void pin(Peer peer) {
        peer.pins++;
        passive.remove(peer.id);
    }

    private void unpin(Peer peer, long now) {
        if (--peer.pins == 0) {
            if (now - peer.lastUsed >= idleNanos) {
                peers.remove(peer.id);
            } else {
                passive.put(peer.id, peer);
                considerExpiry(peer.lastUsed);
            }
        }
    }

    private void expire(long now) {
        if (passiveExpiryScheduled && now - nextPassiveExpiry >= 0) {
            passiveExpiryScheduled = false;
            Iterator<Peer> iterator = passive.values().iterator();
            while (iterator.hasNext()) {
                Peer peer = iterator.next();
                if (now - peer.lastUsed >= idleNanos) {
                    iterator.remove();
                    peers.remove(peer.id);
                } else {
                    considerExpiry(peer.lastUsed);
                }
            }
        }
        Iterator<Observation> observations = recentConnections.values().iterator();
        while (observations.hasNext()) {
            if (now - observations.next().time < idleNanos) {
                break;
            }
            observations.remove();
        }
    }

    private void considerExpiry(long lastUsed) {
        long expiry = lastUsed + idleNanos;
        if (!passiveExpiryScheduled || expiry - nextPassiveExpiry < 0) {
            nextPassiveExpiry = expiry;
            passiveExpiryScheduled = true;
        }
    }

    private void trimPassive() {
        while (passive.size() > maxPassive) {
            peers.remove(passive.pollFirstEntry().getKey());
        }
    }

    private static final class Peer {
        private final long id;
        private InetSocketAddress address;
        private long lastUsed;
        private int pins;

        private Peer(long id) {
            this.id = id;
        }
    }

    private record Observation(long peerId, InetSocketAddress address, long time) { }
}
