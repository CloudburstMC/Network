package com.nukkitx.network;

import java.net.InetSocketAddress;

public interface NetworkServer<S extends SessionConnection> extends NetworkInterface {

    S getSession(InetSocketAddress address);
}
