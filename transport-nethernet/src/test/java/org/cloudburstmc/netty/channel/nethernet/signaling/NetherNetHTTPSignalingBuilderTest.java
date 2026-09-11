package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.util.nethernet.ServerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
