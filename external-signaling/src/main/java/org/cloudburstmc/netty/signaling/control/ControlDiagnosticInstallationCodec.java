package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Owned diagnostic installation metadata and its separate digest domain. Neither decoding nor
 * digest verification establishes current authority, native installation, or address ownership.
 */
public final class ControlDiagnosticInstallationCodec {
    public static final int MAX_INSTALLATION_BYTES = 24_576, MAX_ACKNOWLEDGEMENT_BYTES = 2_048;
    public static final long MAX_INSTALLATION_MILLIS = 300_000;

    public record Binding(String providerOrigin, String hostId, String authorityIncarnation,
                          long generation, long nativeOwnerEpoch, String nativeIncarnation,
                          String hostProfileRevision, String hostProfileSha256, String hostFingerprintHex,
                          long policyRevision, String installationSha256) {
        public Binding {
            origin(providerOrigin);
            if (hostId == null || !hostId.matches("[A-Za-z0-9_-]{1,128}")) throw invalid("host");
            ControlJson.identifier(authorityIncarnation); ControlJson.safe(generation, true);
            ControlJson.safe(nativeOwnerEpoch, true); hex(nativeIncarnation, 16);
            ControlJson.identifier(hostProfileRevision); ControlJson.digest(hostProfileSha256);
            hex(hostFingerprintHex, 32); ControlJson.safe(policyRevision, true); ControlJson.digest(installationSha256);
        }
    }

    public record Endpoint(int family, String addressHex, int port, long candidateRevision,
                           String candidateType, long expiresAt) {
        public Endpoint {
            hex(addressHex, 16);
            if (family != 4 && family != 6 || family == 4 && !addressHex.startsWith("000000000000000000000000")
                    || family == 6 && addressHex.startsWith("00000000000000000000ffff")
                    || port < 1 || port > 65535 || !"host".equals(candidateType) && !"srflx".equals(candidateType))
                throw invalid("endpoint");
            ControlJson.safe(candidateRevision, true); ControlJson.safe(expiresAt, true);
        }
    }

    public record Epoch(String keyId, String secret, long notBefore, long retireAt) {
        public Epoch {
            epochKeyId(keyId);
            if (secret == null || !secret.matches("[\\x21-\\x7e]{32,256}")) throw invalid("epoch secret");
            lifetime(notBefore, retireAt);
        }
        @Override public String toString() { return "DiagnosticEpoch[keyId=" + keyId + ", secret=redacted]"; }
    }

    public record AnswerKey(String family, String keyId, String publicPointHex, long validFrom, long validUntil) {
        public AnswerKey {
            if (!"provider-diagnostic".equals(family)) throw invalid("answer key purpose");
            ControlJson.identifier(keyId); hex(publicPointHex, 97);
            if (!publicPointHex.startsWith("04")) throw invalid("answer key point");
            lifetime(validFrom, validUntil);
        }
    }

    public record AnswerCatalog(String providerOrigin, long notBefore, long expiresAt, List<AnswerKey> keys) {
        public AnswerCatalog {
            origin(providerOrigin); lifetime(notBefore, expiresAt);
            keys = copy(keys, 1, 8, "answer keys");
            String previous = "";
            for (AnswerKey key : keys) {
                if (key.keyId().compareTo(previous) <= 0) throw invalid("answer key order");
                previous = key.keyId();
            }
        }
    }

