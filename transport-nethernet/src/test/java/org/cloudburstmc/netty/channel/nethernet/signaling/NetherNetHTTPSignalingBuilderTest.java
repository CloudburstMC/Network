package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.util.nethernet.ServerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.cloudburstmc.netty.util.nethernet.SdpUtil;
import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NetherNetHTTPSignalingBuilderTest {

    /** Throwaway key from {@code openssl ecparam -name secp384r1 -genkey -noout}. Never deploy it. */
    private static final String PEM =
            "-----BEGIN EC PRIVATE KEY-----\n" +
            "MIGkAgEBBDDmCV/icghwrAdKuvy8s6iJc7J5SdH9Ks43hq7Bw1JO/d0sgdHGbe30\n" +
            "mKHzlu5+GW6gBwYFK4EEACKhZANiAAQK7XU8ZiZox723S4u5U01a1Uioo5TMCuT7\n" +
            "ozPRWmm431vyOC9i+irVFLOTjcvwAY3D3+T7RGG1Y1/F0CPoO8tP3ClHbOeCgINA\n" +
            "qGQgpH3NW/5D6hvZubCqcEKvV2igcoA=\n" +
            "-----END EC PRIVATE KEY-----\n";

    private File pem(Path dir) throws Exception {
        Path file = dir.resolve("identity.pem");
        Files.writeString(file, PEM);
        return file.toFile();
    }

    @Test
    void acceptsAPemIdentity(@TempDir Path dir) throws Exception {
        NetherNetHTTPSignaling signaling = new NetherNetHTTPSignaling.Builder()
                .setIdentityPem(pem(dir), "example.test")
                .build();

        assertNotNull(signaling.serverIdentity());
    }

    @Test
    void acceptsAPrebuiltIdentity(@TempDir Path dir) throws Exception {
        ServerIdentity identity = ServerIdentity.generate("example.test");
        NetherNetHTTPSignaling signaling = new NetherNetHTTPSignaling.Builder()
                .setIdentity(identity)
                .build();

        assertEquals(identity, signaling.serverIdentity());
    }

    @Test
    void reportsWhatItWillDoWithIce(@TempDir Path dir) throws Exception {
        NetherNetSignaling.IceServerInfo turn =
                new NetherNetSignaling.IceServerInfo("user", "secret", List.of("turn:turn.example:3478"));
        NetherNetHTTPSignaling signaling = new NetherNetHTTPSignaling.Builder()
                .setIdentity(ServerIdentity.generate("example.test"))
                .setIceOnLocalPort(false)
                .setIceServers(List.of(turn))
                .build();

        assertFalse(signaling.usesTrickleIce(), "an answer is sent once, with the candidates it has");
        assertFalse(signaling.allowsIceOnLocalPort());
        assertEquals(List.of(turn), signaling.getIceServers());
        assertNotNull(signaling.serverIdentity());
        assertFalse(signaling.isActive(), "nothing is serving until it is bound");
        signaling.close();
    }

    @Test
    void defaultsToAnnouncingEverythingItGathers() throws Exception {
        NetherNetHTTPSignaling signaling = new NetherNetHTTPSignaling.Builder()
                .setIdentity(ServerIdentity.generate("example.test"))
                .setAdvertisedAddresses(null)
                .setIceServers(null)
                .build();

        assertTrue(signaling.allowsIceOnLocalPort(), "ICE may use the signalling port unless told otherwise");
        assertEquals(List.of(), signaling.getIceServers());
        // Nothing configured means nothing is filtered out of an answer
        assertEquals(SDP, SdpUtil.withAdvertisedCandidates(SDP, Set.of()));
        signaling.close();
    }

    @Test
    void takesNoAdvertisementData() throws Exception {
        NetherNetHTTPSignaling signaling = new NetherNetHTTPSignaling.Builder()
                .setIdentity(ServerIdentity.generate("example.test"))
                .build();

        // The MOTD comes from the provider on every request, so a pushed one is ignored
        assertDoesNotThrow(() -> signaling.setAdvertisementData(NetherNetServerSignaling.PongData.DEFAULT));
        signaling.close();
    }

    @Test
    void refusesToBindSomethingThatIsNotAnInternetAddress() throws Exception {
        NetherNetHTTPSignaling signaling = new NetherNetHTTPSignaling.Builder()
                .setIdentity(ServerIdentity.generate("example.test"))
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> signaling.bind(new java.net.SocketAddress() {
                }, null));
        signaling.close();
    }

    @Test
    void reportsAnUnreadableKeystoreAtConfigurationTime(@TempDir Path dir) {
        // A bad TLS configuration has to fail where it is written, not on the first join
        File missing = dir.resolve("nothing.p12").toFile();

        assertThrows(IllegalArgumentException.class,
                () -> new NetherNetHTTPSignaling.Builder().setHttpsKeystore(missing));
        assertThrows(IllegalArgumentException.class,
                () -> new NetherNetHTTPSignaling.Builder().setHttpsKeystore(missing, "password"));
        assertThrows(IllegalArgumentException.class,
                () -> new NetherNetHTTPSignaling.Builder().setHttpsPem(missing, missing));
        assertThrows(IllegalArgumentException.class,
                () -> new NetherNetHTTPSignaling.Builder().setHttpsPem(missing, missing, "password"));
    }

    private static final String SDP = "v=0\r\na=candidate:1 1 udp 2130706431 203.0.113.10 19191 typ host\r\n";

    @Test
    void refusesToBuildWithoutAnIdentity() {
        assertThrows(IllegalStateException.class, () -> new NetherNetHTTPSignaling.Builder().build());
    }

    @Test
    void reportsAnUnreadableIdentityAtConfigurationTime(@TempDir Path dir) throws Exception {
        File unreadable = dir.resolve("broken.pem").toFile();
        Files.writeString(unreadable.toPath(), "not a pem at all\n");

        assertThrows(IllegalArgumentException.class,
                () -> new NetherNetHTTPSignaling.Builder().setIdentityPem(unreadable, "example.test"));
    }
}
