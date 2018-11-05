package com.nukkitx.network;

import com.nukkitx.network.util.Preconditions;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class SessionManager<T extends NetworkSession> {
    protected final ConcurrentMap<InetSocketAddress, T> sessions = new ConcurrentHashMap<>();
    protected final Executor executor;

    public SessionManager(Executor executor) {
        this.executor = executor;
    }

    public SessionManager() {
        this.executor = Executors.newSingleThreadExecutor();
    }

    public final boolean add(InetSocketAddress address, T session) {
        Preconditions.checkNotNull(address, "address");
        Preconditions.checkNotNull(session, "session");

        boolean added = sessions.putIfAbsent(address, session) == null;

        if (added) {
            onAddSession(session);
        }
        return added;
    }

    protected void onAddSession(T session) {
    }

    public final boolean remove(T session) {
        Preconditions.checkNotNull(session, "session");
        boolean removed = sessions.values().remove(session);

        if (removed) {
            onRemoveSession(session);
        }
        return removed;
    }

    protected void onRemoveSession(T session) {
    }

    public final T get(InetSocketAddress address) {
        return sessions.get(address);
    }

    public final Collection<T> all() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    public final int getCount() {
        return sessions.size();
    }
}
