package com.nukkitx.network.raknet;

import com.nukkitx.network.*;
import com.nukkitx.network.util.Preconditions;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

public class RakNetServer<T extends NetworkSession> {
    private final AtomicInteger maximumPlayers = new AtomicInteger(20);
    private final long serverId;
    private final RakNetPacketRegistry rakNetPacketRegistry;
    private final SessionFactory<T> factory;
    private final SessionManager<T> sessionManager;
    private final RakNetEventListener rakNetEventListener;
    private final RakNetNetworkListener<T> rakNetNetworkListener;

    private RakNetServer(long serverId, InetSocketAddress address, SessionManager<T> sessionManager,
                         SessionFactory<T> factory, RakNetPacketRegistry registry,
                         RakNetEventListener rakNetEventListener, int maxThreads) {
        this.serverId = serverId;
        this.sessionManager = sessionManager;
        this.factory = factory;
        this.rakNetPacketRegistry = registry;
        this.rakNetEventListener = rakNetEventListener;
        this.rakNetNetworkListener = new RakNetNetworkListener<>(this, address, maxThreads);
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getMaximumPlayers() {
        return maximumPlayers.get();
    }

    public void createSession(SessionConnection<RakNetPacket> connection) {
        T session = factory.createSession(connection);
        sessionManager.add(connection.getRemoteAddress().orElseThrow(() -> new IllegalStateException("Connection has no remote address")), session);
    }

    public void setMaximumPlayer(int maximumPlayers) {
        this.maximumPlayers.set(maximumPlayers);
    }

    public SessionManager<T> getSessionManager() {
        return sessionManager;
    }

    public RakNetEventListener getRakNetEventListener() {
        return rakNetEventListener;
    }

    public long getId() {
        return serverId;
    }

    public RakNetNetworkListener<T> getRakNetNetworkListener() {
        return rakNetNetworkListener;
    }

    public RakNetPacketRegistry getPacketRegistry() {
        return rakNetPacketRegistry;
    }

    public InetSocketAddress getAddress() {
        return rakNetNetworkListener.getAddress();
    }

    public static class Builder<T extends NetworkSession<RakNetPacket>> {
        private final TIntObjectMap<PacketFactory<CustomRakNetPacket<T>>> packets = new TIntObjectHashMap<>();
        private InetSocketAddress address;
        private long serverId = 0;
        private SessionFactory<T> sessionFactory;
        private SessionManager<T> sessionManager;
        private RakNetEventListener eventListener;
        private int maximumThreads = 1;

        public Builder<T> address(InetSocketAddress address) {
            Preconditions.checkNotNull(address, "address");
            this.address = address;
            return this;
        }

        public Builder<T> address(String host, int port) {
            Preconditions.checkNotNull(host, "host");
            Preconditions.checkArgument(port >= 0 && port <= 65535, "Invalid port");
            this.address = new InetSocketAddress(host, port);
            return this;
        }

        public Builder<T> serverId(long serverId) {
            this.serverId = serverId;
            return this;
        }

        public Builder<T> sessionFactory(SessionFactory<T> sessionFactory) {
            this.sessionFactory = Preconditions.checkNotNull(sessionFactory, "sessionFactory");
            return this;
        }

        public Builder<T> sessionManager(SessionManager<T> sessionManager) {
            this.sessionManager = Preconditions.checkNotNull(sessionManager, "sessionManager");
            return this;
        }

        public Builder<T> listener(RakNetEventListener listener) {
            this.eventListener = Preconditions.checkNotNull(listener, "listener");
            return this;
        }

        public Builder<T> maximumThreads(int maximumThreads) {
            Preconditions.checkArgument(maximumThreads > 0, "Maximum threads must be larger than zero");
            this.maximumThreads = maximumThreads;
            return this;
        }

        public Builder<T> packet(PacketFactory<CustomRakNetPacket<T>> factory, int id) {
            Preconditions.checkNotNull(factory, "factory");
            Preconditions.checkArgument(id >= 0 && id < 256, "Invalid ID");
            packets.put(id, factory);
            return this;
        }

        public RakNetServer<T> build() {
            Preconditions.checkNotNull(address, "address");
            Preconditions.checkNotNull(sessionFactory, "sessionFactory");
            Preconditions.checkNotNull(sessionManager, "sessionManager");
            Preconditions.checkNotNull(eventListener, "eventListener");
            RakNetPacketRegistry<T> registry = new RakNetPacketRegistry<>(packets);
            return new RakNetServer<>(serverId, address, sessionManager, sessionFactory, registry, eventListener, maximumThreads);
        }
    }
}
