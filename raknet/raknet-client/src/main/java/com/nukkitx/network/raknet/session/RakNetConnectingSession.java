package com.nukkitx.network.raknet.session;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.raknet.RakNet;
import com.nukkitx.network.raknet.RakNetUtil;
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
    private final RakNet<T> rakNet;
    private short mtu = RakNetUtil.MAXIMUM_MTU_SIZE;
    private ConnectionState state;

    public RakNetConnectingSession(InetSocketAddress localAddress, InetSocketAddress remoteAddress, Channel channel, RakNet<T> rakNet, CompletableFuture<T> connectedFuture, short mtu) {
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.channel = channel;
        this.rakNet = rakNet;
        this.connectedFuture = connectedFuture;
    }

    public void setConnectionState(ConnectionState state) {
        if (this.state != null) {
            if (state == ConnectionState.CONNECTED) {
                RakNetSession connection = new RakNetSession(remoteAddress, mtu, channel, rakNet);
                T session = rakNet.getSessionFactory().createSession(connection);
                rakNet.getSessionManager().add((InetSocketAddress) channel.localAddress(), session);
                connectedFuture.complete(session);

            } else {
                String errorMessage;
                if (state == ConnectionState.BANNED) {
                    errorMessage = "Banned from connecting";
                } else if (state == ConnectionState.NO_FREE_INCOMMING_CONNECTIONS) {
                    errorMessage = "No connection available. Please try again later";
                } else {
                    errorMessage = "Unable to connect to the server";
                }
                connectedFuture.completeExceptionally(new ConnectException(errorMessage));
            }
        }
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
        NO_FREE_INCOMMING_CONNECTIONS,
    }
}