    public record Installation(Binding binding, long notBefore, long expiresAt, String activeKeyId,
                               List<Epoch> keys, List<Endpoint> endpoints, AnswerCatalog answerCatalog) {
        public Installation {
            Objects.requireNonNull(binding); Objects.requireNonNull(answerCatalog); lifetime(notBefore, expiresAt);
            if (expiresAt - notBefore > MAX_INSTALLATION_MILLIS) throw invalid("installation lifetime");
            epochKeyId(activeKeyId); keys = copy(keys, 1, 8, "epochs"); endpoints = copy(endpoints, 0, 32, "endpoints");
            String previous = ""; boolean active = false;
            for (Epoch key : keys) {
                if (key.keyId().compareTo(previous) <= 0) throw invalid("epoch order");
                previous = key.keyId();
                if (key.keyId().equals(activeKeyId)) {
                    if (key.notBefore() > notBefore || key.retireAt() < expiresAt) throw invalid("active epoch lifetime");
                    active = true;
                }
            }
            if (!active) throw invalid("active epoch");
            Endpoint prior = null; var revisions = new HashSet<Long>();
            for (Endpoint endpoint : endpoints) {
                if (endpoint.expiresAt() <= notBefore || endpoint.expiresAt() > expiresAt
                        || prior != null && compare(prior, endpoint) >= 0 || !revisions.add(endpoint.candidateRevision()))
                    throw invalid("endpoint order or lifetime");
                prior = endpoint;
            }
            if (!answerCatalog.providerOrigin().equals(binding.providerOrigin())
                    || answerCatalog.notBefore() > notBefore || answerCatalog.expiresAt() < expiresAt)
                throw invalid("answer catalog scope or lifetime");
            boolean covering = false;
            for (AnswerKey key : answerCatalog.keys()) covering |= key.validFrom() <= notBefore && key.validUntil() >= expiresAt;
            if (!covering) throw invalid("answer key lifetime");
        }
        @Override public String toString() { return "DiagnosticInstallation[binding=" + binding + ", epochs=redacted]"; }
    }

    public record Acknowledgement(Binding binding) {
        public Acknowledgement { Objects.requireNonNull(binding); }
    }

    private ControlDiagnosticInstallationCodec() { }

    public static Installation decodeInstallation(String wire) {
        JsonObject object = ControlJson.parse(wire, MAX_INSTALLATION_BYTES);
        ControlJson.fields(object, "version", "binding", "notBefore", "expiresAt", "activeKeyId", "keys", "endpoints", "answerCatalog");
        ControlJson.version(object);
        var keys = new ArrayList<Epoch>();
        for (JsonObject key : objects(object, "keys")) {
            ControlJson.fields(key, "keyId", "secret", "notBefore", "retireAt");
            keys.add(new Epoch(ControlJson.string(key, "keyId"), ControlJson.string(key, "secret"),
                    ControlJson.number(key, "notBefore"), ControlJson.number(key, "retireAt")));
        }
        var endpoints = new ArrayList<Endpoint>();
        for (JsonObject endpoint : objects(object, "endpoints")) {
            ControlJson.fields(endpoint, "family", "addressHex", "port", "candidateRevision", "candidateType", "expiresAt");
            endpoints.add(new Endpoint(integer(endpoint, "family"), ControlJson.string(endpoint, "addressHex"), integer(endpoint, "port"),
                    ControlJson.number(endpoint, "candidateRevision"), ControlJson.string(endpoint, "candidateType"), ControlJson.number(endpoint, "expiresAt")));
        }
        JsonObject catalog = ControlJson.object(object, "answerCatalog");
        ControlJson.fields(catalog, "providerOrigin", "notBefore", "expiresAt", "keys");
        var answerKeys = new ArrayList<AnswerKey>();
        for (JsonObject key : objects(catalog, "keys")) {
            ControlJson.fields(key, "family", "keyId", "publicPointHex", "validFrom", "validUntil");
            answerKeys.add(new AnswerKey(ControlJson.string(key, "family"), ControlJson.string(key, "keyId"),
                    ControlJson.string(key, "publicPointHex"), ControlJson.number(key, "validFrom"), ControlJson.number(key, "validUntil")));
        }
        return new Installation(readBinding(ControlJson.object(object, "binding")), ControlJson.number(object, "notBefore"),
                ControlJson.number(object, "expiresAt"), ControlJson.string(object, "activeKeyId"), keys, endpoints,
                new AnswerCatalog(ControlJson.string(catalog, "providerOrigin"), ControlJson.number(catalog, "notBefore"),
                        ControlJson.number(catalog, "expiresAt"), answerKeys));
    }

    /** Own and strictly parse caller JSON before any number conversion or later asynchronous use. */
    public static Installation readInstallation(JsonObject input) { return decodeInstallation(input == null ? null : input.toString()); }

