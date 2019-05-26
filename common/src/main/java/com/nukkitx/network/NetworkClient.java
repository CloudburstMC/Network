package com.nukkitx.network;

import java.net.InetSocketAddress;

public interface NetworkClient<S extends SessionConnection> extends NetworkInterface {

    S connect(InetSocketAddress address);
}
