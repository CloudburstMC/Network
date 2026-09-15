# Draft provider-signed diagnostic answer

This completes the standalone signed-answer codec alongside `diagnostic-v1.md`. It does not mount a facade, authenticate a workload, enable a native diagnostic handler, publish a key catalog or establish reachability. The provider signer remains provider-owned; no reusable provider/private admission key is given to a prober or game server.

The trusted expected input contains the installed diagnostic context, original claims/remote ICE ufrag and the **expected host DTLS fingerprint** from the authorized host/job. The original detached request transcript contains the client fingerprint, so the host fingerprint must not be learned from an unsigned answer. Exact answer bytes cryptographically bind it to the provider signature.

## Canonical response and signature

The only response is a compact, ASCII, duplicate-free JSON object in this exact order:

```
{"version":1,"kind":"diagnostic-answer","keyId":"provider-key-id","expiresAt":1788484830000,"requestDigestHex":"...64 lowercase hex...","answerSdpBase64":"...","answerDigestHex":"...64 lowercase hex...","signatureBase64":"..."}
```

`expiresAt` is exactly the original fixed attempt expiry. It is never restamped, extended or silently shortened. `requestDigestHex` is full SHA-256 of the **exact detached diagnostic assertion transcript**, which binds context/incarnation/generation, attempt, offer digest, client DTLS/ICE credentials, target/revision/family, bounds profile and fixed expiry. `answerDigestHex` is SHA-256 of the exact SDP UTF-8 bytes. The answer is not reconstructed, rewritten or normalized before hashing.

Base64 is standard unpadded base64 with canonical unused bits; the raw ES384 signature is 96-byte P1363 `r || s`. Key IDs are ASCII `[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}`. Canonical decoding requires exact equality with the canonical encoder output, rejecting duplicate/unknown fields, reordered keys, escaped aliases, whitespace, decimal/exponent alternatives and lossy numbers. A primitive-only lexical check rejects nested JSON before allocating nested parser structures. SDP is bounded to 16,384 bytes and the complete ASCII JSON to 24,576 bytes, checked before copying/parsing.

All integers below are unsigned big endian. The signed bytes are:

```
UTF8("nxs-diagnostic-answer-v1\0")
|| uint16(keyIdASCII.length) || keyIdASCII
|| uint64(fixedExpiresAtMillis)
|| requestDigest32 || answerDigest32 || uint32(exactSdpByteLength)
```

No player assertion, machine signature, control proof or unknown signature purpose is accepted as a diagnostic answer.

## Trusted key authority

Both sign and verify accept a synchronous **trusted cached catalog reader**. Each read returns `(providerOrigin, notBefore, expiresAt, keys)`; one to eight unique keys are allowed. Each key has explicit family `provider-diagnostic`, key ID, canonical 97-byte P-384 public point, `validFrom` and `validUntil`. The canonical public point allows exact material comparison across Java/WebCrypto objects without relying on object identity or extractability. The signer separately supplies a matching private ES384 key with the same explicit family and key ID, and self-verifies the signature against the catalog.

The catalog origin must match the expected provider context. Current parent authority and the selected key must cover the **entire original fixed attempt deadline**. At final signature/verification delivery, the reader is called again: origin, key family/ID/public material/window must still match and the current parent must still cover that deadline. Removal, replacement, retirement shortening, future authority or an unavailable reader fails closed. An unrelated catalog change may be accepted if the exact selected key tuple and required parent coverage remain valid.

Catalog constructors and these checks do **not** establish workload authorization, target ownership, source freshness or key-catalog authenticity. The trusted parent service must supply those properties and the original absolute source/key deadlines. Never derive a trusted catalog from answer fields or fetch a key URL named by an answer. The reader must perform no network I/O or unbounded work.

## SDP validation and delivery

Both issuer and verifier check the actual answer against the closed profile: one UDP/DTLS/SCTP application/mid 0, BUNDLE 0, full ICE, setup `active` (matching the existing NXS answer builder), SCTP 5000/max-message-size 262144, completed gathering and one numeric target candidate. Its family/address/port must exactly match the authorized request. `host` and `srflx` are allowed; related-address metadata is bounded numeric metadata, not an alternate destination. Unknown candidate types, relay/TCP, invalid media ports, ambiguous/indented duplicate attributes, player identity markers and NXS1 player credentials are rejected. The NXD1 username and 32-character password must have the expected carrier shape. Without a host secret the answer verifier cannot decrypt that capability; the provider signature authenticates its exact bytes.

Candidate type is the provider's authenticated assertion. Actual selected candidate pair, transport family, pinned DTLS, channel labels/reliability and successful challenge exchange still require native runtime observation. An SDP candidate or a valid signature is not reachability evidence.

`VerifiedDiagnosticAnswer` has no public constructor. It retains owned bytes and the original deadline; no raw SDP getter is available. `takeSdp()` is one-use and synchronously rechecks current catalog/key/parent, cancellation, wall-clock expiry and a finite/nondecreasing monotonic deadline immediately before exposing an owned byte copy. Apply that copy immediately, with no intervening await. A second or reentrant call, closed/cancelled result, expired result or changed authority fails. Consumption is reserved before invoking trusted callbacks; closing from a callback prevents release. Closing erases the codec's retained copy; it cannot recall a copy already handed to the caller. The transport owner must continue enforcing deadlines/revocation and pin the verified expected host fingerprint when applying the SDP.

## Verification boundary

Shared public fixtures cover IPv4/IPv6 and host/srflx descriptors. TypeScript-generated signatures are verified by Java; fresh Java-signed responses are verified by TypeScript, with exact transcript/hash/SDP checks. Negative tests include trusted fingerprint/attempt/offer/endpoint/family mismatch; canonical JSON mutations; valid signatures over incompatible SDP; wrong family/key/catalog; key changes during crypto and before take; cancellation, slow/invalid/backward clocks; oversized inputs and repeated/closed delivery.

These are codec interoperability and delivery-boundary tests. The live workload/facade operation, diagnostic-purpose dispatch before every game pipeline, authentic native DTLS/SCTP/channel exchange, send-path UDP budgets, regional source qualification, independent selected-path observations and stock-client gameplay remain separate gates.
