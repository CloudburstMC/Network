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

package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.SourcedAddress;
import org.cloudburstmc.netty.util.nethernet.IdentityUtils;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.PlayerInfo;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.jose4j.jwt.JwtClaims;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class NetherNetDiscoverySignaling implements NetherNetClientSignaling, NetherNetServerSignaling {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetDiscoverySignaling.class);
    private static final String IDENTITY_ATTRIBUTE = "a=identity:";
    /** Joins whose identity may wait to be checked; more are dropped, so a flood cannot pile up. */
    private static final int MAX_PENDING_VALIDATIONS = 16;
    /** Signals kept for a join while its identity is checked, such as its trickled candidates. */
    private static final int MAX_HELD_SIGNALS = 64;

    private final NetherNetDiscovery discovery;
    private final InetSocketAddress bindAddress;
    private final String localNetworkId;

    // State captured after connect
    private volatile InetSocketAddress remoteAddress;
    private final AtomicReference<String> discoveredServerId = new AtomicReference<>(null);

    private volatile TokenTrust tokenTrust = TokenTrust.MINECRAFT_AUTH;
    private volatile @Nullable OperatorIdentity serverIdentity;
    /** Identity checks run here rather than on the discovery loop, since a trust anchor may fetch keys. */
    private volatile @Nullable ExecutorService validation;

    /**
     * Creates a NetherNetDiscoverySignaling with a random local Network ID and binds to an ephemeral port.
     */
    public NetherNetDiscoverySignaling() {
        this(ThreadLocalRandom.current().nextLong(), new InetSocketAddress(0));
    }

    /**
     * Creates a NetherNetDiscoverySignaling with the specified local Network ID.
     *
     * @param localNetworkId The local Network ID to use.
     */
    public NetherNetDiscoverySignaling(long localNetworkId) {
        this(localNetworkId, new InetSocketAddress(0));
    }

    /**
     * Creates a NetherNetDiscoverySignaling with the specified local Network ID and bind address.
     *
     * @param localNetworkId The local Network ID to use.
     * @param bindAddress    The address to bind the discovery socket to.
     */
    public NetherNetDiscoverySignaling(long localNetworkId, InetSocketAddress bindAddress) {
        this.localNetworkId = Long.toUnsignedString(localNetworkId);
        this.discovery = new NetherNetDiscovery(localNetworkId);
        this.bindAddress = bindAddress;
    }

    @Override
    public String getLocalNetworkId() {
        return this.localNetworkId;
    }

    @Override
    public CompletableFuture<List<IceServerInfo>> connect(SocketAddress remote) {
        CompletableFuture<List<IceServerInfo>> future = new CompletableFuture<>();

        if (!(remote instanceof InetSocketAddress)) {
            future.completeExceptionally(new IllegalArgumentException("Discovery requires InetSocketAddress"));
            return future;
        }

        this.remoteAddress = (InetSocketAddress) remote;

        try {
            if (!this.discovery.isActive()) {
                log.info("Binding NetherNet Discovery to {}", bindAddress);
                this.discovery.bind(bindAddress);
            }

            log.debug("Sending Discovery Request to {}", remote);

            this.discovery.sendDiscoveryRequest(this.remoteAddress, (serverNetworkId, payload) -> {
                try {
                    log.info("Discovery Response Received! Server NetworkID: {}", serverNetworkId);

                    discoveredServerId.set(Long.toUnsignedString(serverNetworkId));

                    future.complete(Collections.emptyList());
                } catch (Exception e) {
                    log.error("Error processing discovery response", e);
                    future.completeExceptionally(e);
                } finally {
                    ReferenceCountUtil.release(payload);
                }
            });
        } catch (Exception e) {
            log.error("Failed to send discovery request", e);
            future.completeExceptionally(e);
        }

        return future;
    }

    @Override
    public boolean allowsIceOnLocalPort() {
        return false;
    }

    @Override
    public void bind(SocketAddress localAddress, @Nullable InetSocketAddress iceAddress, EventLoop eventLoop) {
        if (!this.discovery.isActive()) {
            if (localAddress instanceof InetSocketAddress) {
                this.discovery.bind((InetSocketAddress) localAddress);
            } else {
                this.discovery.bind(bindAddress);
            }
        }
        startValidation();
    }

    private synchronized void startValidation() {
        if (this.validation != null) {
            return;
        }
        // One thread keeps checks as serial as the loop was
        ThreadPoolExecutor validation = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_VALIDATIONS), task -> {
                    Thread thread = new Thread(task, "NetherNet LAN identity validation");
                    thread.setDaemon(true);
                    return thread;
                });
        this.validation = validation;
        prepareTrust(validation);
    }

    /** Fetches the trust anchor's keys now rather than under the first join. */
    private void prepareTrust(ExecutorService validation) {
        try {
            validation.execute(() -> this.tokenTrust.prepare());
        } catch (RejectedExecutionException closedOrBusy) {
            // The first join fetches them instead
        }
    }

    /**
     * Sets who must have signed the token in a join's identity assertion, {@link TokenTrust#MINECRAFT_AUTH}
     * unless set. A join whose assertion does not hold is dropped. One without an assertion is handed over
     * without a player, leaving whether to admit it to the host.
     */
    public void setTokenTrust(TokenTrust tokenTrust) {
        this.tokenTrust = Objects.requireNonNull(tokenTrust);
        ExecutorService validation = this.validation;
        if (validation != null) {
            prepareTrust(validation);
        }
    }

    /**
     * Sets the identity answers are signed with, which the server channel reads when it is created. Without
     * one each channel makes its own, see {@link NetherNetServerSignaling#serverIdentity}.
     */
    public void setServerIdentity(@Nullable OperatorIdentity identity) {
        this.serverIdentity = identity;
    }

    @Override
    public @Nullable OperatorIdentity serverIdentity() {
        return this.serverIdentity;
    }

    @Override
    public void setNewConnectionHandler(NetherNetServerSignaling.NewConnectionHandler handler) {
        this.discovery.setNewConnectionHandler((connectionId, remoteNetworkId, offer, address, player) ->
                admit(handler, connectionId, remoteNetworkId, offer, address));
    }

    /**
     * Hands a join over, with the player its identity assertion names when it carries one that holds. Runs on
     * the discovery loop; a join with an assertion is checked off it and handed over back on it.
     */
    private void admit(NetherNetServerSignaling.NewConnectionHandler handler, String connectionId,
                       String remoteNetworkId, String offer, @Nullable SourcedAddress sourced) {
        if (offer.lines().noneMatch(line -> line.startsWith(IDENTITY_ATTRIBUTE))) {
            handler.onConnect(connectionId, remoteNetworkId, offer, sourced, null);
            return;
        }
        InetSocketAddress address = sourced == null ? null : sourced.address();
        ExecutorService validation = this.validation;
        EventLoop loop = this.discovery.eventLoop();
        if (validation == null || loop == null) {
            log.debug("Dropped LAN join {} from {}, signaling is not bound", connectionId, address);
            return;
        }

        // Only this loop touches it: signals arrive on it, and the check hands back to it
        List<String> held = new ArrayList<>();
        this.discovery.registerSignalHandler(connectionId, signal -> {
            if (held.size() < MAX_HELD_SIGNALS) {
                held.add(signal);
            }
        });
        TokenTrust trust = this.tokenTrust;
        try {
            validation.execute(() -> {
                PlayerInfo player;
                try {
                    JwtClaims claims = IdentityUtils.validateSdp(offer, trust);
                    player = new PlayerInfo(claims.getClaimValueAsString("xid"),
                            claims.getClaimValueAsString("xname"), remoteNetworkId, address, null, claims);
                } catch (Exception e) {
                    log.debug("Dropped LAN join {} from {}, its identity does not hold: {}", connectionId, address,
                            e.toString());
                    onLoop(loop, () -> this.discovery.unregisterSignalHandler(connectionId));
                    return;
                }
                onLoop(loop, () -> {
                    this.discovery.unregisterSignalHandler(connectionId);
                    handler.onConnect(connectionId, remoteNetworkId, offer, sourced, player);
                    SignalHandler next = this.discovery.signalHandler(connectionId);
                    if (next != null) {
                        held.forEach(next::onSignal);
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            this.discovery.unregisterSignalHandler(connectionId);
            log.debug("Dropped LAN join {} from {}, too many are waiting for their identity to be checked",
                    connectionId, address);
        }
    }

    /** Runs {@code task} on the discovery loop, unless it has shut down with the signaling. */
    private static void onLoop(EventLoop loop, Runnable task) {
        try {
            loop.execute(task);
        } catch (RejectedExecutionException closed) {
            // Closed while the identity was checked; the join goes with it
        }
    }

    @Override
    public void setAdvertisementData(PongData pongData) {
        this.discovery.setPongData(pongData);
    }

    /**
     * Asks {@code provider} for the LAN pong on every discovery request, see
     * {@link NetherNetDiscovery#setMotdProvider}.
     */
    public void setMotdProvider(MotdProvider provider) {
        this.discovery.setMotdProvider(provider);
    }

    /** Where discovery is bound, or null before it is. */
    InetSocketAddress localAddress() {
        return this.discovery.localAddress();
    }

    @Override
    public void sendSignal(String targetNetworkId, String data) {
        String actualIdStr = targetNetworkId;

        // Nothing to aim at yet, so use whatever discovery has found (Client Mode)
        if (actualIdStr == null || actualIdStr.equals(NetherNetConstants.DISCOVER_TARGET)) {
            actualIdStr = discoveredServerId.get();
        }

        if (actualIdStr == null) {
            log.warn("Cannot send signal: Unknown Network ID.");
            return;
        }

        try {
            long id = Long.parseUnsignedLong(actualIdStr);

            // If we have an explicit remote address (Client Mode), use it directly
            if (remoteAddress != null) {
                this.discovery.sendSignal(remoteAddress, id, data);
            } else {
                // Server Mode: Use the ID to find the address in the Discovery map
                this.discovery.sendSignal(id, data);
            }
        } catch (NumberFormatException e) {
            log.error("Cannot send LAN signal to non-numeric Network ID: {}", actualIdStr);
        }
    }

    @Override
    public void setSignalHandler(String connectionId, SignalHandler handler) {
        this.discovery.registerSignalHandler(connectionId, handler);
    }

    @Override
    public void removeSignalHandler(String connectionId) {
        this.discovery.unregisterSignalHandler(connectionId);
    }

    @Override
    public void setFailureHandler(NetherNetClientSignaling.FailureHandler handler) {
        // Nothing to do for discovery signaling
    }

    @Override
    public boolean isActive() {
        return this.discovery.isActive();
    }

    @Override
    public void close() {
        this.discovery.close();
        ExecutorService validation;
        synchronized (this) {
            validation = this.validation;
            this.validation = null;
        }
        if (validation != null) {
            validation.shutdownNow();
        }
    }
}
