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

package org.cloudburstmc.netty.util.nethernet;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetDiscovery;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
import org.cloudburstmc.netty.channel.nethernet.signaling.ServerData;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A simple scanner example for discovering NetherNet servers on the local network.
 */
public class NetherNetScanner {
    public static void main(String[] args) throws Exception {
        long myNetworkId = ThreadLocalRandom.current().nextLong();
        NetherNetDiscovery discovery = new NetherNetDiscovery(myNetworkId);

        discovery.bind(new InetSocketAddress("::", 0));

        System.out.println("Scanning for NetherNet servers on port 7551...");

        InetSocketAddress broadcastTarget = new InetSocketAddress("255.255.255.255", NetherNetConstants.DISCOVERY_PORT);

        discovery.sendDiscoveryRequest(broadcastTarget, (senderId, payload) -> {
            try {
                // A server answers in each version it knows, so this prints it once per reply
                PongData status = ServerData.decode(payload);
                if (status == null) {
                    return;
                }
                System.out.println("--------------------------------");
                System.out.println("Found Server: " + senderId);
                System.out.println("Data Version: " + status.dataVersion());
                System.out.println("MOTD: " + status.serverName());
                System.out.println("Level: " + status.levelName());
                System.out.println("Version: " + status.version() + " (" + status.protocol() + ")");
                System.out.println("Players: " + status.playerCount() + "/" + status.maxPlayerCount());
                System.out.println("Game Mode: " + status.gameType());
                System.out.println("Editor World: " + status.isEditorWorld());
                System.out.println("Hardcore: " + status.isHardcore());
                System.out.println("Xbox / Self-Signed Logins: " + status.onlineAuth() + " / " + status.selfSignedAuth());
                System.out.println("--------------------------------");
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                payload.release();
            }
        });

        Thread.sleep(10000);
        discovery.close();
    }
}
