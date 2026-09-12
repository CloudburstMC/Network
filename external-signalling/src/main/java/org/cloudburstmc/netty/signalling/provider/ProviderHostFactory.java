package org.cloudburstmc.netty.signalling.provider;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import org.cloudburstmc.netty.signalling.ProviderTransport;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Supplied by the native admission integration; the extension owns the Bedrock child pipeline.
 */
public interface ProviderHostFactory {
    CompletionStage<Host> open(ServerBootstrap bootstrap, InetSocketAddress udpBind, Map<String, String> options);

    record Host(ProviderTransport transport, Channel channel, List<String> warnings) {
    }
}
