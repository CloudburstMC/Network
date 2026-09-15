package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import static org.cloudburstmc.netty.signaling.control.ControlDiagnosticInstallationCodec.*;

/** Optional typed heartbeat slice. A null expected document supplies no material or new authority. */
public final class ControlDiagnosticHeartbeatCodec {
    public static final int MAX_REQUEST_BYTES = 2_112, MAX_RESPONSE_BYTES = 26_752;
    public record Request(Acknowledgement installed) { }
    public record Response(Installation expected, Acknowledgement accepted) {
        @Override public String toString() { return "DiagnosticHeartbeatResponse[installation=redacted, accepted=" + accepted + "]"; }
    }
    private ControlDiagnosticHeartbeatCodec() { }

    public static Request decodeRequest(String wire) {
        JsonObject value = ControlJson.parse(wire, MAX_REQUEST_BYTES);
        ControlJson.fields(value, "version", "installed"); ControlJson.version(value);
        return new Request(value.get("installed").isJsonNull() ? null : decodeAcknowledgement(value.get("installed").toString()));
    }
    public static Response decodeResponse(String wire) {
        JsonObject value = ControlJson.parse(wire, MAX_RESPONSE_BYTES);
        ControlJson.fields(value, "version", "expected", "accepted"); ControlJson.version(value);
        return new Response(value.get("expected").isJsonNull() ? null : decodeInstallation(value.get("expected").toString()),
                value.get("accepted").isJsonNull() ? null : decodeAcknowledgement(value.get("accepted").toString()));
    }
    public static String encodeRequest(Request request) {
        JsonObject value = new JsonObject(); value.addProperty("version", 1);
        value.add("installed", request.installed() == null ? JsonNull.INSTANCE : JsonParser.parseString(encodeAcknowledgement(request.installed())));
        return value.toString();
    }
    public static String encodeResponse(Response response) {
        JsonObject value = new JsonObject(); value.addProperty("version", 1);
        value.add("expected", response.expected() == null ? JsonNull.INSTANCE : JsonParser.parseString(encodeInstallation(response.expected())));
        value.add("accepted", response.accepted() == null ? JsonNull.INSTANCE : JsonParser.parseString(encodeAcknowledgement(response.accepted())));
        return value.toString();
    }
}
