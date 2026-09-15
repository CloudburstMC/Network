package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.List;

/** Shared application-state digests. These values alone are neither authority nor proof of native application. */
public final class ControlStateCodec {
    public static final int MAX_STATE_BYTES = 2048, MAX_TICKET_POLICY_BYTES = 4096;
    /** Null cutoff means unretired; finite cutoffs are original absolute timestamps. */
    public record TicketEpoch(String keyId, long notBefore, Long acceptUntil) {
        public TicketEpoch {
            ControlStateCodec.keyId(keyId); ControlJson.safe(notBefore, false);
            if (acceptUntil != null) { ControlJson.safe(acceptUntil, true); if (acceptUntil <= notBefore) throw ControlJson.invalid("epoch cutoff"); }
        }
    }
    public record TicketPolicy(String activeKeyId, List<TicketEpoch> epochs) {
        public TicketPolicy {
            keyId(activeKeyId); epochs = List.copyOf(epochs);
            if (epochs.isEmpty() || epochs.size() > 8) throw ControlJson.invalid("epoch count");
            String previous = ""; boolean active = false;
            for (var epoch : epochs) {
                if (epoch.keyId().compareTo(previous) <= 0) throw ControlJson.invalid("epoch order");
                previous = epoch.keyId(); active |= previous.equals(activeKeyId);
            }
            if (!active) throw ControlJson.invalid("active epoch");
        }
    }
    public record AppliedBasis(long generation, long desiredRevision, String state, String admission,
                               String hostProfileRevision, String ticketPolicySha256) {
        public AppliedBasis {
            ControlJson.safe(generation, true); ControlJson.safe(desiredRevision, false); hostState(state);
            if (state.equals("serving")) {
                if (!"enabled".equals(admission)) throw ControlJson.invalid("serving admission");
                ControlJson.identifier(hostProfileRevision); ControlJson.digest(ticketPolicySha256);
            } else if (!"disabled".equals(admission) || hostProfileRevision != null || ticketPolicySha256 != null)
                throw ControlJson.invalid("disabled admission");
        }
    }
    public record Summary(long desiredRevision, String desiredState, String appliedBasisSha256) {
        public Summary {
            ControlJson.safe(desiredRevision, false); hostState(desiredState);
            if (appliedBasisSha256 != null) ControlJson.digest(appliedBasisSha256);
        }
    }
    /** Shared state.applied/session.ready payload. Identity, sequence and lifetime belong to the outer frame. */
    public record Acknowledgement(String syncId, Summary state) {
        public Acknowledgement { ControlJson.opaque(syncId); java.util.Objects.requireNonNull(state); ControlJson.digest(state.appliedBasisSha256()); }
    }
    private ControlStateCodec() { }
    private static void keyId(String value) { if (value == null || !value.matches("[A-Z0-9]{4}")) throw ControlJson.invalid("epoch key"); }
    private static void hostState(String value) {
        if (!"serving".equals(value) && !"draining".equals(value) && !"closed".equals(value)) throw ControlJson.invalid("host state");
    }
    private static String nullableString(JsonObject object, String name) {
        return object.get(name).isJsonNull() ? null : ControlJson.string(object, name);
    }
    public static TicketPolicy decodeTicketPolicy(String wire) {
        var object = ControlJson.parse(wire, MAX_TICKET_POLICY_BYTES);
        ControlJson.fields(object, "version", "activeKeyId", "epochs"); ControlJson.version(object);
        if (!object.get("epochs").isJsonArray()) throw ControlJson.invalid("epochs");
        var epochs = new java.util.ArrayList<TicketEpoch>();
        for (var value : object.getAsJsonArray("epochs")) {
            if (!value.isJsonObject()) throw ControlJson.invalid("epoch");
            var epoch = value.getAsJsonObject(); ControlJson.fields(epoch, "keyId", "notBefore", "acceptUntil");
            epochs.add(new TicketEpoch(ControlJson.string(epoch, "keyId"), ControlJson.number(epoch, "notBefore"),
                    epoch.get("acceptUntil").isJsonNull() ? null : ControlJson.number(epoch, "acceptUntil")));
        }
        return new TicketPolicy(ControlJson.string(object, "activeKeyId"), epochs);
    }
    public static String encodeTicketPolicy(TicketPolicy policy) {
        var object = new JsonObject(); object.addProperty("version", 1); object.addProperty("activeKeyId", policy.activeKeyId());
        var epochs = new JsonArray();
        for (var epoch : policy.epochs()) {
            var item = new JsonObject(); item.addProperty("keyId", epoch.keyId()); item.addProperty("notBefore", epoch.notBefore());
            item.addProperty("acceptUntil", epoch.acceptUntil()); epochs.add(item);
        }
        object.add("epochs", epochs); return object.toString();
    }
    public static String ticketPolicyDigest(TicketPolicy policy) {
        return ControlFrameCodec.payloadDigest(ControlProof.array("nethernet-control-ticket-policy-v1", 1, policy.activeKeyId(),
                policy.epochs().stream().map(epoch -> Arrays.asList(epoch.keyId(), epoch.notBefore(), epoch.acceptUntil())).toList()));
    }
    public static AppliedBasis decodeAppliedBasis(String wire) {
        var object = ControlJson.parse(wire, MAX_STATE_BYTES);
        ControlJson.fields(object, "version", "generation", "desiredRevision", "state", "admission", "hostProfileRevision", "ticketPolicySha256");
        ControlJson.version(object);
        return new AppliedBasis(ControlJson.number(object, "generation"), ControlJson.number(object, "desiredRevision"),
                ControlJson.string(object, "state"), ControlJson.string(object, "admission"),
                nullableString(object, "hostProfileRevision"), nullableString(object, "ticketPolicySha256"));
    }
    public static String encodeAppliedBasis(AppliedBasis basis) {
        var object = new JsonObject(); object.addProperty("version", 1); object.addProperty("generation", basis.generation());
        object.addProperty("desiredRevision", basis.desiredRevision()); object.addProperty("state", basis.state());
        object.addProperty("admission", basis.admission()); object.addProperty("hostProfileRevision", basis.hostProfileRevision());
        object.addProperty("ticketPolicySha256", basis.ticketPolicySha256()); return object.toString();
    }
    public static String appliedBasisDigest(AppliedBasis basis) {
        return ControlFrameCodec.payloadDigest(ControlProof.array("nethernet-control-applied-state-v1", 1, basis.generation(),
                basis.desiredRevision(), basis.state(), basis.admission(), basis.hostProfileRevision(), basis.ticketPolicySha256()));
    }
    public static Summary readSummary(JsonObject object) {
        ControlJson.fields(object, "desiredRevision", "desiredState", "appliedBasisSha256");
        return new Summary(ControlJson.number(object, "desiredRevision"), ControlJson.string(object, "desiredState"),
                nullableString(object, "appliedBasisSha256"));
    }
    public static Acknowledgement decodeAcknowledgement(String wire) {
        var object = ControlJson.parse(wire, MAX_STATE_BYTES);
        ControlJson.fields(object, "version", "syncId", "desiredRevision", "desiredState", "appliedBasisSha256"); ControlJson.version(object);
        return new Acknowledgement(ControlJson.string(object, "syncId"), new Summary(ControlJson.number(object, "desiredRevision"),
                ControlJson.string(object, "desiredState"), ControlJson.string(object, "appliedBasisSha256")));
    }
    public static String encodeAcknowledgement(Acknowledgement ack) {
        var object = new JsonObject(); object.addProperty("version", 1); object.addProperty("syncId", ack.syncId());
        object.addProperty("desiredRevision", ack.state().desiredRevision()); object.addProperty("desiredState", ack.state().desiredState());
        object.addProperty("appliedBasisSha256", ack.state().appliedBasisSha256()); return object.toString();
    }
    /** Pure comparison; callers must separately hold current source authority and actual applied state. */
    public static boolean matches(Summary summary, Acknowledgement ack) { return ack.state().equals(summary); }
}
