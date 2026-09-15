package org.cloudburstmc.netty.signaling.diagnostic;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonParser;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticExchangeTest {
    record Frame(boolean toHost, int channel, byte[] bytes) { }
    static final String ATTEMPT = "12".repeat(16);
    static final class Pair {
        final ArrayDeque<Frame> queue = new ArrayDeque<>();
        final DiagnosticExchange host = new DiagnosticExchange(ATTEMPT, true, (c,b) -> queue.add(new Frame(false,c,b)));
        final DiagnosticExchange prober = new DiagnosticExchange(ATTEMPT, false, (c,b) -> queue.add(new Frame(true,c,b)));
        Pair() { host.start(0); prober.start(0); }
        void deliver(Frame frame) { (frame.toHost ? host : prober).receive(frame.channel, frame.bytes); }
        void drain() { int count = 0; while (!queue.isEmpty()) { assertTrue(++count < 50); deliver(queue.remove()); } }
    }
    @Test void bothEndpointsRequireFourIndependentRoundTripsAndPeerCompletion() {
        Pair pair = new Pair();
        assertFalse(pair.host.complete()); assertFalse(pair.prober.complete());
        assertNull(pair.host.completionDigestHex()); assertNull(pair.prober.completionDigestHex());
        Set<String> nonces = new HashSet<>(); for (Frame frame : pair.queue) nonces.add(HexFormat.of().formatHex(Arrays.copyOfRange(frame.bytes,24,56)));
        assertEquals(4, nonces.size()); pair.drain();
        assertTrue(pair.host.complete()); assertTrue(pair.prober.complete());
        assertTrue(pair.host.completionDigestHex().matches("[0-9a-f]{64}"));
        assertEquals(pair.host.completionDigestHex(), pair.prober.completionDigestHex());
        assertEquals(5, pair.host.sentFrames()); assertEquals(280, pair.host.sentBytes());
        assertEquals(6, pair.prober.sentFrames()); assertEquals(497, pair.prober.sentBytes());
    }
    @Test void reliableCompletionCanOvertakeUnreliableReplyWithoutEarlySuccess() {
        Pair pair = new Pair(); Frame held = null;
        while (!pair.queue.isEmpty()) {
            Frame frame = pair.queue.remove();
            if (!frame.toHost && frame.channel == 1 && frame.bytes[6] == DiagnosticExchange.REPLY) held = frame;
            else pair.deliver(frame);
        }
        assertNotNull(held); assertFalse(pair.prober.complete()); assertFalse(pair.host.complete());
        assertNull(pair.host.completionDigestHex()); assertNull(pair.prober.completionDigestHex());
        pair.deliver(held); pair.drain(); assertTrue(pair.host.complete()); assertTrue(pair.prober.complete());
    }
    @Test void twoLostUnreliableChallengesUseSameNonceAndBoundedRetries() {
        Pair pair = new Pair(); byte[] nonce = null; int dropped = 0;
        for (int tick = 0; tick < 4; tick++) {
            while (!pair.queue.isEmpty()) {
                Frame frame = pair.queue.remove();
                if (frame.toHost && frame.channel == 1 && frame.bytes[6] == DiagnosticExchange.CHALLENGE) {
                    byte[] current = Arrays.copyOfRange(frame.bytes,24,56);
                    if (nonce == null) nonce = current; else assertArrayEquals(nonce,current);
                    if (dropped++ < 2) continue;
                }
                pair.deliver(frame);
            }
            pair.host.tick((tick+1)*250_000_000L); pair.prober.tick((tick+1)*250_000_000L);
        }
        pair.drain(); assertEquals(3,dropped); assertTrue(pair.host.complete()); assertTrue(pair.prober.complete());
        assertTrue(pair.prober.sentBytes() <= 1024); assertTrue(pair.host.sentFrames() <= 12);
    }
    @Test void changedNonceCrossAttemptWrongChannelAndUnboundedDuplicatesFail() {
        for (int mutation : new int[]{7,8,24}) {
            Pair pair = new Pair(); Frame frame = pair.queue.remove(); byte[] changed = frame.bytes.clone(); changed[mutation] ^= 1;
            if (mutation == 24) { pair.deliver(frame); } // nonce replacement on same reliable challenge
            assertThrows(IllegalArgumentException.class, () -> pair.deliver(new Frame(frame.toHost,frame.channel,changed)));
        }
        Pair pair = new Pair(); Frame unreliable = pair.queue.stream().filter(f -> f.toHost && f.channel == 1).findFirst().orElseThrow();
        pair.drain(); pair.deliver(unreliable); pair.deliver(unreliable);
        assertThrows(IllegalArgumentException.class, () -> pair.deliver(unreliable)); assertFalse(pair.host.complete());
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticExchange(ATTEMPT,true,(c,b)->{}).receive(0,new byte[257]));
    }
    @Test void senderFailureCannotLaterBecomeSuccessful() {
        DiagnosticExchange exchange = new DiagnosticExchange(ATTEMPT,true,(c,b)->{throw new IllegalStateException("closed");});
        assertThrows(IllegalStateException.class, () -> exchange.start(0)); assertFalse(exchange.complete());
        assertThrows(IllegalArgumentException.class, () -> exchange.start(0));
    }
    @Test void independentPublicFrameFixtureMatchesCanonicalBytes() throws Exception {
        try(var reader=new InputStreamReader(getClass().getResourceAsStream("/nxs/diagnostic-exchange-v1.fixtures.json"),StandardCharsets.UTF_8)) {
            var fixture=JsonParser.parseReader(reader).getAsJsonObject();String attempt=fixture.get("attemptIdHex").getAsString();
            for(var value:fixture.getAsJsonArray("frames")) {
                var frame=value.getAsJsonObject();
                assertEquals(frame.get("frameHex").getAsString(),HexFormat.of().formatHex(DiagnosticExchange.encode(attempt,frame.get("kind").getAsInt(),frame.get("channel").getAsInt(),HexFormat.of().parseHex(frame.get("payloadHex").getAsString()))));
            }
            var n=fixture.getAsJsonObject("nonces");
            byte[] completion=DiagnosticAdmissionCodec.digest(DiagnosticAdmissionCodec.concat(DiagnosticAdmissionCodec.domain("completion"),HexFormat.of().parseHex(attempt),
                HexFormat.of().parseHex(n.get("proberReliable").getAsString()),HexFormat.of().parseHex(n.get("proberUnreliable").getAsString()),
                HexFormat.of().parseHex(n.get("hostReliable").getAsString()),HexFormat.of().parseHex(n.get("hostUnreliable").getAsString())));
            assertEquals(fixture.get("completionDigestHex").getAsString(),HexFormat.of().formatHex(completion));
        }
    }
}
