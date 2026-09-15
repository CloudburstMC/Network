package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.cloudburstmc.netty.util.nethernet.EndpointAddress;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Owned candidate-lease protocol values and ordered digest bytes. Parsing proves structure, not
 * authorization, native ownership, reachability or acceptance by a provider. No publication occurs.
 */
public final class CandidateLeaseCodec {
    public static final long MAX_SAFE_INTEGER = ControlJson.MAX_SAFE_INTEGER;
    public static final int MAX_PROFILE_BYTES = 16384, MAX_LEASE_BYTES = 4096, MAX_RECEIPT_BYTES = 1024;
    public static final long MAX_OBSERVATION_AGE_MILLIS = 300000, CLOCK_SKEW_MILLIS = 30000;
    public static final long MAX_LEASE_MILLIS = MAX_OBSERVATION_AGE_MILLIS - CLOCK_SKEW_MILLIS;
    public static final String ADMISSION_CAPABILITY = "nethernet.stateless-admission.v1";

    /** Exact profile spelling is retained; endpoint comparisons separately normalize address bytes. */
    public record Candidate(String address, int port, int component, String foundation, long priority,
                            String protocol, String type) {
        public Candidate {
            numericAddress(address); CandidateLeaseCodec.port(port);
            if (component != 1 || foundation == null || !foundation.matches("[A-Za-z0-9._:-]{1,32}")
                    || priority < 1 || priority > 2147483647L || !"udp".equals(protocol)
                    || !("host".equals(type) || "srflx".equals(type))) throw ControlJson.invalid("candidate");
        }
    }

    public record Profile(List<Candidate> candidates, String capability, String nativeIncarnation,
                          String credentialKeyId, String dtlsFingerprint, int maxMessageSize, int sctpPort) {
        public Profile {
            if (candidates == null || candidates.size() > 32) throw ControlJson.invalid("candidate count");
            candidates = List.copyOf(candidates);
            if (candidates.stream().distinct().count() != candidates.size()) throw ControlJson.invalid("duplicate candidate");
            if (candidates.stream().map(c -> addressHex(c.address()) + ":" + c.port()).distinct().count() != candidates.size())
                throw ControlJson.invalid("duplicate numeric endpoint");
            incarnation(nativeIncarnation);
            if (!ADMISSION_CAPABILITY.equals(capability) || credentialKeyId == null
                    || !credentialKeyId.matches("[A-Z0-9]{4}") || dtlsFingerprint == null
                    || !dtlsFingerprint.matches("sha-256 [0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){31}")
                    || maxMessageSize != 262144 || sctpPort != 5000) throw ControlJson.invalid("controlled profile");
        }
    }

    public record Observation(String family, String addressHex, int port, long monitorEpoch,
                              long mappingRevision, long observationSequence, long observedAt, long expiresAt) {
        public Observation {
            int length = "ipv4".equals(family) ? 8 : "ipv6".equals(family) ? 32 : 0;
            if (length == 0 || addressHex == null || !addressHex.matches("[0-9a-f]{" + length + "}"))
                throw ControlJson.invalid("observation address");
            if (length == 32 && addressHex.startsWith("00000000000000000000ffff"))
                throw ControlJson.invalid("unnormalized mapped IPv6 observation");
            CandidateLeaseCodec.port(port); ControlJson.safe(monitorEpoch, true); ControlJson.safe(mappingRevision, true);
            ControlJson.safe(observationSequence, true); ControlJson.safe(observedAt, false); ControlJson.safe(expiresAt, false);
            if (expiresAt <= observedAt || expiresAt - observedAt > MAX_LEASE_MILLIS)
                throw ControlJson.invalid("candidate lease lifetime");
        }
    }

    public record Leases(String profileSha256, long nativeOwnerEpoch, String nativeIncarnation, List<Observation> observations) {
        public Leases {
            ControlJson.digest(profileSha256); ControlJson.safe(nativeOwnerEpoch, true); incarnation(nativeIncarnation);
            if (observations == null || observations.size() > 2) throw ControlJson.invalid("observation count");
            observations = List.copyOf(observations);
            String previous = "";
            for (Observation observation : observations) {
                if (observation.family().compareTo(previous) <= 0) throw ControlJson.invalid("observation family order");
                previous = observation.family();
            }
        }
        /** Original minimum absolute expiry, or zero for the explicit empty association. */
        public long expiresAt() { return observations.stream().mapToLong(Observation::expiresAt).min().orElse(0); }
    }

