package org.cloudburstmc.netty.channel.nethernet;

import org.cloudburstmc.netty.channel.nethernet.backend.WebRtcSession;
import org.cloudburstmc.netty.channel.nethernet.config.DefaultNetherChannelConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.function.DoubleConsumer;

/**
 * A server accepted NetherNet connection, backed by a
 * {@link WebRtcSession} from the backend seam. The parent server channel
 * bridges session events (open, close, inbound messages, remote address)
 * into this channel.
 */
public class NetherNetChildChannel extends NetherNetChannel {

    private volatile WebRtcSession session;

    public NetherNetChildChannel(Channel parent, InetSocketAddress remote, InetSocketAddress local) {
        super(parent, remote, local);
        this.config = new DefaultNetherChannelConfig(this);
    }

    /**
     * Attaches the negotiated session. Called once by the server channel
     * right after the backend accepts the offer, before the transport can
     * open.
     */
    public void attachSession(WebRtcSession session) {
        Objects.requireNonNull(session, "session");
        synchronized (this) {
            if (isOpen()) {
                if (this.session != null) {
                    throw new IllegalStateException("A session is already attached");
                }
                this.session = session;
                return;
            }
        }
        session.close();
    }

    @Override
    public EventLoop eventLoop() {
        try {
            return super.eventLoop();
        } catch (IllegalStateException e) {
            // Negotiation can fail before ServerBootstrap registers the child.
            // Its close and close-future listeners still need an executor.
            if (parent() == null) {
                throw e;
            }
            return parent().eventLoop();
        }
    }

    @Override
    protected void sendFramed(ByteBuf framed) {
        WebRtcSession session = this.session;
        if (session != null) {
            session.send(toNioBuffer(framed));
        }
    }

    @Override
    protected void requestRttSample(DoubleConsumer callback) {
        WebRtcSession session = this.session;
        if (session != null) {
            session.requestRtt(callback);
        } else {
            callback.accept(-1);
        }
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new AbstractUnsafe() {
            @Override
            public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
                promise.setFailure(new UnsupportedOperationException("Child channel cannot connect"));
            }
        };
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        throw new UnsupportedOperationException("Child channel cannot be bound");
    }

    @Override
    protected void doClose() throws Exception {
        WebRtcSession session;
        synchronized (this) {
            super.doClose();
            session = this.session;
            this.session = null;
        }
        if (session != null) {
            session.close();
        }
    }
}