    public static String encodeInstallation(Installation installation) {
        Objects.requireNonNull(installation);
        JsonObject object = new JsonObject(); object.addProperty("version", 1); object.add("binding", bindingObject(installation.binding()));
        object.addProperty("notBefore", installation.notBefore()); object.addProperty("expiresAt", installation.expiresAt());
        object.addProperty("activeKeyId", installation.activeKeyId());
        JsonArray keys = new JsonArray();
        for (Epoch key : installation.keys()) {
            JsonObject item = new JsonObject(); item.addProperty("keyId", key.keyId()); item.addProperty("secret", key.secret());
            item.addProperty("notBefore", key.notBefore()); item.addProperty("retireAt", key.retireAt()); keys.add(item);
        }
        object.add("keys", keys); JsonArray endpoints = new JsonArray();
        for (Endpoint endpoint : installation.endpoints()) {
            JsonObject item = new JsonObject(); item.addProperty("family", endpoint.family()); item.addProperty("addressHex", endpoint.addressHex());
            item.addProperty("port", endpoint.port()); item.addProperty("candidateRevision", endpoint.candidateRevision());
            item.addProperty("candidateType", endpoint.candidateType()); item.addProperty("expiresAt", endpoint.expiresAt()); endpoints.add(item);
        }
        object.add("endpoints", endpoints); AnswerCatalog source = installation.answerCatalog(); JsonObject catalog = new JsonObject();
        catalog.addProperty("providerOrigin", source.providerOrigin()); catalog.addProperty("notBefore", source.notBefore());
        catalog.addProperty("expiresAt", source.expiresAt()); JsonArray answerKeys = new JsonArray();
        for (AnswerKey key : source.keys()) {
            JsonObject item = new JsonObject(); item.addProperty("family", key.family()); item.addProperty("keyId", key.keyId());
            item.addProperty("publicPointHex", key.publicPointHex()); item.addProperty("validFrom", key.validFrom());
            item.addProperty("validUntil", key.validUntil()); answerKeys.add(item);
        }
        catalog.add("keys", answerKeys); object.add("answerCatalog", catalog); return object.toString();
    }

    /** Fixed canonical arrays contain secret hashes; the claimed installation digest is deliberately excluded. */
    public static byte[] installationPreimage(Installation installation) {
        Objects.requireNonNull(installation);
        List<List<Object>> keys = installation.keys().stream().map(key -> List.<Object>of(key.keyId(),
                ControlFrameCodec.payloadDigest(key.secret().getBytes(StandardCharsets.UTF_8)), key.notBefore(), key.retireAt())).toList();
        List<List<Object>> endpoints = installation.endpoints().stream().map(endpoint -> List.<Object>of(endpoint.family(), endpoint.addressHex(),
                endpoint.port(), endpoint.candidateRevision(), endpoint.candidateType(), endpoint.expiresAt())).toList();
        AnswerCatalog catalog = installation.answerCatalog();
        List<List<Object>> answerKeys = catalog.keys().stream().map(key -> List.<Object>of(key.family(), key.keyId(), key.publicPointHex(),
                key.validFrom(), key.validUntil())).toList();
        return ControlProof.array("nethernet-control-diagnostic-installation-v1", 1, bindingArray(installation.binding()),
                installation.notBefore(), installation.expiresAt(), installation.activeKeyId(), keys, endpoints,
                List.of(catalog.providerOrigin(), catalog.notBefore(), catalog.expiresAt(), answerKeys));
    }

    public static String installationDigest(Installation installation) { return ControlFrameCodec.payloadDigest(installationPreimage(installation)); }

    /** Owned digest consistency only. Callers separately require current trusted authority and actual native installation. */
    public static Installation verifyInstallation(Installation installation) {
        if (!installationDigest(installation).equals(installation.binding().installationSha256())) throw invalid("installation digest");
        return installation;
    }

    public static Acknowledgement decodeAcknowledgement(String wire) {
        JsonObject object = ControlJson.parse(wire, MAX_ACKNOWLEDGEMENT_BYTES);
        ControlJson.fields(object, "version", "binding"); ControlJson.version(object);
        return new Acknowledgement(readBinding(ControlJson.object(object, "binding")));
    }

