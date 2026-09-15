package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandidateLeaseCodecTest {
    private static final CandidateLeaseCodec.NativeOwner OWNER = new CandidateLeaseCodec.NativeOwner(5,
            "0123456789abcdef0123456789abcdef", "candidate_owner_claim_01");
    private static JsonObject fixtures() throws Exception {
        Path path = Path.of("docs/external-signaling/control-v1.candidate-leases.fixtures.json");
        if (!Files.exists(path)) path = Path.of("..").resolve(path);
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
    private static JsonObject fixture(String collection, String name) throws Exception {
        for (var value : fixtures().getAsJsonArray(collection)) {
            if (value.getAsJsonObject().get("name").getAsString().equals(name)) return value.getAsJsonObject();
        }
        throw new AssertionError(name);
    }
    private static CandidateLeaseCodec.Profile profile() throws Exception {
        return CandidateLeaseCodec.decodeProfile(fixture("profiles", "dual-srflx").get("profile").toString());
    }
    private static CandidateLeaseCodec.Leases leases() throws Exception {
        return CandidateLeaseCodec.decodeLeases(fixture("leases", "dual-srflx").get("candidateLeases").toString());
    }

    @Test void independentNodePreimagesAndDigestsMatchForEveryClosedProfileField() throws Exception {
        var digests = new HashSet<String>();
        for (var value : fixtures().getAsJsonArray("profiles")) {
            var vector = value.getAsJsonObject();
            var profile = CandidateLeaseCodec.decodeProfile(vector.get("profile").toString());
            assertEquals(vector.get("preimageUtf8").getAsString(), new String(CandidateLeaseCodec.profilePreimage(profile), StandardCharsets.UTF_8));
            assertEquals(vector.get("sha256").getAsString(), CandidateLeaseCodec.profileDigest(profile));
            assertEquals(vector.get("profile"), JsonParser.parseString(CandidateLeaseCodec.encodeProfile(profile)));
            assertTrue(digests.add(CandidateLeaseCodec.profileDigest(profile)), vector.get("name").getAsString());
        }
    }

    @Test void independentObservationAndCompleteLeaseBytesMatchBothRuntimes() throws Exception {
        for (var value : fixtures().getAsJsonArray("observations")) {
            var vector = value.getAsJsonObject(); String incarnation = vector.get("nativeIncarnation").getAsString();
            var observation = CandidateLeaseCodec.decodeObservation(vector.get("observation").toString());
            long epoch = vector.get("nativeOwnerEpoch").getAsLong();
            assertEquals(vector.get("preimageUtf8").getAsString(), new String(CandidateLeaseCodec.observationPreimage(epoch, incarnation, observation), StandardCharsets.UTF_8));
            assertEquals(vector.get("sha256").getAsString(), CandidateLeaseCodec.observationDigest(epoch, incarnation, observation));
            assertEquals(vector.get("observation"), JsonParser.parseString(CandidateLeaseCodec.encodeObservation(observation)));
        }
        for (var value : fixtures().getAsJsonArray("leases")) {
            var vector = value.getAsJsonObject();
            var leases = CandidateLeaseCodec.decodeLeases(vector.get("candidateLeases").toString());
            var profile = CandidateLeaseCodec.decodeProfile(fixture("profiles", vector.get("profile").getAsString()).get("profile").toString());
            assertEquals(vector.get("preimageUtf8").getAsString(), new String(CandidateLeaseCodec.leasesPreimage(leases), StandardCharsets.UTF_8));
            assertEquals(vector.get("sha256").getAsString(), CandidateLeaseCodec.leasesDigest(leases));
            assertEquals(vector.get("candidateLeases"), JsonParser.parseString(CandidateLeaseCodec.encodeLeases(leases)));
            CandidateLeaseCodec.requireAssociation(profile, leases);
            var receipt = CandidateLeaseCodec.decodeReceipt(vector.get("receipt").toString());
            assertEquals(vector.get("receipt"), JsonParser.parseString(CandidateLeaseCodec.encodeReceipt(receipt)));
            assertTrue(CandidateLeaseCodec.matches(receipt, receipt.hostProfileRevision(), leases));
        }
    }

    @Test void observationBytesSurviveRekeyingAndExactAddressTextChanges() throws Exception {
        var leases = leases();
        var rebound = CandidateLeaseCodec.decodeProfile(fixture("profiles", "same-observations-new-key").get("profile").toString());
        var next = CandidateLeaseCodec.bind(rebound, OWNER, leases.observations());
        assertEquals(leases.observations(), next.observations());
        assertNotEquals(leases.profileSha256(), next.profileSha256());
        assertNotEquals(CandidateLeaseCodec.leasesDigest(leases), CandidateLeaseCodec.leasesDigest(next));
        assertEquals(leases.expiresAt(), next.expiresAt());
        var expanded = CandidateLeaseCodec.decodeProfile(fixture("profiles", "exact-expanded-ipv6").get("profile").toString());
        assertEquals(leases.observations(), CandidateLeaseCodec.bind(expanded, OWNER, leases.observations()).observations());
    }

    @Test void modelsOwnListsAndExternalJsonCannotCoerceOrLaterChangeProfile() throws Exception {
        var profile = profile(); var input = new ArrayList<>(profile.candidates());
        var owned = new CandidateLeaseCodec.Profile(input, profile.capability(), profile.nativeIncarnation(), profile.credentialKeyId(),
                profile.dtlsFingerprint(), profile.maxMessageSize(), profile.sctpPort());
        input.clear(); assertEquals(2, owned.candidates().size());
        assertThrows(UnsupportedOperationException.class, () -> owned.candidates().clear());
        var observationInput = new ArrayList<>(leases().observations());
        var leases = CandidateLeaseCodec.bind(profile, OWNER, observationInput);
        observationInput.clear(); assertEquals(2, leases.observations().size());
        assertThrows(UnsupportedOperationException.class, () -> leases.observations().clear());
        var json = JsonParser.parseString(CandidateLeaseCodec.encodeProfile(profile)).getAsJsonObject();
        var read = CandidateLeaseCodec.readProfile(json); json.addProperty("credentialKeyId", "B002");
        assertEquals("A001", read.credentialKeyId());
        json.getAsJsonArray("candidates").get(0).getAsJsonObject().addProperty("port", 1.5);
        assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.readProfile(json));
    }

    @Test void closedShapesAndVersionsRejectEveryMissingNullOrExtraMember() throws Exception {
        var profile = fixture("profiles", "dual-srflx").getAsJsonObject("profile");
        for (String field : List.copyOf(profile.keySet())) {
            var missing = profile.deepCopy(); missing.remove(field);
            assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.decodeProfile(missing.toString()), field);
            var nil = profile.deepCopy(); nil.add(field, JsonNull.INSTANCE);
            assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.decodeProfile(nil.toString()), field);
        }
        var lease = fixture("leases", "dual-srflx").getAsJsonObject("candidateLeases");
        for (String field : List.copyOf(lease.keySet())) {
            var missing = lease.deepCopy(); missing.remove(field);
            assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.decodeLeases(missing.toString()), field);
            var nil = lease.deepCopy(); nil.add(field, JsonNull.INSTANCE);
            assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.decodeLeases(nil.toString()), field);
        }
        var extra = lease.deepCopy(); extra.addProperty("acceptedSha256", leases().profileSha256());
        assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.decodeLeases(extra.toString()));
        for (int version : List.of(0, 2, 3)) {
            var changed = lease.deepCopy(); changed.addProperty("version", version);
            assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.decodeLeases(changed.toString()));
        }
        profile.addProperty("version", 1);
        assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.decodeProfile(profile.toString()));
    }

    @Test void strictIntegerTokensRejectFractionsExponentsStringsNegativeZeroAndOverflow() throws Exception {
        String original = CandidateLeaseCodec.encodeObservation(leases().observations().get(0));
        for (String field : List.of("port", "monitorEpoch", "mappingRevision", "observationSequence", "observedAt", "expiresAt")) {
            for (String token : List.of("1.0", "1.1", "1e0", "\"1\"", "-0", "-1", "9007199254740992", "18446744073709551616")) {
                String changed = original.replaceFirst("\"" + field + "\":[0-9]+", "\"" + field + "\":" + token);
                assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.decodeObservation(changed), field + ":" + token);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new CandidateLeaseCodec.Observation("ipv4", "08080808", 1,
                9007199254740992L, 1, 1, 0, 1));
    }

    @Test void digestsRequireExactlyCanonicalUnpaddedBase64url() throws Exception {
        String digest = leases().profileSha256();
        int index = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".indexOf(digest.charAt(42));
        String noncanonical = digest.substring(0, 42) + "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".charAt(index + 1);
        for (String value : List.of(digest + "=", noncanonical, "a".repeat(64), "a".repeat(42), "a".repeat(44), "+".repeat(43), "/".repeat(43))) {
            assertThrows(IllegalArgumentException.class, () -> new CandidateLeaseCodec.Leases(value, 5, leases().nativeIncarnation(), List.of()), value);
        }
        assertEquals(43, digest.length());
    }

    @Test void boundedCollectionsRejectDuplicateFamiliesOrderAndOversizedProfiles() throws Exception {
        var lease = leases(); var v4 = lease.observations().get(0); var v6 = lease.observations().get(1);
        for (List<CandidateLeaseCodec.Observation> values : List.of(List.of(v4, v4), List.of(v6, v4), List.of(v4, v6, v4)))
            assertThrows(IllegalArgumentException.class, () -> new CandidateLeaseCodec.Leases(lease.profileSha256(), 5, lease.nativeIncarnation(), values));
        var p = profile();
        assertThrows(IllegalArgumentException.class, () -> new CandidateLeaseCodec.Profile(java.util.Collections.nCopies(33, p.candidates().get(0)),
                p.capability(), p.nativeIncarnation(), p.credentialKeyId(), p.dtlsFingerprint(), p.maxMessageSize(), p.sctpPort()));
        assertThrows(IllegalArgumentException.class, () -> new CandidateLeaseCodec.Profile(List.of(p.candidates().get(0), p.candidates().get(0)),
                p.capability(), p.nativeIncarnation(), p.credentialKeyId(), p.dtlsFingerprint(), p.maxMessageSize(), p.sctpPort()));
    }

    @Test void strictParserRejectsDuplicateFieldsTrailingBytesBomAndBoundedInputs() throws Exception {
        String wire = CandidateLeaseCodec.encodeLeases(leases());
        for (String invalid : List.of(wire.replace("\"version\":1", "\"version\":1,\"version\":1"), wire + "{}", "\uFEFF" + wire,
                " ".repeat(CandidateLeaseCodec.MAX_LEASE_BYTES) + wire, "null"))
            assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.decodeLeases(invalid));
        var o = leases().observations().get(0);
        for (String hex : List.of("0808080A", "080808", "00000000000000000000000008080808"))
            assertThrows(IllegalArgumentException.class, () -> new CandidateLeaseCodec.Observation("ipv4", hex, o.port(), 1, 1, 1, 0, 1));
    }

    @Test void parsingNumericNonpublicProfilesIsSeparateFromPublicLeaseAssociation() throws Exception {
        var original = fixture("profiles", "dual-srflx").getAsJsonObject("profile");
        for (String address : List.of("127.0.0.1", "192.168.1.2", "203.0.113.1", "224.0.0.1", "0.0.0.0")) {
            var changed = original.deepCopy(); changed.getAsJsonArray("candidates").get(0).getAsJsonObject().addProperty("address", address);
            var profile = CandidateLeaseCodec.decodeProfile(changed.toString());
            var observations = new ArrayList<>(leases().observations()); var o = observations.get(0);
            observations.set(0, new CandidateLeaseCodec.Observation("ipv4", CandidateLeaseCodec.addressHex(address), o.port(),
                    o.monitorEpoch(), o.mappingRevision(), o.observationSequence(), o.observedAt(), o.expiresAt()));
            assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.bind(profile, OWNER, observations), address);
        }
        for (String address : List.of("example.com", "[2606:4700:4700::1111]", "fe80::1%eth0", " 8.8.8.8", "8.8.8", "256.0.0.1",
                "08.8.8.8", "::ffff:8.08.8.8")) {
            var changed = original.deepCopy(); changed.getAsJsonArray("candidates").get(0).getAsJsonObject().addProperty("address", address);
            assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.decodeProfile(changed.toString()), address);
        }
        assertEquals("08080808", CandidateLeaseCodec.addressHex("::ffff:8.8.8.8"));
    }

    @Test void coverageRejectsMissingExtraWrongOwnerWrongPortAndDuplicateNumericEndpoint() throws Exception {
        var p = profile(); var lease = leases();
        assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.bind(p, OWNER, List.of(lease.observations().get(0))));
        assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.requireAssociation(p,
                new CandidateLeaseCodec.Leases(lease.profileSha256(), 5, "f".repeat(32), lease.observations())));
        var altered = new ArrayList<>(lease.observations()); var o = altered.get(0);
        altered.set(0, new CandidateLeaseCodec.Observation(o.family(), o.addressHex(), o.port() + 1, o.monitorEpoch(), o.mappingRevision(),
                o.observationSequence(), o.observedAt(), o.expiresAt()));
        assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.bind(p, OWNER, altered));
        var candidates = new ArrayList<>(p.candidates()); var c = candidates.get(0);
        candidates.set(1, new CandidateLeaseCodec.Candidate(c.address(), c.port(), 1, "another", c.priority(), "udp", "srflx"));
        assertThrows(IllegalArgumentException.class, () -> new CandidateLeaseCodec.Profile(candidates, p.capability(), p.nativeIncarnation(), p.credentialKeyId(),
                p.dtlsFingerprint(), p.maxMessageSize(), p.sctpPort()));
        assertThrows(IllegalArgumentException.class, () -> new CandidateLeaseCodec.Observation("ipv6", "00000000000000000000ffff08080808",
                43000, 1, 1, 1, 0, 270000));
    }

    @Test void finalAcceptanceUsesOriginalReceiveAndStrictActualCommitExpiry() throws Exception {
        var leases = leases(); long observed = leases.observations().get(0).observedAt();
        CandidateLeaseCodec.requireAcceptableAt(leases, observed, leases.expiresAt() - 1);
        assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.requireAcceptableAt(leases, observed, leases.expiresAt()));
        assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.requireAcceptableAt(leases, observed - 30001, observed));
        assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.requireAcceptableAt(leases, 9007199254740991L, observed));
        var o = leases.observations().get(0);
        assertThrows(IllegalArgumentException.class, () -> new CandidateLeaseCodec.Observation(o.family(), o.addressHex(), o.port(), 1, 1, 1, observed, observed));
        assertThrows(IllegalArgumentException.class, () -> new CandidateLeaseCodec.Observation(o.family(), o.addressHex(), o.port(), 1, 1, 1,
                observed, observed + 270001));
        var empty = new CandidateLeaseCodec.Leases(leases.profileSha256(), 5, leases.nativeIncarnation(), List.of());
        assertEquals(0, empty.expiresAt());
        assertFalse(CandidateLeaseCodec.matches(CandidateLeaseCodec.receipt("hpr_original", leases), "hpr_another", leases));
    }

    @Test void nativeOwnerClaimAndIssuedTupleUseClosedSafeIntegerBytesAndExactOriginalClaim() throws Exception {
        for (var value : fixtures().getAsJsonArray("nativeOwners")) {
            var vector = value.getAsJsonObject();
            var claim = CandidateLeaseCodec.decodeNativeOwnerClaim(vector.get("claim").toString());
            var owner = CandidateLeaseCodec.decodeNativeOwner(vector.get("owner").toString());
            assertEquals(vector.get("claim"), JsonParser.parseString(CandidateLeaseCodec.encodeNativeOwnerClaim(claim)));
            assertEquals(vector.get("owner"), JsonParser.parseString(CandidateLeaseCodec.encodeNativeOwner(owner)));
            assertTrue(CandidateLeaseCodec.matchesClaim(claim, owner, OWNER.nativeIncarnation()));
            assertFalse(CandidateLeaseCodec.matchesClaim(claim, owner, "f".repeat(32)));
            assertFalse(CandidateLeaseCodec.matchesClaim(new CandidateLeaseCodec.NativeOwnerClaim(claim.expectedEpoch(), "candidate_owner_claim_02"),
                    owner, OWNER.nativeIncarnation()));
        }
        assertThrows(IllegalArgumentException.class, () -> new CandidateLeaseCodec.NativeOwnerClaim(9007199254740991L, OWNER.claimId()));
        assertThrows(IllegalArgumentException.class, () -> new CandidateLeaseCodec.NativeOwner(0, OWNER.nativeIncarnation(), OWNER.claimId()));
        assertThrows(IllegalArgumentException.class, () -> new CandidateLeaseCodec.NativeOwnerClaim(0, "short"));
        for (String token : List.of("-0", "1.5", "1e0", "\"1\"", "9007199254740992")) {
            String claim = "{\"version\":1,\"expectedEpoch\":" + token + ",\"claimId\":\"candidate_owner_claim_01\"}";
            assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.decodeNativeOwnerClaim(claim));
        }
        var extra = JsonParser.parseString(CandidateLeaseCodec.encodeNativeOwner(OWNER)).getAsJsonObject(); extra.addProperty("accepted", true);
        assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.decodeNativeOwner(extra.toString()));
        var leases = leases(); var other = new CandidateLeaseCodec.Leases(leases.profileSha256(), 6, leases.nativeIncarnation(), leases.observations());
        assertNotEquals(CandidateLeaseCodec.leasesDigest(leases), CandidateLeaseCodec.leasesDigest(other));
        assertNotEquals(CandidateLeaseCodec.observationDigest(5, OWNER.nativeIncarnation(), leases.observations().get(0)),
                CandidateLeaseCodec.observationDigest(6, OWNER.nativeIncarnation(), leases.observations().get(0)));
        assertThrows(IllegalArgumentException.class, () -> CandidateLeaseCodec.bind(profile(),
                new CandidateLeaseCodec.NativeOwner(6, "f".repeat(32), OWNER.claimId()), leases.observations()));
    }
}