    /** Receipt metadata alone is neither a native application acknowledgement nor lease authority. */
    public record Receipt(String hostProfileRevision, String profileSha256, String acceptedSha256, long expiresAt) {
        public Receipt {
            ControlJson.identifier(hostProfileRevision); ControlJson.digest(profileSha256);
            ControlJson.digest(acceptedSha256); ControlJson.safe(expiresAt, false);
        }
    }

    /** Intent for an authenticated full host-only/empty publication; this value does not issue an owner. */
    public record NativeOwnerClaim(long expectedEpoch, String claimId) {
        public NativeOwnerClaim {
            ControlJson.safe(expectedEpoch, false); ControlJson.opaque(claimId);
            if (expectedEpoch == MAX_SAFE_INTEGER) throw ControlJson.invalid("native owner epoch exhausted");
        }
    }

    /** Nonsecret issued tuple. Attaching it requires the original guarded claim and durable receipt. */
    public record NativeOwner(long epoch, String nativeIncarnation, String claimId) {
        public NativeOwner {
            ControlJson.safe(epoch, true); incarnation(nativeIncarnation); ControlJson.opaque(claimId);
        }
    }

    private CandidateLeaseCodec() { }

    public static Profile decodeProfile(String wire) {
        JsonObject value = ControlJson.parse(wire, MAX_PROFILE_BYTES);
        ControlJson.fields(value, "version", "candidates", "statelessAdmission", "credentialKeyId", "dtlsFingerprint", "maxMessageSize", "sctpPort");
        if (ControlJson.number(value, "version") != 2) throw ControlJson.invalid("controlled profile version");
        JsonObject admission = ControlJson.object(value, "statelessAdmission");
        ControlJson.fields(admission, "capability", "incarnation");
        if (!value.get("candidates").isJsonArray()) throw ControlJson.invalid("candidates");
        List<Candidate> candidates = new ArrayList<>();
        for (var item : value.getAsJsonArray("candidates")) {
            if (!item.isJsonObject()) throw ControlJson.invalid("candidate");
            JsonObject c = item.getAsJsonObject();
            ControlJson.fields(c, "address", "port", "component", "foundation", "priority", "protocol", "type");
            candidates.add(new Candidate(ControlJson.string(c, "address"), integer(c, "port"), integer(c, "component"),
                    ControlJson.string(c, "foundation"), ControlJson.number(c, "priority"),
                    ControlJson.string(c, "protocol"), ControlJson.string(c, "type")));
        }
        return new Profile(candidates, ControlJson.string(admission, "capability"), ControlJson.string(admission, "incarnation"),
                ControlJson.string(value, "credentialKeyId"), ControlJson.string(value, "dtlsFingerprint"),
                integer(value, "maxMessageSize"), integer(value, "sctpPort"));
    }

    /** Bound and own externally constructed JSON before its numeric values can be coerced. */
    public static Profile readProfile(JsonObject value) { return decodeProfile(value == null ? null : value.toString()); }

    public static String encodeProfile(Profile profile) {
        Objects.requireNonNull(profile);
        JsonObject value = new JsonObject(); value.addProperty("version", 2);
        JsonArray candidates = new JsonArray();
        for (Candidate c : profile.candidates()) {
            JsonObject item = new JsonObject(); item.addProperty("address", c.address()); item.addProperty("port", c.port());
            item.addProperty("component", c.component()); item.addProperty("foundation", c.foundation());
            item.addProperty("priority", c.priority()); item.addProperty("protocol", c.protocol()); item.addProperty("type", c.type());
            candidates.add(item);
        }
        value.add("candidates", candidates);
        JsonObject admission = new JsonObject(); admission.addProperty("capability", profile.capability());
        admission.addProperty("incarnation", profile.nativeIncarnation()); value.add("statelessAdmission", admission);
        value.addProperty("credentialKeyId", profile.credentialKeyId()); value.addProperty("dtlsFingerprint", profile.dtlsFingerprint());
        value.addProperty("maxMessageSize", profile.maxMessageSize()); value.addProperty("sctpPort", profile.sctpPort());
        return value.toString();
    }

