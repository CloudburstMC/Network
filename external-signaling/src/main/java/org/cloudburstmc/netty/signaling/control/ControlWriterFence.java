package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;

/** Immutable writer identity. Equality is necessary for, but never implements, an authoritative CAS. */
public record ControlWriterFence(String transport, long sessionEpoch, String sessionId, String connectionId,
                                 String keyId, long machineKeyRevision) {
    public ControlWriterFence {
        ControlJson.identifier(keyId);
        if ("legacy-http".equals(transport)) {
            if (sessionEpoch != 0 || !"".equals(sessionId) || !"".equals(connectionId) || machineKeyRevision != 0) throw ControlJson.invalid("legacy writer");
        } else {
            if (!"websocket".equals(transport) && !"https".equals(transport)) throw ControlJson.invalid("writer transport");
            ControlJson.safe(sessionEpoch, true);
            ControlJson.safe(machineKeyRevision, true);
            ControlJson.opaque(sessionId);
            ControlJson.opaque(connectionId);
        }
    }

    public static ControlWriterFence decode(String wire) { return read(ControlJson.parse(wire, 1024)); }
    public String encode() { return object().toString(); }

    static ControlWriterFence read(JsonObject value) {
        String transport = ControlJson.string(value, "transport");
        if (transport.equals("legacy-http")) ControlJson.fields(value, "transport", "sessionEpoch", "sessionId", "connectionId", "keyId");
        else ControlJson.fields(value, "transport", "sessionEpoch", "sessionId", "connectionId", "keyId", "machineKeyRevision");
        return new ControlWriterFence(transport, ControlJson.number(value, "sessionEpoch"), ControlJson.string(value, "sessionId"),
                ControlJson.string(value, "connectionId"), ControlJson.string(value, "keyId"),
                transport.equals("legacy-http") ? 0 : ControlJson.number(value, "machineKeyRevision"));
    }

    JsonObject object() {
        JsonObject value = new JsonObject();
        value.addProperty("transport", transport);
        value.addProperty("sessionEpoch", sessionEpoch);
        value.addProperty("sessionId", sessionId);
        value.addProperty("connectionId", connectionId);
        value.addProperty("keyId", keyId);
        if (!transport.equals("legacy-http")) value.addProperty("machineKeyRevision", machineKeyRevision);
        return value;
    }
}
