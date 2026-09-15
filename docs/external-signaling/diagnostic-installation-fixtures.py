#!/usr/bin/env python3
"""Independent ASCII JSON/SHA-256 vectors for the draft installation contract."""
import base64
import copy
import hashlib
import ipaddress
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
def wire(value):
    return json.dumps(value, ensure_ascii=True, separators=(",", ":"))
def digest(text):
    return base64.urlsafe_b64encode(hashlib.sha256(text.encode("utf-8")).digest()).decode().rstrip("=")
def public_point(value):
    if isinstance(value, dict):
        if "publicPointHex" in value:
            return value["publicPointHex"]
        for item in value.values():
            found = public_point(item)
            if found:
                return found
    if isinstance(value, list):
        for item in value:
            found = public_point(item)
            if found:
                return found

def preimage(d):
    b = d["binding"]
    return wire(["nethernet-control-diagnostic-installation-v1", 1,
        [b[k] for k in ["providerOrigin", "hostId", "authorityIncarnation", "generation", "nativeOwnerEpoch", "nativeIncarnation",
            "hostProfileRevision", "hostProfileSha256", "hostFingerprintHex", "policyRevision"]],
        d["notBefore"], d["expiresAt"], d["activeKeyId"],
        [[k["keyId"], digest(k["secret"]), k["notBefore"], k["retireAt"]] for k in d["keys"]],
        [[e[k] for k in ["family", "addressHex", "port", "candidateRevision", "candidateType", "expiresAt"]] for e in d["endpoints"]],
        [d["answerCatalog"]["providerOrigin"], d["answerCatalog"]["notBefore"], d["answerCatalog"]["expiresAt"],
            [[k[p] for p in ["family", "keyId", "publicPointHex", "validFrom", "validUntil"]] for k in d["answerCatalog"]["keys"]]]])

def schema():
    def integer(minimum=0, maximum=9_007_199_254_740_991):
        return {"type": "integer", "minimum": minimum, "maximum": maximum}
    def string(pattern, maximum=None):
        return {"type": "string", "pattern": pattern, **({"maxLength": maximum} if maximum else {})}
    def closed(properties):
        return {"type": "object", "additionalProperties": False, "required": list(properties), "properties": properties}
    def array(ref, maximum, minimum=0):
        return {"type": "array", "minItems": minimum, "maxItems": maximum, "items": {"$ref": "#/$defs/" + ref}}
    identifier = string("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")
    sha = string("^[A-Za-z0-9_-]{43}$")
    origin = string("^https://", 256)
    key_id = string("^[A-Z0-9]{4}$")
    binding = closed({"providerOrigin": origin, "hostId": string("^[A-Za-z0-9_-]{1,128}$"),
        "authorityIncarnation": identifier, "generation": integer(1), "nativeOwnerEpoch": integer(1),
        "nativeIncarnation": string("^[0-9a-f]{32}$"), "hostProfileRevision": identifier,
        "hostProfileSha256": sha, "hostFingerprintHex": string("^[0-9a-f]{64}$"),
        "policyRevision": integer(1), "installationSha256": sha})
    epoch = closed({"keyId": key_id, "secret": string(r"^[\x21-\x7e]{32,256}$"), "notBefore": integer(), "retireAt": integer(1)})
    endpoint = closed({"family": {"enum": [4, 6]}, "addressHex": string("^[0-9a-f]{32}$"), "port": integer(1, 65535),
        "candidateRevision": integer(1), "candidateType": {"enum": ["host", "srflx"]}, "expiresAt": integer(1)})
    endpoint["allOf"] = [{"if": {"properties": {"family": {"const": 4}}},
        "then": {"properties": {"addressHex": string("^000000000000000000000000[0-9a-f]{8}$")}},
        "else": {"properties": {"addressHex": {"not": {"pattern": "^00000000000000000000ffff"}}}}}]
    answer_key = closed({"family": {"const": "provider-diagnostic"}, "keyId": identifier,
        "publicPointHex": string("^04[0-9a-f]{192}$"), "validFrom": integer(), "validUntil": integer(1)})
    catalog = closed({"providerOrigin": origin, "notBefore": integer(), "expiresAt": integer(1), "keys": array("answerKey", 8, 1)})
    install = closed({"version": {"const": 1}, "binding": {"$ref": "#/$defs/binding"}, "notBefore": integer(), "expiresAt": integer(1),
        "activeKeyId": key_id, "keys": array("epoch", 8, 1), "endpoints": array("endpoint", 32), "answerCatalog": {"$ref": "#/$defs/answerCatalog"}})
    ack = closed({"version": {"const": 1}, "binding": {"$ref": "#/$defs/binding"}})
    return {"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "urn:nethernet:control-v1:diagnostic-installation",
        "$comment": "Structural schema only. The normative codec additionally checks canonical HTTPS origin/base64url, original duplicate-free integer bytes, sorted unique keys/endpoints/revisions, all parent/endpoint/key/catalog time relations, <=300000ms policy lifetime, digest consistency and encoded byte limits. Current trusted authority and actual native installation are separate checks.",
        "oneOf": [{"$ref": "#/$defs/installation"}, {"$ref": "#/$defs/acknowledgement"}],
        "$defs": {"binding": binding, "epoch": epoch, "endpoint": endpoint, "answerKey": answer_key,
            "answerCatalog": catalog, "installation": install, "acknowledgement": ack}}

