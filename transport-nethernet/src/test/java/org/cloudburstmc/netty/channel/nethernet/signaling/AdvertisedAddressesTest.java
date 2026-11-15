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

import io.netty.util.NetUtil;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Advertised names, looked up through a resolver the test answers for. */
class AdvertisedAddressesTest {
    private final Map<String, InetAddress[]> dns = new HashMap<>();

    private List<InetSocketAddress> resolve(String... entries) throws UnknownHostException {
        return AdvertisedAddresses.resolve(AdvertisedAddresses.parse(List.of(entries), 19132), host -> {
            InetAddress[] answer = this.dns.get(host);
            if (answer == null) {
                throw new UnknownHostException(host);
            }
            return answer;
        });
    }

    private void answer(String host, String... addresses) {
        InetAddress[] answer = new InetAddress[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            answer[i] = NetUtil.createInetAddressFromIpAddressString(addresses[i]);
        }
        this.dns.put(host, answer);
    }

    private static InetSocketAddress endpoint(String address, int port) {
        return new InetSocketAddress(NetUtil.createInetAddressFromIpAddressString(address), port);
    }

    @Test
    void readsLiteralsAndNamesWithoutLookingAnythingUp() {
        List<InetSocketAddress> parsed = AdvertisedAddresses.parse(List.of(" 1.1.1.1", "[2606:4700::1]:19133",
                "play.example.test", "play.example.test:19134", "1.1.1.1:19132"), 19132);

        assertEquals(List.of(endpoint("1.1.1.1", 19132), endpoint("2606:4700::1", 19133),
                InetSocketAddress.createUnresolved("play.example.test", 19132),
                InetSocketAddress.createUnresolved("play.example.test", 19134)), parsed);
        assertTrue(parsed.get(2).isUnresolved());
    }

    @Test
    void aNameStandsForEveryAddressItResolvesTo() throws Exception {
        answer("play.example.test", "8.8.8.8", "2606:4700::7", "1.1.1.1");

        assertEquals(List.of(endpoint("1.1.1.1", 19133), endpoint("8.8.8.8", 19133),
                endpoint("2606:4700::7", 19133)), resolve("1.1.1.1:19133", "play.example.test:19133"));
    }

    @Test
    void failsOnANameThatDoesNotResolve() {
        UnknownHostException failure = assertThrows(UnknownHostException.class,
                () -> resolve("1.1.1.1", "play.example.test"));
        assertEquals("Could not resolve advertised address play.example.test", failure.getMessage());
    }

    @Test
    void failsOnANameThatResolvesWhereNoPlayerCanReach() {
        answer("play.example.test", "1.1.1.1", "0.0.0.0");
        assertThrows(IllegalArgumentException.class, () -> resolve("play.example.test"));
    }

    @Test
    void failsBeyondTheMostEndpoints() throws Exception {
        String[] most = IntStream.rangeClosed(1, AdvertisedAddresses.MAX_ENDPOINTS)
                .mapToObj(i -> "8.8.8.8:" + (20000 + i)).toArray(String[]::new);
        assertEquals(AdvertisedAddresses.MAX_ENDPOINTS, resolve(most).size());

        // Two addresses behind the one name are two endpoints
        answer("play.example.test", "1.1.1.1", "1.0.0.1");
        most[0] = "play.example.test";
        assertThrows(IllegalArgumentException.class, () -> resolve(most));
    }

    @Test
    void refusesWhatIsNeitherALiteralNorAName() {
        for (String entry : List.of("", "user@play.example.test", "play.example.test:0", "play.example.test:65536",
                "http://play.example.test", "1.1.1.999", "[1::2::3]", "play.example.test:port", "::1:19133",
                "1.1.1.1:0")) {
            assertThrows(IllegalArgumentException.class, () -> AdvertisedAddresses.parse(List.of(entry), 19132),
                    entry);
        }
    }

    @Test
    void refusesALiteralNoPlayerCanReach() {
        for (String entry : List.of("0.0.0.0", "[::]:19133", "224.0.0.1", "[fe80::1]", "203.0.113.1:19133",
                "[2001:db8::1]")) {
            assertThrows(IllegalArgumentException.class, () -> AdvertisedAddresses.parse(List.of(entry), 19132),
                    entry);
        }
    }
}
