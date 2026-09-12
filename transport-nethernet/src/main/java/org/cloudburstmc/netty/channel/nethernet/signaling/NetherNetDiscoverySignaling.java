package org.cloudburstmc.netty.channel.nethernet.signaling;

import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

public class NetherNetDiscoverySignaling implements NetherNetClientSignaling, NetherNetServerSignaling {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetDiscoverySignaling.class);

    private final NetherNetDiscovery discovery;
    private final InetSocketAddress bindAddress;
    private final String localNetworkId;

    // State captured after connect
    private volatile InetSocketAddress remoteAddress;
    private final AtomicReference<String> discoveredServerId = new AtomicReference<>(null);

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
    public void bind(SocketAddress localAddress, EventLoop eventLoop) {
        if (!this.discovery.isActive()) {
            if (localAddress instanceof InetSocketAddress) {
                this.discovery.bind((InetSocketAddress) localAddress);
            } else {
                this.discovery.bind(bindAddress);
            }
        }
    }

    @Override
    public void setNewConnectionHandler(NetherNetServerSignaling.NewConnectionHandler handler) {
        this.discovery.setNewConnectionHandler(handler);
    }

    @Override
    public void setAdvertisementData(PongData pongData) {
        this.discovery.setPongData(pongData);
    }

    @Override
    public void sendSignal(String targetNetworkId, String data) {
        String actualIdStr = targetNetworkId;

        // If '0' is passed, try to use the discovered ID (Client Mode)
        if (actualIdStr == null || actualIdStr.equals("0")) {
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
    public void setSignalHandler(long connectionId, SignalHandler handler) {
        this.discovery.registerSignalHandler(connectionId, handler);
    }

    @Override
    public void removeSignalHandler(long connectionId) {
        this.discovery.unregisterSignalHandler(connectionId);
    }

    @Override
    public void setNotFoundHandler(NetherNetClientSignaling.NotFoundHandler handler) {
        // Nothing to do for discovery signalling
    }

    @Override
    public boolean isActive() {
        return this.discovery.isActive();
    }

    @Override
    public void close() {
        this.discovery.close();
    }
}
