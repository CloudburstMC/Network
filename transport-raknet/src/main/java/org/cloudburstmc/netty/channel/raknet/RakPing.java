package org.cloudburstmc.netty.channel.raknet;

import io.netty.util.AbstractReferenceCounted;
import io.netty.util.Recycler;
import io.netty.util.ReferenceCounted;

import java.net.InetSocketAddress;

public class RakPing extends AbstractReferenceCounted {

    private static final Recycler<RakPing> RECYCLER = new Recycler<RakPing>() {
        public RakPing newObject(Recycler.Handle<RakPing> handle) {
            return new RakPing(handle);
        }
    };

    private final Recycler.Handle<RakPing> handle;
    private long pingTime;
    private InetSocketAddress sender;

    private RakPing(Recycler.Handle<RakPing> handle) {
        this.handle = handle;
    }

    public static RakPing newInstance(long pingTime, InetSocketAddress sender) {
        RakPing ping = RECYCLER.get();
        ping.pingTime = pingTime;
        ping.sender = sender;
        return ping;
    }

    public long getPingTime() {
        return this.pingTime;
    }


    public InetSocketAddress getSender() {
        return this.sender;
    }

    @Override
    protected void deallocate() {
        this.setRefCnt(1);
        this.handle.recycle(this);
    }

    @Override
    public ReferenceCounted touch(Object hint) {
        return null;
    }
}
