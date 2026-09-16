package org.cloudburstmc.netty.channel.nethernet;

import org.junit.jupiter.api.Test;
import tel.schich.libdatachannel.CandidatePair;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.PeerConnectionConfiguration;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NetherNetChannel#selectedPath()} reads the selected pair from the binding on a live
 * connection. This checks that the native call answers and that both sides come back typed and
 * resolved, which is what the address matching it replaced could not give behind a NAT.
 */
class SelectedPairTest {
    private static final Set<String> TYPES = Set.of("host", "srflx", "prflx", "relay");

    @Test
    void aLiveConnectionReportsATypedResolvedPair() throws Exception {
        try (PeerConnection offerer = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT);
             PeerConnection answerer = PeerConnection.createPeer(PeerConnectionConfiguration.DEFAULT)) {
            offerer.onLocalDescription.register((peer, sdp, type) -> answerer.setRemoteDescription(sdp, type));
            answerer.onLocalDescription.register((peer, sdp, type) -> offerer.setRemoteDescription(sdp, type));
            offerer.onLocalCandidate.register((peer, candidate, mid) -> answerer.addRemoteCandidate(candidate, mid));
            answerer.onLocalCandidate.register((peer, candidate, mid) -> offerer.addRemoteCandidate(candidate, mid));

            CountDownLatch open = new CountDownLatch(1);
            DataChannel sender = offerer.createDataChannel("pair");
            sender.onOpen.register(channel -> open.countDown());
            offerer.setLocalDescription(null);
            assertTrue(open.await(30, TimeUnit.SECONDS), "the channel should open");

            for (PeerConnection peer : List.of(offerer, answerer)) {
                CandidatePair pair = peer.selectedCandidatePair();
                assertNotNull(pair.local(), pair.localCandidate());
                assertNotNull(pair.remote(), pair.remoteCandidate());
                assertNotNull(pair.remote().getAddress(), "a literal resolves, so the address can be compared");
                assertTrue(TYPES.contains(pair.localType()), pair.localCandidate());
                assertTrue(TYPES.contains(pair.remoteType()), pair.remoteCandidate());
            }
        }
    }
}