def main():
    t = 1_800_000_000_000
    point = public_point(json.loads((HERE / "diagnostic-answer-v1.fixtures.json").read_text()))
    assert point and len(point) == 194 and point.startswith("04")
    d = {"version": 1, "binding": {"providerOrigin": "https://provider.example", "hostId": "host_diagnostic_fixture",
        "authorityIncarnation": "authority_incarnation_fixture", "generation": 7, "nativeOwnerEpoch": 3,
        "nativeIncarnation": "0123456789abcdef0123456789abcdef", "hostProfileRevision": "hpr_diagnostic_fixture_1",
        "hostProfileSha256": digest("test-only immutable profile 1"), "hostFingerprintHex": "ab" * 32,
        "policyRevision": 21, "installationSha256": digest("")}, "notBefore": t, "expiresAt": t + 300_000,
        "activeKeyId": "D002", "keys": [
            {"keyId": "D001", "secret": "test-only-diagnostic-previous-epoch-material", "notBefore": t - 300_000, "retireAt": t + 60_000},
            {"keyId": "D002", "secret": "test-only-diagnostic-current-epoch-material", "notBefore": t - 60_000, "retireAt": t + 900_000}],
        "endpoints": [
            {"family": 4, "addressHex": "0" * 24 + "c633640a", "port": 43000, "candidateRevision": 101, "candidateType": "srflx", "expiresAt": t + 90_000},
            {"family": 6, "addressHex": ipaddress.ip_address("2001:db8::a").packed.hex(), "port": 19132, "candidateRevision": 102, "candidateType": "host", "expiresAt": t + 300_000}],
        "answerCatalog": {"providerOrigin": "https://provider.example", "notBefore": t - 60_000, "expiresAt": t + 900_000,
            "keys": [{"family": "provider-diagnostic", "keyId": "diagnostic_answer_1", "publicPointHex": point,
                "validFrom": t - 60_000, "validUntil": t + 900_000}]}}
    docs = [("dual-family-independent-expiry", copy.deepcopy(d))]
    renewal = copy.deepcopy(d)
    renewal["binding"]["policyRevision"] += 1
    renewal["notBefore"] += 15_000
    renewal["expiresAt"] += 15_000
    for endpoint in renewal["endpoints"]:
        endpoint["expiresAt"] += 15_000
    docs.append(("same-endpoint-renewal", renewal))
    rebind = copy.deepcopy(d)
    rebind["binding"].update(hostProfileRevision="hpr_diagnostic_fixture_2", hostProfileSha256=digest("test-only immutable profile 2"), policyRevision=23)
    docs.append(("player-profile-rebind", rebind))
    empty = copy.deepcopy(d)
    empty["binding"].update(hostProfileRevision="hpr_diagnostic_fixture_empty", hostProfileSha256=digest("test-only empty profile"), policyRevision=24)
    empty["endpoints"] = []
    docs.append(("empty-withdrawal", empty))
    maximum = copy.deepcopy(d)
    maximum["endpoints"] = [{"family": 4, "addressHex": "0" * 24 + f"c63364{i:02x}", "port": 19132,
        "candidateRevision": 200 + i, "candidateType": "host", "expiresAt": t + 300_000} for i in range(1, 33)]
    docs.append(("maximum-32-endpoints", maximum))
    vectors = []
    for name, document in docs:
        text = preimage(document)
        document["binding"]["installationSha256"] = digest(text)
        ack = {"version": 1, "binding": document["binding"]}
        vectors.append({"name": name, "installation": document, "wire": wire(document), "preimageUtf8": text,
            "sha256": digest(text), "acknowledgement": ack, "acknowledgementWire": wire(ack)})
    result = {"format": "nethernet-control-diagnostic-installation-fixtures-v1", "scope": "Public test-only metadata; no installed authority or address ownership", "vectors": vectors}
    (HERE / "control-v1.diagnostic-installation.fixtures.json").write_text(json.dumps(result, indent=2) + "\n")
    (HERE / "control-v1.diagnostic-installation.schema.json").write_text(json.dumps(schema(), indent=2) + "\n")

if __name__ == "__main__":
    main()
