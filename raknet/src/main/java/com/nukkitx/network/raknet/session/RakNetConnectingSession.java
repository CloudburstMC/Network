package com.nukkitx.network.raknet.session;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.raknet.RakNetClient;
import com.nukkitx.network.raknet.RakNetUtil;
import com.nukkitx.network.util.Preconditions;
import io.netty.channel.Channel;
import lombok.Data;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

@Data
public class RakNetConnectingSession<T extends NetworkSession<RakNetSession>> {
    private final InetSocketAddress localAddress;
    private final InetSocketAddress remoteAddress;
    private final CompletableFuture<T> connectedFuture;
    private final Channel channel;
    private final RakNetClient<T> rakNet;
    private short mtu = RakNetUtil.MAXIMUM_MTU_SIZE;
    private ConnectionState state;
    private boolean closed = false;

    public RakNetConnectingSession(InetSocketAddress localAddress, InetSocketAddress remoteAddress, Channel channel, RakNetClient<T> rakNet, CompletableFuture<T> connectedFuture, short mtu) {
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.channel = channel;
        this.rakNet = rakNet;
        this.connectedFuture = connectedFuture;
    }

    public void setConnectionState(ConnectionState state) {
        if (this.state != null && this.state != state) {
            if (state == ConnectionState.CONNECTED) {
                RakNetSession connection = new ClientRakNetSession(remoteAddress, mtu, channel, rakNet);
                T session = rakNet.getSessionFactory().createSession(connection);
                rakNet.getSessionManager().add((InetSocketAddress) channel.localAddress(), session);
                connectedFuture.complete(session);
            } else {
                String errorMessage;
                if (state == ConnectionState.BANNED) {
                    errorMessage = "Banned from connecting";
                } else if (state == ConnectionState.NO_FREE_INCOMING_CONNECTIONS) {
                    errorMessage = "No connection available. Please try again later";
                } else {
                    errorMessage = "Unable to connect to the server";
                }
                connectedFuture.completeExceptionally(new ConnectException(errorMessage));
            }
        }
    }

    public void close() {
        checkForClosed();
        rakNet.removeConnectingSession(this);
        closed = true;
    }

    private void checkForClosed() {
        Preconditions.checkState(!closed, "Connecting session has already been closed");
    }

    public ConnectionState getState() {
        return state;
    }

    public CompletableFuture<T> getConnectedFuture() {
        return connectedFuture;
    }

    public enum ConnectionState {
        CONNECTED,
        BANNED,
        NO_FREE_INCOMING_CONNECTIONS,
    }
}