    public static String encodeAcknowledgement(Acknowledgement acknowledgement) {
        JsonObject object = new JsonObject(); object.addProperty("version", 1); object.add("binding", bindingObject(acknowledgement.binding()));
        return object.toString();
    }

    private static Binding readBinding(JsonObject binding) {
        ControlJson.fields(binding, "providerOrigin", "hostId", "authorityIncarnation", "generation", "nativeOwnerEpoch", "nativeIncarnation",
                "hostProfileRevision", "hostProfileSha256", "hostFingerprintHex", "policyRevision", "installationSha256");
        return new Binding(ControlJson.string(binding, "providerOrigin"), ControlJson.string(binding, "hostId"),
                ControlJson.string(binding, "authorityIncarnation"), ControlJson.number(binding, "generation"),
                ControlJson.number(binding, "nativeOwnerEpoch"), ControlJson.string(binding, "nativeIncarnation"),
                ControlJson.string(binding, "hostProfileRevision"), ControlJson.string(binding, "hostProfileSha256"),
                ControlJson.string(binding, "hostFingerprintHex"), ControlJson.number(binding, "policyRevision"), ControlJson.string(binding, "installationSha256"));
    }

    private static JsonObject bindingObject(Binding binding) {
        JsonObject object = new JsonObject(); object.addProperty("providerOrigin", binding.providerOrigin()); object.addProperty("hostId", binding.hostId());
        object.addProperty("authorityIncarnation", binding.authorityIncarnation()); object.addProperty("generation", binding.generation());
        object.addProperty("nativeOwnerEpoch", binding.nativeOwnerEpoch()); object.addProperty("nativeIncarnation", binding.nativeIncarnation());
        object.addProperty("hostProfileRevision", binding.hostProfileRevision()); object.addProperty("hostProfileSha256", binding.hostProfileSha256());
        object.addProperty("hostFingerprintHex", binding.hostFingerprintHex()); object.addProperty("policyRevision", binding.policyRevision());
        object.addProperty("installationSha256", binding.installationSha256()); return object;
    }

    private static List<Object> bindingArray(Binding binding) {
        return List.of(binding.providerOrigin(), binding.hostId(), binding.authorityIncarnation(), binding.generation(), binding.nativeOwnerEpoch(),
                binding.nativeIncarnation(), binding.hostProfileRevision(), binding.hostProfileSha256(), binding.hostFingerprintHex(), binding.policyRevision());
    }

    private static List<JsonObject> objects(JsonObject object, String name) {
        if (!object.get(name).isJsonArray()) throw invalid(name);
        List<JsonObject> result = new ArrayList<>();
        for (var value : object.getAsJsonArray(name)) {
            if (!value.isJsonObject()) throw invalid(name);
            result.add(value.getAsJsonObject());
        }
        return result;
    }

    private static int integer(JsonObject object, String field) {
        long value = ControlJson.number(object, field);
        if (value > Integer.MAX_VALUE) throw invalid(field);
        return (int) value;
    }
    private static void origin(String value) {
        ControlJson.audience(value);
        if (!value.startsWith("https://") || value.length() > 256) throw invalid("diagnostic origin");
    }
    private static void epochKeyId(String value) { if (value == null || !value.matches("[A-Z0-9]{4}")) throw invalid("epoch key"); }
    private static void hex(String value, int bytes) { if (value == null || !value.matches("[0-9a-f]{" + bytes * 2 + "}")) throw invalid("hex"); }
    private static void lifetime(long start, long end) { ControlJson.safe(start, false); ControlJson.safe(end, true); if (end <= start) throw invalid("lifetime"); }
    private static <T> List<T> copy(List<T> input, int minimum, int maximum, String field) {
        if (input == null || input.size() < minimum || input.size() > maximum || input.stream().anyMatch(Objects::isNull)) throw invalid(field);
        return List.copyOf(input);
    }
    private static int compare(Endpoint left, Endpoint right) {
        int order = Integer.compare(left.family(), right.family());
        if (order == 0) order = left.addressHex().compareTo(right.addressHex());
        return order == 0 ? Integer.compare(left.port(), right.port()) : order;
    }
    private static IllegalArgumentException invalid(String reason) { return ControlJson.invalid("diagnostic " + reason); }
}
