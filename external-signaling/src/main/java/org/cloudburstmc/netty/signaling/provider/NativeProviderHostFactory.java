/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.signaling.provider;

import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signaling.admission.AdmissionGate;
import io.netty.bootstrap.ServerBootstrap;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;
import org.cloudburstmc.netty.signaling.admission.NativeProviderTransport;

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
            if (directory == null || directory.isBlank()) {
                throw new IllegalArgumentException("Provider stateDirectory required");
            }

            List<InetSocketAddress> external = new ArrayList<>();
            for (var value : JsonParser.parseString(options.getOrDefault("advertisedEndpoints", "[]")).getAsJsonArray()) {
                var address = value.getAsJsonObject();
                external.add(new InetSocketAddress(EndpointAddress.parse(address.get("address").getAsString()), address.get("port").getAsInt()));
            }

            boolean localDevelopment = Boolean.parseBoolean(options.getOrDefault("localDevelopment", "false"));
            ProviderEndpoint endpoint = ProviderEndpoint.resolve(udpBind, external, localDevelopment);
            var identity = ProviderHostIdentity.ensure(Path.of(directory));

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
