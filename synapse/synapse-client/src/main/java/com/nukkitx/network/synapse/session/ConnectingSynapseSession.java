package com.nukkitx.network.synapse.session;

import io.netty.channel.Channel;
import lombok.Value;
import net.minidev.json.JSONObject;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

@Value
public class ConnectingSynapseSession {
    private final Channel channel;
    private final CompletableFuture<SynapseSession> future;
    private final InetSocketAddress remoteAddress;
    private final JSONObject loginData;
}