    public static Leases decodeLeases(String wire) {
        JsonObject value = ControlJson.parse(wire, MAX_LEASE_BYTES);
        ControlJson.fields(value, "version", "profileSha256", "nativeOwnerEpoch", "nativeIncarnation", "observations"); ControlJson.version(value);
        if (!value.get("observations").isJsonArray() || value.getAsJsonArray("observations").size() > 2)
            throw ControlJson.invalid("observations");
        List<Observation> observations = new ArrayList<>();
        for (var item : value.getAsJsonArray("observations")) {
            if (!item.isJsonObject()) throw ControlJson.invalid("observation");
            observations.add(readObservation(item.getAsJsonObject()));
        }
        return new Leases(ControlJson.string(value, "profileSha256"), ControlJson.number(value, "nativeOwnerEpoch"),
                ControlJson.string(value, "nativeIncarnation"), observations);
    }

    public static Observation decodeObservation(String wire) {
        return readObservation(ControlJson.parse(wire, MAX_LEASE_BYTES));
    }

    private static Observation readObservation(JsonObject o) {
        ControlJson.fields(o, "family", "addressHex", "port", "monitorEpoch", "mappingRevision", "observationSequence", "observedAt", "expiresAt");
        return new Observation(ControlJson.string(o, "family"), ControlJson.string(o, "addressHex"), integer(o, "port"),
                ControlJson.number(o, "monitorEpoch"), ControlJson.number(o, "mappingRevision"),
                ControlJson.number(o, "observationSequence"), ControlJson.number(o, "observedAt"), ControlJson.number(o, "expiresAt"));
    }

    public static String encodeLeases(Leases leases) {
        Objects.requireNonNull(leases);
        JsonObject value = new JsonObject(); value.addProperty("version", 1); value.addProperty("profileSha256", leases.profileSha256());
        value.addProperty("nativeOwnerEpoch", leases.nativeOwnerEpoch());
        value.addProperty("nativeIncarnation", leases.nativeIncarnation()); JsonArray observations = new JsonArray();
        leases.observations().forEach(o -> observations.add(observationObject(o))); value.add("observations", observations);
        return value.toString();
    }

    public static String encodeObservation(Observation observation) { return observationObject(observation).toString(); }

    private static JsonObject observationObject(Observation o) {
        JsonObject value = new JsonObject(); value.addProperty("family", o.family()); value.addProperty("addressHex", o.addressHex());
        value.addProperty("port", o.port()); value.addProperty("monitorEpoch", o.monitorEpoch());
        value.addProperty("mappingRevision", o.mappingRevision()); value.addProperty("observationSequence", o.observationSequence());
        value.addProperty("observedAt", o.observedAt()); value.addProperty("expiresAt", o.expiresAt()); return value;
    }

    public static Receipt decodeReceipt(String wire) {
        JsonObject value = ControlJson.parse(wire, MAX_RECEIPT_BYTES);
        ControlJson.fields(value, "version", "hostProfileRevision", "profileSha256", "acceptedSha256", "expiresAt"); ControlJson.version(value);
        return new Receipt(ControlJson.string(value, "hostProfileRevision"), ControlJson.string(value, "profileSha256"),
                ControlJson.string(value, "acceptedSha256"), ControlJson.number(value, "expiresAt"));
    }

    public static String encodeReceipt(Receipt receipt) {
        JsonObject value = new JsonObject(); value.addProperty("version", 1); value.addProperty("hostProfileRevision", receipt.hostProfileRevision());
        value.addProperty("profileSha256", receipt.profileSha256()); value.addProperty("acceptedSha256", receipt.acceptedSha256());
        value.addProperty("expiresAt", receipt.expiresAt()); return value.toString();
    }

