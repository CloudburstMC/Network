package com.nukkitx.network.raknet;

import com.nukkitx.network.raknet.util.IntRange;
import io.netty.buffer.ByteBuf;
import lombok.experimental.UtilityClass;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Queue;

@UtilityClass
public class RakNetUtils {

    /*
        Packet IDs
     */

    public static final InetSocketAddress LOOPBACK_V4 = new InetSocketAddress(Inet4Address.getLoopbackAddress(), 19132);
    public static final InetSocketAddress LOOPBACK_V6 = new InetSocketAddress(Inet6Address.getLoopbackAddress(), 19132);
    public static final InetSocketAddress[] LOCAL_IP_ADDRESSES_V4 = new InetSocketAddress[20];
    public static final InetSocketAddress[] LOCAL_IP_ADDRESSES_V6 = new InetSocketAddress[20];


    static {

//        Enumeration<NetworkInterface> interfaces;
//        try {
//            interfaces = NetworkInterface.getNetworkInterfaces();
//        } catch (SocketException e) {
//            throw new RuntimeException(e);
//        }
//
//        for (NetworkInterface interfaze = interfaces.nextElement(); interfaces.hasMoreElements();) {
//            //interfaze.
//        }

        LOCAL_IP_ADDRESSES_V4[0] = LOOPBACK_V4;
        LOCAL_IP_ADDRESSES_V6[0] = LOOPBACK_V6;

        for (int i = 1; i < 20; i++) {
            LOCAL_IP_ADDRESSES_V4[i] = new InetSocketAddress("0.0.0.0", 19132);
            LOCAL_IP_ADDRESSES_V6[i] = new InetSocketAddress("::0", 19132);
        }
    }

    public static void readIntRangesToQueue(ByteBuf buffer, Queue<IntRange> queue) {
        int size = buffer.readUnsignedShort();
        for (int i = 0; i < size; i++) {
            boolean singleton = buffer.readBoolean();
            int start = buffer.readUnsignedMediumLE();
            // We don't need the upper limit if it's a singleton
            int end = singleton ? start : buffer.readMediumLE();
            queue.offer(new IntRange(start, end));
        }
    }

    public static void writeIntRanges(ByteBuf buffer, IntRange[] ranges) {
        buffer.writeShort(ranges.length);

        for (IntRange range : ranges) {
            boolean singleton = range.start == range.end;
            buffer.writeBoolean(singleton);
            buffer.writeMediumLE(range.start);
            if (!singleton) {
                buffer.writeMediumLE(range.end);
            }
        }
    }

    public static boolean verifyUnconnectedMagic(ByteBuf buffer) {
        byte[] readMagic = new byte[RakNetConstants.RAKNET_UNCONNECTED_MAGIC.length];
        buffer.readBytes(readMagic);

        return Arrays.equals(readMagic, RakNetConstants.RAKNET_UNCONNECTED_MAGIC);
    }

    public static void writeUnconnectedMagic(ByteBuf buffer) {
        buffer.writeBytes(RakNetConstants.RAKNET_UNCONNECTED_MAGIC);
    }

    public static int clamp(int value, int low, int high) {
        return value < low ? low : value > high ? high : value;
    }

    public static int powerOfTwoCeiling(int value) {
        value--;
        value |= value >> 1;
        value |= value >> 2;
        value |= value >> 4;
        value |= value >> 8;
        value |= value >> 16;
        value++;
        return value;
    }
}
