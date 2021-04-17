package org.cloudburstmc.netty.channel.raknet;

import io.netty.util.AbstractReferenceCounted;
import io.netty.util.Recycler;

public class RakPendingConnection extends AbstractReferenceCounted {

    private static final Recycler<RakPendingConnection> RECYCLER = new Recycler<RakPendingConnection>() {
        public RakPendingConnection newObject(Recycler.Handle<RakPendingConnection> handle) {
            return new RakPendingConnection(handle);
        }
    };

    private final Recycler.Handle<RakPendingConnection> handle;
    private int protocolVersion;

    private RakPendingConnection(Recycler.Handle<RakPendingConnection> handle) {
        this.handle = handle;
    }

    public static RakPendingConnection newInstance(int protocolVersion) {
        RakPendingConnection connection = RECYCLER.get();
        connection.protocolVersion = protocolVersion;
        return connection;
    }

    public int getProtocolVersion() {
        return this.protocolVersion;
    }

    @Override
    protected void deallocate() {
        this.setRefCnt(1);
        this.handle.recycle(this);
    }

    @Override
    public RakPendingConnection touch(Object o) {
        return this;
    }
}
