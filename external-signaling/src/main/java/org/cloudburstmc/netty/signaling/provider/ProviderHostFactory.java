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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import org.cloudburstmc.netty.signaling.ProviderTransport;

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
