/*
 * Copyright 2022 CloudburstMC
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

package org.cloudburstmc.netty.channel.raknet;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.PromiseCombiner;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.netty.channel.proxy.ProxyChannel;
import org.cloudburstmc.netty.channel.raknet.config.DefaultRakServerConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.config.RakServerChannelConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakServerCookieMode;
import org.cloudburstmc.netty.handler.codec.raknet.common.UnconnectedPongEncoder;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerOfflineHandler;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerRateLimiter;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerRouteHandler;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerTailHandler;
import org.cloudburstmc.netty.util.BedrockGuardRegistrationClient;
import org.cloudburstmc.netty.util.RakUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class RakServerChannel extends ProxyChannel<DatagramChannel> implements ServerChannel {

    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakServerChannel.class);

    private final RakServerChannelConfig config;
    private final Map<SocketAddress, RakChildChannel> childChannelMap = new ConcurrentHashMap<>();
    private final Consumer<RakChannel> childConsumer;
    private volatile boolean filterProtectionRegistered;

    public RakServerChannel(DatagramChannel channel) {
        this(channel, null);
    }

    public RakServerChannel(DatagramChannel channel, Consumer<RakChannel> childConsumer) {
        super(channel);
        this.childConsumer = childConsumer;
        this.config = new DefaultRakServerConfig(this);
        this.initPipeline();

        channel.closeFuture().addListener(future -> {
            if (!future.isSuccess()) {
                log.warn("RakServerChannel closed unsuccessfully", future.cause());
            } else if (future.cause() != null) {
                log.warn("RakServerChannel closed with cause", future.cause());
            }
        });
    }

    protected void initPipeline() {
        this.pipeline().addLast(UnconnectedPongEncoder.NAME, UnconnectedPongEncoder.INSTANCE);
        if (this.config().getPacketLimit() > 0) { // No point in enabling this.
            this.pipeline().addLast(RakServerRateLimiter.NAME, new RakServerRateLimiter(this));
        }
        this.pipeline().addLast(RakServerOfflineHandler.NAME, new RakServerOfflineHandler(this));
        this.pipeline().addLast(RakServerRouteHandler.NAME, new RakServerRouteHandler(this));
        this.pipeline().addLast(RakServerTailHandler.NAME, RakServerTailHandler.INSTANCE);
    }

    /**
     * Create new child channel assigned to remote address.
     *
     * @param address         remote address of new connection.
     * @param protocolVersion RakNet protocol version from the handshake cookie, or 0 if not available.
     * @return RakChildChannel instance of new channel, or {@code null} if a non-replaceable channel already exists.
     */
    public RakChildChannel createChildChannel(InetSocketAddress address, InetSocketAddress localAddress, long clientGuid, int mtu, int protocolVersion) {
        RakChildChannel existingChannel = this.childChannelMap.get(address);
        if (this.config().getCookieMode() != RakServerCookieMode.INVALID &&
                this.config().getCookieMode() != RakServerCookieMode.OFF && existingChannel != null) {
            // We know this player is coming from this IP address due to the cookie, so we can safely close the existing channel.
            existingChannel.close();
        } else if (existingChannel != null) {
            // Could be spoofed, so we don't close the existing channel.
            return null;
        }

        RakChildChannel channel = new RakChildChannel(address, localAddress, this, clientGuid, mtu, childConsumer);
        channel.closeFuture().addListener((GenericFutureListener<ChannelFuture>) this::onChildClosed);
        // Set before fireChannelRead because initChannel runs async on the child worker thread.
        if (protocolVersion != 0) {
            channel.config().setOption(RakChannelOption.RAK_PROTOCOL_VERSION, protocolVersion);
        }
        // Fire channel thought ServerBootstrap,
        // register to eventLoop, assign default options and attributes
        this.pipeline().fireChannelRead(channel).fireChannelReadComplete();
        this.childChannelMap.put(address, channel);

        if (this.config().getMetrics() != null) {
            this.config().getMetrics().channelOpen(address);
        }
        return channel;
    }

    public RakChildChannel getChildChannel(SocketAddress address) {
        return this.childChannelMap.get(address);
    }

    private void onChildClosed(ChannelFuture channelFuture) {
        RakChildChannel channel = (RakChildChannel) channelFuture.channel();
        this.childChannelMap.remove(channel.remoteAddress());

        if (this.config().getMetrics() != null) {
            this.config().getMetrics().channelClose(channel.remoteAddress());
        }

        channel.rakPipeline().fireChannelInactive();
        channel.rakPipeline().fireChannelUnregistered();
        // Need to use reflection to destroy pipeline because
        // DefaultChannelPipeline.destroy() is only called when channel.isOpen() is false,
        // but the method is called on parent channel, and there is no other way to destroy pipeline.
        RakUtils.destroyChannelPipeline(channel.rakPipeline());
    }

    @Override
    public void onCloseTriggered(ChannelPromise promise) {
        if (log.isTraceEnabled()) {
            log.trace("Closing RakServerChannel: {}", Thread.currentThread().getName(), new Throwable());
        }
        try {
            this.unregisterFilterProtectionIfNeeded();
        } catch (IOException e) {
            log.warn("Failed to unregister bedrock-guard protection for {}", this.localAddress(), e);
        }
        PromiseCombiner combiner = new PromiseCombiner(this.eventLoop());
        this.childChannelMap.values().forEach(channel -> combiner.add(channel.close()));

        ChannelPromise combinedPromise = this.newPromise();
        combinedPromise.addListener(future -> super.onCloseTriggered(promise));
        combiner.finish(combinedPromise);
    }

    public boolean tryBlockAddress(InetAddress address, long time, TimeUnit unit) {
        RakServerRateLimiter rateLimiter = this.pipeline().get(RakServerRateLimiter.class);
        if (rateLimiter != null) {
            return rateLimiter.blockAddress(address, time, unit);
        }
        return false;
    }

    @Override
    public RakServerChannelConfig config() {
        return this.config;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return this.bind(localAddress, this.newPromise());
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        ChannelPromise bindPromise = this.newPromise();
        bindPromise.addListener(future -> {
            if (!future.isSuccess()) {
                promise.tryFailure(future.cause());
                return;
            }

            this.registerFilterProtectionIfConfigured();
            promise.trySuccess();
        });
        super.bind(localAddress, bindPromise);
        return promise;
    }

    private void registerFilterProtectionIfConfigured() {
        Path socketPath = this.config.getFilterRegistrationSocketPath();
        if (socketPath == null || this.filterProtectionRegistered) {
            return;
        }

        InetSocketAddress localAddress = this.localAddress();
        if (localAddress == null) {
            log.error("bedrock-guard integration requires a bound local address; falling back to ACTIVE cookie mode");
            this.config.clearExternalFilterProtection();
            return;
        }

        try {
            BedrockGuardRegistrationClient.registerListener(socketPath, localAddress, this.config.getCookieSecret());
            this.config.applyExternalFilterProtection();
            this.filterProtectionRegistered = true;
            log.info("Registered bedrock-guard protection for {} via {} and switched cookie mode to OFFLOADED_PSK", localAddress, socketPath);
        } catch (IOException e) {
            this.config.clearExternalFilterProtection();
            this.filterProtectionRegistered = false;
            log.error("Failed to register bedrock-guard protection for {} via {}; falling back to ACTIVE cookie mode", localAddress, socketPath, e);
        }
    }

    private void unregisterFilterProtectionIfNeeded() throws IOException {
        Path socketPath = this.config.getFilterRegistrationSocketPath();
        if (socketPath == null || !this.filterProtectionRegistered) {
            return;
        }

        InetSocketAddress localAddress = this.localAddress();
        if (localAddress == null) {
            this.filterProtectionRegistered = false;
            return;
        }

        BedrockGuardRegistrationClient.unregisterListener(socketPath, localAddress);
        this.filterProtectionRegistered = false;
        log.info("Unregistered bedrock-guard protection for {} via {}", localAddress, socketPath);
    }
}
