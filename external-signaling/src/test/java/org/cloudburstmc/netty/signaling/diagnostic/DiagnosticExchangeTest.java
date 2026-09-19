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
        Pair() { host.start(); prober.start(); }
        void deliver(Frame frame) { (frame.toHost ? host : prober).receive(frame.channel, frame.bytes); }
        void drain() { int count = 0; while (!queue.isEmpty()) { assertTrue(++count < 50); deliver(queue.remove()); } }
    }
    @Test void hostIsPassiveAndOnlyEchoProvesTheRoundTrip() {
        Pair pair=new Pair(); assertEquals(1,pair.queue.size()); assertEquals(0,pair.host.sentFrames());
        Frame ping=pair.queue.remove();assertTrue(ping.toHost);assertEquals(0,ping.channel);
        pair.deliver(ping);assertFalse(pair.prober.complete());assertEquals(1,pair.queue.size());assertFalse(pair.host.complete());
        pair.deliver(pair.queue.remove());assertTrue(pair.prober.complete());assertTrue(pair.queue.isEmpty());
        assertEquals(1,pair.host.sentFrames());assertEquals(56,pair.host.sentBytes());
        assertEquals(1,pair.prober.sentFrames());assertEquals(56,pair.prober.sentBytes());
        assertEquals(1,pair.host.receivedFrames());assertEquals(56,pair.host.receivedBytes());
    }
    @Test void missingPongNeverQualifiesAndThereIsNoRetryOrCompletion() {
        Pair pair=new Pair();pair.deliver(pair.queue.remove());pair.queue.clear();assertFalse(pair.prober.complete());
        assertFalse(pair.host.complete());assertTrue(pair.queue.isEmpty());
        assertThrows(IllegalArgumentException.class,()->DiagnosticExchange.encode(ATTEMPT,2,1,new byte[32]));
    }
    @Test void invalidKindNonceAttemptChannelAndDuplicatesFailClosed() {
        for(int mutation:new int[]{6,7,8,24}) {
            Pair pair=new Pair();pair.deliver(pair.queue.remove());Frame pong=pair.queue.remove();byte[] bad=pong.bytes.clone();bad[mutation]^=1;
            assertThrows(IllegalArgumentException.class,()->pair.prober.receive(pong.channel,bad));assertFalse(pair.prober.complete());
        }
        Pair pair=new Pair();Frame first=pair.queue.remove();pair.deliver(first);
        assertThrows(IllegalArgumentException.class,()->pair.deliver(first));
        assertThrows(IllegalArgumentException.class,()->DiagnosticExchange.encode(ATTEMPT,4,0,new byte[32]));
        assertThrows(IllegalArgumentException.class,()->new DiagnosticExchange(ATTEMPT,true,(c,f)->{}).receive(0,new byte[257]));
    }
    @Test void senderFailureCannotLaterBecomeSuccessful() {
        var exchange=new DiagnosticExchange(ATTEMPT,false,(c,f)->{throw new IllegalStateException("closed");});
        assertThrows(IllegalStateException.class,()->exchange.start());assertFalse(exchange.complete());
        assertThrows(IllegalArgumentException.class,()->exchange.start());
    }
    @Test void independentPublicFrameFixtureMatchesCanonicalBytes() throws Exception {
        try(var reader=new InputStreamReader(getClass().getResourceAsStream("/nxs/diagnostic-exchange-v1.fixtures.json"),StandardCharsets.UTF_8)) {
            var fixture=JsonParser.parseReader(reader).getAsJsonObject();String attempt=fixture.get("attemptIdHex").getAsString();
            for(var value:fixture.getAsJsonArray("frames")) {
                var frame=value.getAsJsonObject();
                assertEquals(frame.get("frameHex").getAsString(),HexFormat.of().formatHex(DiagnosticExchange.encode(attempt,frame.get("kind").getAsInt(),frame.get("channel").getAsInt(),HexFormat.of().parseHex(frame.get("payloadHex").getAsString()))));
            }

        }
    }
}
