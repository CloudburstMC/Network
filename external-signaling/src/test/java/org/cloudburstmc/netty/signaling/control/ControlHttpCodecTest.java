package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.cloudburstmc.netty.signaling.control.ControlSessionCodecTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlHttpCodecTest {
    private static ControlHttpCodec.Context context(JsonObject vector, ControlHttpCodec.Request request) throws Exception {
        return new ControlHttpCodec.Context(request.audience(), request.intent().instanceId(), request.intent().generation(), request.method(),
                request.encodedPathAndQuery(), request.intent().operation(), ControlWriterFence.decode(vector.get("writer").toString()),
                request.capabilities(), now(), now() + 300000, 1000);
    }

    @Test
    void preservesOneIntentAcrossWsCarrierKeyRotationAndPersistentHttpFallback() throws Exception {
        String sameIntent = null;
        for (String name : List.of("http-over-ws", "http-after-key-rotation", "http-persistent-fallback")) {
            var vector = vector(name);
            String wire = vector.get("envelope").toString();
            var request = ControlHttpCodec.decode(wire);
            byte[] body = vector.get("bodyUtf8").getAsString().getBytes(StandardCharsets.UTF_8);
            var key = key(vector.get("keyFamily").getAsString());
            var context = context(vector, request);
            assertEquals(vector.get("signingText").getAsString(), new String(ControlHttpCodec.signingBytes(request), StandardCharsets.UTF_8));
            assertEquals(request, ControlHttpCodec.verify(wire, body, context, key));
            assertEquals(request, ControlHttpCodec.decode(ControlHttpCodec.encode(request)));
            var signed = ControlHttpCodec.sign(request, privateKey(vector.get("keyFamily").getAsString()));
            assertEquals(signed, ControlHttpCodec.verify(ControlHttpCodec.encode(signed), body, context, key));
            String digest = ControlLifecycleCodec.intentDigest(request.intent());
            if (sameIntent == null) sameIntent = digest;
            assertEquals(sameIntent, digest);
        }
    }

    @Test
    void originalBodyActualRouteCurrentWriterAndMachineKeyAreAllRequired() throws Exception {
        var vector = vector("http-over-ws");
        String wire = vector.get("envelope").toString();
        var request = ControlHttpCodec.decode(wire);
        byte[] body = vector.get("bodyUtf8").getAsString().getBytes(StandardCharsets.UTF_8);
        var original = context(vector, request);
        assertThrows(IllegalArgumentException.class, () -> ControlHttpCodec.verify(wire, (new String(body, StandardCharsets.UTF_8).trim()).getBytes(StandardCharsets.UTF_8), original, key("machine")));
        assertThrows(IllegalArgumentException.class, () -> ControlHttpCodec.verify(wire, body, original, key("machineCandidate")));
        assertThrows(IllegalArgumentException.class, () -> ControlHttpCodec.verify(wire, body, original, key("providerControl")));
        for (String field : List.of("route", "operation", "generation", "writer", "capabilities")) {
            var context = new ControlHttpCodec.Context(original.audience(), original.instanceId(), field.equals("generation") ? 4 : 3,
                    original.method(), field.equals("route") ? "/signal/heartbeat?version=2" : original.encodedPathAndQuery(),
                    field.equals("operation") ? "rotate" : "heartbeat", field.equals("writer") ? writer("activatedWriter") : original.writer(),
                    field.equals("capabilities") ? List.of("request-response") : original.capabilities(), now(), now() + 300000, 1000);
            assertThrows(IllegalArgumentException.class, () -> ControlHttpCodec.verify(wire, body, context, key("machine")), field);
        }
        var rotatedVector = vector("http-after-key-rotation");
        var rotatedContext = context(rotatedVector, ControlHttpCodec.decode(rotatedVector.get("envelope").toString()));
        assertThrows(IllegalArgumentException.class, () -> ControlHttpCodec.verify(wire, body, rotatedContext, key("machine")));
    }

    @Test
    void unsignedTargetsUnknownFieldsAndSlidingDeadlinesAreRejected() throws Exception {
        JsonObject original = vector("http-over-ws").getAsJsonObject("envelope");
        for (String target : List.of("https://other.example/x", "//other.example/x", "/path#fragment", "/path\\next", "/path%", "/path%Q0", "/path query")) {
            var changed = original.deepCopy(); changed.addProperty("encodedPathAndQuery", target);
            assertThrows(IllegalArgumentException.class, () -> ControlHttpCodec.decode(changed.toString()), target);
        }
        var changed = original.deepCopy(); changed.addProperty("machineKeyRevision", 2);
        assertThrows(IllegalArgumentException.class, () -> ControlHttpCodec.decode(changed.toString()));
        String wire = original.toString();
        assertThrows(IllegalArgumentException.class, () -> ControlHttpCodec.decode(wire.replace("\"sessionEpoch\":7", "\"sessionEpoch\":7e0")));
        assertThrows(IllegalArgumentException.class, () -> ControlHttpCodec.decode(wire.replace("\"method\":\"POST\"", "\"method\":\"POST\",\"\\u006dethod\":\"POST\"")));
        var request = ControlHttpCodec.decode(wire);
        var ctx = context(vector("http-over-ws"), request);
        byte[] body = vector("http-over-ws").get("bodyUtf8").getAsString().getBytes(StandardCharsets.UTF_8);
        var shortened = new ControlHttpCodec.Context(ctx.audience(), ctx.instanceId(), ctx.generation(), ctx.method(), ctx.encodedPathAndQuery(),
                ctx.operation(), ctx.writer(), ctx.capabilities(), now(), request.expiresAt() - 1, 1000);
        assertThrows(IllegalArgumentException.class, () -> ControlHttpCodec.verify(wire, body, shortened, key("machine")));
    }
}
