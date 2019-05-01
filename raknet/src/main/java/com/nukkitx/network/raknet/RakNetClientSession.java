package com.nukkitx.network.raknet;

import com.nukkitx.network.NetworkUtils;
import com.nukkitx.network.util.DisconnectReason;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

import javax.annotation.ParametersAreNonnullByDefault;
import java.net.InetSocketAddress;

@ParametersAreNonnullByDefault
public class RakNetClientSession extends RakNetSession {
    private int connectionAttempts;
    private long nextConnectionAttempt;

    RakNetClientSession(InetSocketAddress address, Channel channel, RakNet rakNet, int mtu) {
        super(address, channel, rakNet, mtu);
    }

    @Override
    protected void onPacket(ByteBuf buffer) {
        int packetId = buffer.readUnsignedByte();

        switch (packetId) {
            case RakNetConstants.ID_OPEN_CONNECTION_REPLY_1:
                this.onOpenConnectionReply1(buffer);
                break;
            case RakNetConstants.ID_OPEN_CONNECTION_REPLY_2:
                this.onOpenConnectionReply2(buffer);
                break;
            case RakNetConstants.ID_CONNECTION_REQUEST_ACCEPTED:
                this.onConnectionRequestAccepted(buffer);
                break;
            case RakNetConstants.ID_CONNECTION_REQUEST_FAILED:
                this.close(DisconnectReason.CONNECTION_REQUEST_FAILED);
                break;
            case RakNetConstants.ID_INCOMPATIBLE_PROTOCOL_VERSION:
                this.close(DisconnectReason.INCOMPATIBLE_PROTOCOL_VERSION);
                break;
            case RakNetConstants.ID_ALREADY_CONNECTED:
                this.close(DisconnectReason.ALREADY_CONNECTED);
                break;
            case RakNetConstants.ID_NO_FREE_INCOMING_CONNECTIONS:
                this.close(DisconnectReason.NO_FREE_INCOMING_CONNECTIONS);
                break;
            case RakNetConstants.ID_IP_RECENTLY_CONNECTED:
                this.close(DisconnectReason.IP_RECENTLY_CONNECTED);
                break;
        }
    }

    @Override
    protected void tick() {
        if (this.getState() == RakNetState.UNCONNECTED) {
            if (this.connectionAttempts >= RakNetConstants.MAXIMUM_CONNECTION_ATTEMPTS) {
                this.close(DisconnectReason.TIMED_OUT);
            } else {
                long currentTime = System.currentTimeMillis();
                if (this.nextConnectionAttempt < currentTime) {
                    int mtuDiff = (RakNetConstants.MAXIMUM_MTU_SIZE - RakNetConstants.MINIMUM_MTU_SIZE) / 9;
                    int mtuSize = RakNetConstants.MAXIMUM_MTU_SIZE - (this.connectionAttempts * mtuDiff);
                    if (mtuSize < RakNetConstants.MINIMUM_MTU_SIZE) {
                        mtuSize = RakNetConstants.MINIMUM_MTU_SIZE;
                    }

                    this.sendOpenConnectionRequest1(mtuSize);

                    this.nextConnectionAttempt = currentTime + 1000;
                    this.connectionAttempts++;
                }
            }
        }
    }

    private void onOpenConnectionReply1(ByteBuf buffer) {
        if (this.getState() != RakNetState.UNCONNECTED) {
            return;
        }
        if (!RakNetUtils.verifyUnconnectedMagic(buffer)) {
            return;
        }
        this.guid = buffer.readLong();
        boolean security = buffer.readBoolean();
        this.mtu = buffer.readUnsignedShort();

        if (security) {
            this.close(DisconnectReason.CONNECTION_REQUEST_FAILED);
            return;
        }

        this.setState(RakNetState.INITIALIZING);

        this.sendOpenConnectionRequest2();
    }

    private void onOpenConnectionReply2(ByteBuf buffer) {
        if (this.getState() != RakNetState.INITIALIZING) {
            return;
        }
        if (!RakNetUtils.verifyUnconnectedMagic(buffer)) {
            this.close(DisconnectReason.CONNECTION_REQUEST_FAILED);
            return;
        }

        long guid = buffer.readLong();
        if (this.guid != guid) {
            this.close(DisconnectReason.CONNECTION_REQUEST_FAILED);
            return;
        }
        this.mtu = buffer.readUnsignedShort();
        boolean security = buffer.readBoolean();

        this.initialize();
        this.setState(RakNetState.INITIALIZED);

        this.sendConnectionRequest();
    }

    private void onConnectionRequestAccepted(ByteBuf buffer) {
        InetSocketAddress address = NetworkUtils.readAddress(buffer);
        int systemIndex = buffer.readUnsignedShort();
        for (int i = 0; i < 20; i++) {
            if (buffer.readableBytes() > 16) {
                NetworkUtils.readAddress(buffer);
            } else {
                break;
            }
        }
        long pongTime = buffer.readLong();

        this.sendNewIncomingConnection(pongTime);

        this.setState(RakNetState.CONNECTED);
    }

    private void sendOpenConnectionRequest1(int mtuSize) {
        ByteBuf buffer = this.allocateBuffer(mtuSize);
        buffer.writeByte(RakNetConstants.ID_OPEN_CONNECTION_REQUEST_1);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeByte(this.rakNet.protocolVersion);
        buffer.writerIndex(buffer.writerIndex() + mtuSize - 18);

        this.sendDirect(buffer);
    }

    private void sendOpenConnectionRequest2() {
        ByteBuf buffer = this.allocateBuffer(34);
        buffer.writeByte(RakNetConstants.ID_OPEN_CONNECTION_REQUEST_2);
        RakNetUtils.writeUnconnectedMagic(buffer);
        NetworkUtils.writeAddress(buffer, this.address);
        buffer.writeShort(this.mtu);
        buffer.writeLong(this.rakNet.guid);

        this.sendDirect(buffer);
    }

    private void sendConnectionRequest() {
        ByteBuf buffer = this.allocateBuffer(18);
        try {
            buffer.writeByte(RakNetConstants.ID_CONNECTION_REQUEST);
            buffer.writeLong(this.rakNet.guid);
            buffer.writeLong(System.currentTimeMillis());
            buffer.writeBoolean(false);

            this.send(buffer, RakNetReliability.RELIABLE_ORDERED);
        } finally {
            buffer.release();
        }
    }

    private void sendNewIncomingConnection(long pingTime) {
        boolean ipv6 = this.isIpv6Session();
        ByteBuf buffer = this.allocateBuffer(ipv6 ? 294 : 94);
        try {
            buffer.writeByte(RakNetConstants.ID_NEW_INCOMING_CONNECTION);
            NetworkUtils.writeAddress(buffer, address);
            for (InetSocketAddress address : ipv6 ? RakNetUtils.LOCAL_IP_ADDRESSES_V6 : RakNetUtils.LOCAL_IP_ADDRESSES_V4) {
                NetworkUtils.writeAddress(buffer, address);
            }
            buffer.writeLong(pingTime);
            buffer.writeLong(System.currentTimeMillis());

            this.send(buffer, RakNetReliability.RELIABLE_ORDERED);
        } finally {
            buffer.release();
        }
    }
}
