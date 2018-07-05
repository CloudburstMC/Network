package com.nukkitx.network;

import io.netty.buffer.ByteBuf;
import lombok.experimental.UtilityClass;

import java.net.*;

@UtilityClass
public class NetworkUtils {
    public static final int AF_INET6 = 23;

    public static InetSocketAddress readAddress(ByteBuf buffer) {
        short type = buffer.readUnsignedByte();
        byte[] address;
        int port;
        if (type == 4) {
            address = new byte[4];
            buffer.readBytes(address);
            port = buffer.readUnsignedShort();
        } else if (type == 6) {
            buffer.skipBytes(2); // Family, AF_INET6
            port = buffer.readUnsignedShort();
            buffer.skipBytes(4); // Flow information
            address = new byte[16];
            buffer.readBytes(address);
            buffer.readInt(); // Scope ID
        } else {
            throw new UnsupportedOperationException("Unknown Internet Protocol version.");
        }

        try {
            return new InetSocketAddress(InetAddress.getByAddress(address), port);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static void writeAddress(ByteBuf buffer, InetSocketAddress address) {
        if (address.getAddress() instanceof Inet4Address) {
            buffer.writeByte((4 & 0xFF));
            buffer.writeBytes(address.getAddress().getAddress());
            buffer.writeShort(address.getPort());
        } else if (address.getAddress() instanceof Inet6Address) {
            buffer.writeByte((6 & 0xFF));
            buffer.writeShortLE(AF_INET6);
            buffer.writeShort(address.getPort());
            buffer.writeInt(0);
            buffer.writeBytes(address.getAddress().getAddress());
            buffer.writeInt(0);
        } else {
            throw new UnsupportedOperationException("Unknown InetAddress instance");
        }
    }
}