    public static NativeOwnerClaim decodeNativeOwnerClaim(String wire) {
        JsonObject value = ControlJson.parse(wire, MAX_RECEIPT_BYTES);
        ControlJson.fields(value, "version", "expectedEpoch", "claimId"); ControlJson.version(value);
        return new NativeOwnerClaim(ControlJson.number(value, "expectedEpoch"), ControlJson.string(value, "claimId"));
    }
    public static String encodeNativeOwnerClaim(NativeOwnerClaim claim) {
        JsonObject value = new JsonObject(); value.addProperty("version", 1);
        value.addProperty("expectedEpoch", claim.expectedEpoch()); value.addProperty("claimId", claim.claimId()); return value.toString();
    }
    public static NativeOwner decodeNativeOwner(String wire) {
        JsonObject value = ControlJson.parse(wire, MAX_RECEIPT_BYTES);
        ControlJson.fields(value, "version", "epoch", "nativeIncarnation", "claimId"); ControlJson.version(value);
        return new NativeOwner(ControlJson.number(value, "epoch"), ControlJson.string(value, "nativeIncarnation"), ControlJson.string(value, "claimId"));
    }
    public static String encodeNativeOwner(NativeOwner owner) {
        JsonObject value = new JsonObject(); value.addProperty("version", 1); value.addProperty("epoch", owner.epoch());
        value.addProperty("nativeIncarnation", owner.nativeIncarnation()); value.addProperty("claimId", owner.claimId()); return value.toString();
    }
    /** Pure receipt comparison. The caller still owns the original native guard and claim intent. */
    public static boolean matchesClaim(NativeOwnerClaim claim, NativeOwner owner, String nativeIncarnation) {
        incarnation(nativeIncarnation);
        return owner.epoch() == claim.expectedEpoch() + 1 && owner.claimId().equals(claim.claimId())
                && owner.nativeIncarnation().equals(nativeIncarnation);
    }

