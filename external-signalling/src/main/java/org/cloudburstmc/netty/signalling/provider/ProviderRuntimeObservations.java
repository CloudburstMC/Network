package org.cloudburstmc.netty.signalling.provider;

import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signalling.ProviderClient;

/**
 * Host observations are independent of the publicly advertised server-list status.
 */
public final class ProviderRuntimeObservations {
    private ProviderRuntimeObservations() {
    }

    public static ProviderClient.Health health(int connectedPlayers, int capacity, long sampledAt, String build) {
        return health(connectedPlayers, capacity, sampledAt, build, true);
    }

    /**
     * @param acceptingPlayers Whether the host wants more players, which a full or draining host
     *                         answers no to while still being healthy
     */
    public static ProviderClient.Health health(int connectedPlayers, int capacity, long sampledAt, String build,
                                               boolean acceptingPlayers) {
        return new ProviderClient.Health(true, acceptingPlayers, capacity,
                Math.min(1, (double) connectedPlayers / Math.max(1, capacity)), "nethernet", build,
                new ProviderClient.PlayerCount(connectedPlayers, sampledAt));
    }

    public static String registrationMessage(JsonObject registration) {
        String instanceId = registration.get("instanceId").getAsString();
        var address = registration.get("publicAddress");
        return address != null && !address.isJsonNull()
                ? "Provider address: " + address.getAsString() + " (instance " + instanceId + ")"
                : "Provider instance registered: " + instanceId + "; public addresses are managed on its attached Signal Servers";
    }
}
