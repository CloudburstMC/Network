package com.nukkitx.network.raknet.session;

import com.nukkitx.network.NetworkSession;
import com.nukkitx.network.raknet.RakNetClient;
import com.nukkitx.network.raknet.RakNetUtil;
import com.nukkitx.network.util.Preconditions;
import io.netty.channel.Channel;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

@Getter
public class RakNetConnectingSession<T extends NetworkSession<RakNetSession>> {
    private final InetSocketAddress localAddress;
    private final InetSocketAddress remoteAddress;
    @Getter(AccessLevel.NONE)
    private final CompletableFuture<T> connectedFuture;
    private final Channel channel;
    private final RakNetClient<T> rakNet;
    @Setter
    private int mtu = RakNetUtil.MAXIMUM_MTU_SIZE;
    @Setter
    private long remoteId;
    private ConnectionState state = null;
    private T session;
    private boolean closed = false;

    public RakNetConnectingSession(InetSocketAddress localAddress, InetSocketAddress remoteAddress, Channel channel, RakNetClient<T> rakNet, CompletableFuture<T> connectedFuture) {
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.channel = channel;
        this.rakNet = rakNet;
        this.connectedFuture = connectedFuture;
    }

    public RakNetSession createSession(ConnectionState state) {
        checkForClosed();
        Preconditions.checkState(this.state == null, "State has already been set");
        this.state = ConnectionState.SESSION_CREATED;
        RakNetSession connection = new ClientRakNetSession(remoteAddress, localAddress, mtu, channel, rakNet, remoteId);
        session = rakNet.getSessionFactory().createSession(connection);
        rakNet.getSessionManager().add(localAddress, session);
        return connection;
    }

    public void complete() {
        checkForClosed();
        Preconditions.checkState(this.state == ConnectionState.SESSION_CREATED, "Incorrect state to complete");
        this.state = ConnectionState.CONNECTED;
        connectedFuture.complete(session);
        close();
    }

    public void completeExceptionally(ConnectionState state) {
        checkForClosed();
        Preconditions.checkState(this.state == null, "State has already been set");
        String errorMessage;
        if (state == ConnectionState.BANNED) {
            errorMessage = "Banned from connecting";
        } else if (state == ConnectionState.NO_FREE_INCOMING_CONNECTIONS) {
            errorMessage = "No connection available. Please try again later";
        } else {
            errorMessage = "Unable to connect to the server";
        }
        connectedFuture.completeExceptionally(new ConnectException(errorMessage));
        close();
    }

    private void close() {
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
        SESSION_CREATED,
        CONNECTED,
        BANNED,
        NO_FREE_INCOMING_CONNECTIONS,
    }
}