    public static byte[] profilePreimage(Profile profile) {
        return canonicalArray("nethernet-control-host-profile-v2", 2,
                profile.candidates().stream().map(c -> List.of(c.address(), c.port(), c.component(), c.foundation(), c.priority(), c.protocol(), c.type())).toList(),
                List.of(profile.capability(), profile.nativeIncarnation()), profile.credentialKeyId(), profile.dtlsFingerprint(),
                profile.maxMessageSize(), profile.sctpPort());
    }
    private static byte[] canonicalArray(Object... values) {
        java.util.StringJoiner result = new java.util.StringJoiner(",", "[", "]");
        for (Object value : values) {
            if (value instanceof String text) result.add(org.cloudburstmc.netty.signaling.ProviderCrypto.quote(text));
            else if (value instanceof List<?> list) result.add(new String(canonicalArray(list.toArray()), java.nio.charset.StandardCharsets.UTF_8));
            else result.add(String.valueOf(value));
        }
        return result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String payloadDigest(byte[] payload) {
        try {
            return org.cloudburstmc.netty.signaling.ProviderCrypto.base64(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String profileDigest(Profile profile) { return payloadDigest(profilePreimage(profile)); }
    public static byte[] observationPreimage(long nativeOwnerEpoch, String nativeIncarnation, Observation observation) {
        ControlJson.safe(nativeOwnerEpoch, true); incarnation(nativeIncarnation);
        return canonicalArray("nethernet-control-candidate-observation-v1", 1, nativeOwnerEpoch, nativeIncarnation, observationValues(observation));
    }
    public static String observationDigest(long nativeOwnerEpoch, String nativeIncarnation, Observation observation) {
        return payloadDigest(observationPreimage(nativeOwnerEpoch, nativeIncarnation, observation));
    }
    public static byte[] leasesPreimage(Leases leases) {
        return canonicalArray("nethernet-control-candidate-leases-v1", 1, leases.profileSha256(), leases.nativeOwnerEpoch(), leases.nativeIncarnation(),
                leases.observations().stream().map(CandidateLeaseCodec::observationValues).toList());
    }
    public static String leasesDigest(Leases leases) { return payloadDigest(leasesPreimage(leases)); }
    private static List<Object> observationValues(Observation o) {
        return List.of(o.family(), o.addressHex(), o.port(), o.monitorEpoch(), o.mappingRevision(), o.observationSequence(), o.observedAt(), o.expiresAt());
    }

    /** Associates unchanged observation bytes with a complete, possibly re-keyed profile. */
    public static Leases bind(Profile profile, NativeOwner owner, List<Observation> observations) {
        if (!owner.nativeIncarnation().equals(profile.nativeIncarnation())) throw ControlJson.invalid("native owner profile association");
        Leases leases = new Leases(profileDigest(profile), owner.epoch(), profile.nativeIncarnation(), observations);
        requireAssociation(profile, leases); return leases;
    }

    /** Publication boundary: exact association and one public numeric endpoint per reflexive entry. */
    public static void requireAssociation(Profile profile, Leases leases) {
        if (!profile.nativeIncarnation().equals(leases.nativeIncarnation()) || !profileDigest(profile).equals(leases.profileSha256()))
            throw ControlJson.invalid("candidate profile association");
        List<Candidate> reflexive = profile.candidates().stream().filter(c -> c.type().equals("srflx")).toList();
        if (reflexive.size() != leases.observations().size()) throw ControlJson.invalid("reflexive coverage");
        for (Observation o : leases.observations()) {
            long matches = reflexive.stream().filter(c -> {
                InetAddress address = numericAddress(c.address());
                return c.port() == o.port() && family(address).equals(o.family())
                        && addressHex(address).equals(o.addressHex()) && EndpointAddress.scope(address) == EndpointAddress.Scope.PUBLIC;
            }).count();
            if (matches != 1) throw ControlJson.invalid("public reflexive endpoint");
        }
    }

    /** Pure primary-acceptance time bounds. Historical replay must retain its original receipt. */
    public static void requireAcceptableAt(Leases leases, long originalReceivedAt, long finalCommitAt) {
        ControlJson.safe(originalReceivedAt, false); ControlJson.safe(finalCommitAt, false);
        long latestObservation = checkedSafeAdd(originalReceivedAt, CLOCK_SKEW_MILLIS);
        long latestExpiry = checkedSafeAdd(originalReceivedAt, MAX_OBSERVATION_AGE_MILLIS);
        for (Observation o : leases.observations()) {
            if (o.observedAt() > latestObservation || o.expiresAt() > latestExpiry || o.expiresAt() <= finalCommitAt)
                throw ControlJson.invalid("candidate lease acceptance time");
        }
    }

    public static Receipt receipt(String hostProfileRevision, Leases leases) {
        return new Receipt(hostProfileRevision, leases.profileSha256(), leasesDigest(leases), leases.expiresAt());
    }
    public static boolean matches(Receipt receipt, String hostProfileRevision, Leases leases) {
        return receipt.equals(receipt(hostProfileRevision, leases));
    }

    /** Numeric normalization only; never performs DNS or asserts public scope. */
    public static String addressHex(String literal) { return addressHex(numericAddress(literal)); }
    private static String addressHex(InetAddress address) { return HexFormat.of().formatHex(address.getAddress()); }
    private static String family(InetAddress address) { return address.getAddress().length == 4 ? "ipv4" : "ipv6"; }
    private static InetAddress numericAddress(String value) {
        if (value != null && value.indexOf('.') >= 0) {
            String dotted = value.substring(value.lastIndexOf(':') + 1);
            if (!dotted.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}"))
                throw ControlJson.invalid("numeric candidate address");
        }
        try { return EndpointAddress.parse(value); }
        catch (UnknownHostException failure) { throw ControlJson.invalid("numeric candidate address"); }
    }
    private static void incarnation(String value) {
        if (value == null || !value.matches("[0-9a-f]{32}")) throw ControlJson.invalid("native incarnation");
    }
    private static void port(int value) { if (value < 1 || value > 65535) throw ControlJson.invalid("port"); }
    private static int integer(JsonObject object, String field) {
        long value = ControlJson.number(object, field);
        if (value > Integer.MAX_VALUE) throw ControlJson.invalid(field);
        return (int) value;
    }
    private static long checkedSafeAdd(long left, long right) {
        long value = Math.addExact(left, right); ControlJson.safe(value, false); return value;
    }
}
