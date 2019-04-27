package com.nukkitx.network.raknet;

import lombok.experimental.UtilityClass;

@UtilityClass
public class RakNetConstants {

    public static final byte RAKNET_PROTOCOL_VERSION = 9; // Mojang's version.
    public static final short MINIMUM_MTU_SIZE = 576;
    public static final short MAXIMUM_MTU_SIZE = 1464;
    /**
     * Maximum amount of ordering channels as defined in vanilla RakNet.
     */
    public static final int MAXIMUM_ORDERING_CHANNELS = 16;
    /**
     * Maximum size of an {@link EncapsulatedPacket} header.
     */
    public static final int MAXIMUM_ENCAPSULATED_HEADER_SIZE = 28;
    /**
     * Maximum size of the UDP header.
     */
    public static final int MAXIMUM_UDP_HEADER_SIZE = 23;
    /**
     * Maximum allowed {@link com.nukkitx.network.raknet.util.SplitPacketHelper}s per {@link RakNetSession}.
     */
    public static final int MAXIMUM_SPLIT_COUNT = 32;
    public static final int MAXIMUM_CONNECTION_ATTEMPTS = 10;
    /**
     * Time after {@link RakNetSession} is closed due to no activity.
     */
    public static final int SESSION_TIMEOUT_MS = 30000;
    /**
     * Time after {@link RakNetSession} is refreshed due to no activity.
     */
    public static final int SESSION_STALE_MS = 5000;
    public static final byte FLAG_VALID = (byte) 0b10000000;

    /*
        Flags
     */
    public static final byte FLAG_ACK = (byte) 0b01000000;
    public static final byte FLAG_HAS_B_AND_AS = (byte) 0b00100000;
    public static final byte FLAG_NACK = (byte) 0b00100000;
    public static final byte FLAG_PACKET_PAIR = (byte) 0b00010000;
    public static final byte FLAG_CONTINOUS_SEND = (byte) 0b00001000;
    public static final byte FLAG_NEEDS_B_AND_AS = (byte) 0b00000100;
    /**
     *
     */
    public static final byte ID_CONNECTED_PING = (byte) 0x00;

    /*
        Packet IDs
     */
    public static final byte ID_UNCONNECTED_PING = (byte) 0x01;
    public static final byte ID_UNCONNECTED_PING_OPEN_CONNECTIONS = (byte) 0x02;
    public static final byte ID_CONNECTED_PONG = (byte) 0x03;
    public static final byte ID_DETECT_LOST_CONNECTION = (byte) 0x04;
    public static final byte ID_OPEN_CONNECTION_REQUEST_1 = (byte) 0x05;
    public static final byte ID_OPEN_CONNECTION_REPLY_1 = (byte) 0x06;
    public static final byte ID_OPEN_CONNECTION_REQUEST_2 = (byte) 0x07;
    public static final byte ID_OPEN_CONNECTION_REPLY_2 = (byte) 0x08;
    public static final byte ID_CONNECTION_REQUEST = (byte) 0x09;
    public static final byte ID_CONNECTION_REQUEST_ACCEPTED = (byte) 0x10;
    public static final byte ID_CONNECTION_REQUEST_FAILED = (byte) 0x11;
    public static final byte ID_ALREADY_CONNECTED = (byte) 0x12;
    public static final byte ID_NEW_INCOMING_CONNECTION = (byte) 0x13;
    public static final byte ID_NO_FREE_INCOMING_CONNECTIONS = (byte) 0x14;
    public static final byte ID_DISCONNECTION_NOTIFICATION = (byte) 0x15;
    public static final byte ID_CONNECTION_LOST = (byte) 0x16;
    public static final byte ID_CONNECTION_BANNED = (byte) 0x17;
    public static final byte ID_INCOMPATIBLE_PROTOCOL_VERSION = (byte) 0x19;
    public static final byte ID_IP_RECENTLY_CONNECTED = (byte) 0x1a;
    public static final byte ID_TIMESTAMP = (byte) 0x1b;
    public static final byte ID_UNCONNECTED_PONG = (byte) 0x1c;
    public static final byte ID_ADVERTISE_SYSTEM = (byte) 0x1d;
    public static final byte ID_USER_PACKET_ENUM = (byte) 0x80;
    /**
     * Magic used to identify RakNet packets
     */
    static final byte[] RAKNET_UNCONNECTED_MAGIC = new byte[]{
            0, -1, -1, 0, -2, -2, -2, -2, -3, -3, -3, -3, 18, 52, 86, 120
    };
}
