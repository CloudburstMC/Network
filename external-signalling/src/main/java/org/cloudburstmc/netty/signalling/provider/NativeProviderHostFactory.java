package org.cloudburstmc.netty.signalling.provider;

import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signalling.admission.AdmissionGate;
import io.netty.bootstrap.ServerBootstrap;
import org.cloudburstmc.netty.signalling.admission.EndpointAddress;
import org.cloudburstmc.netty.signalling.admission.NativeProviderTransport;

import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Fixed native endpoint using the extension's existing Bedrock child pipeline.
 */
public final class NativeProviderHostFactory implements ProviderHostFactory {
    @Override
    public CompletionStage<Host> open(ServerBootstrap bootstrap, InetSocketAddress udpBind, Map<String, String> options) {
        try {
            String directory = options.get("stateDirectory");
            if (directory == null || directory.isBlank())
                throw new IllegalArgumentException("Provider stateDirectory required");
            Path state = Path.of(directory);
            List<InetSocketAddress> external = new ArrayList<>();
            for (var value : JsonParser.parseString(options.getOrDefault("advertisedEndpoints", "[]")).getAsJsonArray()) {
                var address = value.getAsJsonObject();
                external.add(new InetSocketAddress(EndpointAddress.parse(address.get("address").getAsString()), address.get("port").getAsInt()));
            }
            boolean localDevelopment = Boolean.parseBoolean(options.getOrDefault("localDevelopment", "false"));
            ProviderEndpoint endpoint = ProviderEndpoint.resolve(udpBind, external, localDevelopment);
            var identity = ProviderHostIdentity.ensure(state);
            return NativeProviderTransport.open(bootstrap, endpoint.bind(), () -> {
                                try {
                                    return ProviderEndpoint.resolve(udpBind, external, localDevelopment).advertised();
                                } catch (java.io.IOException unavailable) {
                                    throw new UncheckedIOException(unavailable);
                                }
                            },
                            identity.certificate(), identity.privateKey(), AdmissionGate.Limits.defaults())
                    .thenApply(transport -> new Host(transport, transport.channel(), endpoint.warnings()));
        } catch (Exception invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
    }
}
