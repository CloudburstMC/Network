package org.cloudburstmc.netty.signaling;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.netty.signaling.control.*;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Exact historical adoption proof. None of these serializable values establishes a live listener. */
final class ControlledNativeOwner {
    static final String MODE = "issued-v1";
    record Claim(CandidateLeaseCodec.NativeOwnerClaim claim, CandidateLeaseCodec.Profile profile, String bodyDigest) {
        CandidateLeaseCodec.NativeOwner issued() {
            return new CandidateLeaseCodec.NativeOwner(claim.expectedEpoch() + 1, profile.nativeIncarnation(), claim.claimId());
        }
    }
    static Claim claim(byte[] body) {
        var value = ControlledProviderJson.parse(new String(body, StandardCharsets.UTF_8), ControlLifecycleCodec.MAX_HTTP_BODY_BYTES);
        if (!value.has("nativeOwnerClaim")) return null;
        var claim = CandidateLeaseCodec.decodeNativeOwnerClaim(value.get("nativeOwnerClaim").toString());
        var profile = CandidateLeaseCodec.decodeProfile(value.get("hostProfile") == null ? null : value.get("hostProfile").toString());
        if (value.has("hostProfileRevision") || value.has("candidateLeases") || value.has("keyRequestId")
                || profile.candidates().stream().anyMatch(c -> !c.type().equals("host")))
            throw new IllegalArgumentException("Native adoption requires a complete direct profile and installed keys");
        return new Claim(claim, profile, ControlFrameCodec.payloadDigest(body));
    }
    static boolean requiresAcknowledgement(ControlLifecycleCodec.Intent intent, byte[] originalBody) {
        if (!intent.operation().equals("heartbeat")) return false;
        ControlLifecycleCodec.verifyBody(intent, originalBody); return claim(originalBody) != null;
    }
    static JsonObject marker(ControlLifecycleCodec.Intent intent, byte[] originalBody, ControlLifecycleCodec.Receipt receipt) {
        ControlLifecycleCodec.verifyBody(intent, originalBody); ControlLifecycleCodec.verifyReceipt(receipt, intent);
        Claim claim = claim(originalBody);
        if (!intent.operation().equals("heartbeat") || !receipt.disposition().equals("committed") || claim == null)
            throw new IllegalArgumentException("Missing committed native adoption");
        var value = new JsonObject(); value.addProperty("version", 1); value.addProperty("sequence", intent.sequence());
        value.addProperty("intentDigest", ControlLifecycleCodec.intentDigest(intent));
        value.addProperty("receiptDigest", ControlFrameCodec.payloadDigest(ControlLifecycleCodec.encodeReceipt(receipt).getBytes(StandardCharsets.UTF_8)));
        value.addProperty("bodyDigest", intent.payloadSha256()); value.addProperty("profileSha256", CandidateLeaseCodec.profileDigest(claim.profile()));
        value.add("owner", JsonParser.parseString(CandidateLeaseCodec.encodeNativeOwner(claim.issued()))); return value;
    }
    static void validateMarker(JsonObject value) {
        if (value == null || !value.keySet().equals(Set.of("version", "sequence", "intentDigest", "receiptDigest", "bodyDigest", "profileSha256", "owner"))
                || ControlledProviderJson.number(value, "version") != 1 || ControlledProviderJson.number(value, "sequence") < 1)
            throw ControlledProviderJson.invalid();
        for (String field : Set.of("intentDigest", "receiptDigest", "bodyDigest", "profileSha256")) {
            String digest = ControlledProviderJson.string(value, field);
            if (!digest.matches("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")) throw ControlledProviderJson.invalid();
        }
        CandidateLeaseCodec.decodeNativeOwner(value.get("owner").toString());
    }
    private ControlledNativeOwner() { }
}
