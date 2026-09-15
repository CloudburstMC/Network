# Draft independent diagnostic host completion v1

This opt-in contract carries an immutable native host observation. The codec and pure correlator do not themselves drain a native queue, enroll a target, schedule a probe, publish a report or change player readiness. The optional authenticated heartbeat upload and its immutable receipts are specified separately in `control-v1.diagnostic-completion-receipt.md`; implementation and deployment evidence belongs to each consumer's delivery record.

The wire schema and independent Python-generated public vectors are in `control-v1.diagnostic-completion.schema.json` and `control-v1.diagnostic-completion.fixtures.json`. Consumers must mirror these files and retain the reviewed Network commit in their shared-contract provenance inventory. The original installation binding uses `control-v1.diagnostic-installation.schema.json` without changing its grammar.

## Completion bytes

`ControlDiagnosticCompletion` is a closed JSON object with these fields, in canonical encoder order:

| Field | Meaning |
| --- | --- |
| `version` | Literal `1`. |
| `installation` | Full original admitting `ControlDiagnosticBinding`: provider origin, host, authority incarnation, generation, native owner epoch/incarnation, immutable profile revision/hash, host fingerprint, policy revision and installation hash. |
| `keyId` | Actual diagnostic admission epoch ID, four uppercase letters/digits. No epoch secret. |
| `attemptIdHex`, `offerDigestHex`, `clientFingerprintHex` | Original 16-byte attempt ID and full 32-byte offer/client DTLS hashes, lowercase hex. |
| `expiresAt` | Original permit deadline in integer Unix milliseconds, exact whole second, maximum `4294967295000`. Never restamped by completion, polling or upload. |
| `target` | `{family,addressHex,port,candidateRevision}` from the admitted permit. Full target policy digest and candidate type remain in the original immutable attempt. |
| `success`, `reason`, `completedAt`, `cleanupComplete` | Actual native outcome and cleanup completion time in integer Unix milliseconds. The reason is a closed symbolic enum, never arbitrary error text. |
| `completionDigestHex` | Verified four-direction/peer-completion transcript SHA256 hex on success; `null` on failure. No nonce values or partial direction claims. |
| `selectedLocal`, `selectedRemote` | Numeric `{family,addressHex,port}` tuples, or `null` when unobserved. Both families must match the permit. |
| `frames` | `{sent,sentBytes,received,receivedBytes}` observed application counters. |
| `udp` | `{reserved,sent,sentBytes,rejected}` actual native UDP counters, or `null` when unavailable. These are a pre-destruction snapshot; cleanup is reported independently. No largest-payload measurement is invented. |

The existing Network `NativeDiagnosticHostGate.Result` and neutral `DiagnosticAdmission.Completion` preserve these fields. The emitter must use the admission-time installation capture and check that its separate native `context` equals the binding's provider/host/native incarnation/generation. A null installation from an unassociated low-level fixture cannot be uploaded through this contract. Do not rebind a drained result to today's installation or reconstruct it from a mutable profile. Do not infer direction counts from aggregate frame counters: `DiagnosticExchange.complete()` and its digest are the host's four-direction proof.

IPv4 is packed as twelve zero bytes followed by the address. IPv6 uses sixteen network-order bytes and rejects IPv4-mapped addresses. Ports are `1..65535`. This is numeric syntax, not public endpoint enrollment or reachability policy. The host's selected local tuple can be a private gameplay-mux address/port behind NAT and differ from the public target. The native gate checks the owned listener and selected family before producing success. The prober's selected remote must equal the authorized public target; under the current dedicated numeric-source contract, the host's selected remote must equal the authorized source slot. Neither the codec nor correlation performs DNS or establishes NAT64 support.

Single completion input is limited to **4096 UTF-8 bytes**, nesting depth8, duplicate-free closed fields and lossless nonnegative integer tokens. SHA/base64url/origin grammar follows the existing control codec. Decoder accepts property ordering/whitespace variations; the encoder emits the fixed order above and existing binding order. The immutable completion digest is base64url SHA256 of UTF-8:

```text
["nethernet-control-diagnostic-completion-v1",<canonical completion object>]
```

It hashes every public field, including observations and original binding. It is an identity digest, not a signature or upload receipt. Caller data is owned before hashing awaits. Failed observations retain nonnegative Java-int frame counters up to `2147483647` and nonnegative safe-integer native counters, including rejected/over-budget observations; successful observations require sent frames5..12, received frames6..12 (AUTH included), sent bytes280..1024, received bytes497..1024, native sent>=1, sent<=reserved<=256, rejected=0, and sent<=sentBytes<=sent*1200. Success requires both selected tuples, verified completion digest, cleanup, reason `complete`, and completedAt strictly before expiresAt. Failed completions cannot carry a completion digest or reason `complete`; their completedAt may be at most expiresAt+300000. The correlator additionally checks attempt issue time, report receipt time and future timestamps against the owned evaluation cut.

The independent batch format is `{version:1,completions:[...]}` with **1..4** entries and **16384 UTF-8 bytes** total. Repeated provider/host/authority incarnation/generation/native owner tuple/attempt identities are rejected even if their contents differ. Historical cross-owner identities remain distinct. The optional heartbeat field is `diagnosticCompletions`; its receipt contract is separate from diagnostic installation/ACK. Polling/durable queues must retain the delivery plan's native32, poll4 and local durable32 bounds, exact receipt reconciliation, and explicit unknown/loss accounting at capacity.

## Independent correlation

`correlateDiagnosticResults` consumes only public, independently trusted inputs:

- Original attempt job, retained original installation ACK and semantic acceptance revision, pinned full SDP offer/client fingerprint, immutable prober result digest and original prober-only receipt. `AttemptRecord.offerDigestHex` is the signed offer request-body hash and must exist to establish issuance; `offerSdpDigestHex` is the separate full SDP hash compared to the native/prober `offerDigestHex`. Shared correlation fixtures deliberately use different body and SDP digests. The private admission nonce is neither copied nor returned.
- Host completion plus immutable completion digest, authenticated host ID and original recordedAt, supplied by the authenticated host-result store.
- Prober report associated with the immutable original digest/receipt. Its canonical sorted-JSON SHA256 hex must match both retained result digest and receipt.
- Original trusted `admissionKeyId`. The issuance store must retain the offer's selected epoch ID immutably; the caller supplies that original association. Missing key history is unknown, not guessed from a rotated installation.
- Optional trusted report-current cut: `{jobId,jobRevision,attemptIdHex,installation,acceptanceRevision,target,source,sourceCheckedAt,expiresAt,observationNotAfter}`.
- Explicit `now` for deterministic evaluation. No network, D1, clock reads, native operations or mutations occur in the pure function.

DTO construction and digest consistency do not establish authentication. The host store must authenticate the current host credential while preserving the original historical native owner, reject conflicting duplicates under that identity, retain the exact first receipt and reject reports received outside the original attempt+300000 grace. A later native owner may carry durable old bytes; it cannot rewrite their historical binding, hashes, completion time or digest to become current.

The report-current cut must come from a **report-eligibility query**, preserving the existing relational job revision, target registry revision/JSON, exact ACK/acceptance marker, current canonical owner/profile/endpoint/policy, workload/source identity and their fixed bounds. It must allow a completed stored result. Existing unfinished-attempt predicates require `result_digest_hex IS NULL` and an unexpired execution permit and therefore cannot supply this report cut directly. Equal newly read Target JSON alone cannot establish that an old job survived target disable/re-enrollment. The supplied job/attempt identifiers provide explicit association; they are not a substitute for that relational guard. Observed config/registry/ACK withdrawal or ABA must retire the caller's old cut. Pure correlation does not mint a trusted cut or weaken existing adapter ownership checks.

Original permit expiry bounds **execution and both successful completions**. A report may remain useful after that permit expires. Its separately fixed `observationNotAfter` is anchored to the original evidence, at most `min(host.completedAt,prober.finishedAt)+300000`, and capped by the original captured current authority/selected endpoint/policy deadline. The source cut must be nonfuture and live with expiresAt<=sourceCheckedAt+300000. The caller freezes the report cutoff once; receipt delivery, rendering and refreshed source reads do not restamp it. Both original recordedAt values must be no earlier than their completion, no later than attempt.expiresAt+300000, and no later than the evaluation time. Cleanup already completed before either success timestamp.

The immutable prober-only receipt is never modified or promoted. Output is a separate point-in-time assessment containing original attempt expiry, fixed observation cutoff when eligible, report digests, and:

| Output | Meaning |
| --- | --- |
| `historicalEvidence: matched-completion` | Both independently stored successful completions match original binding/attempt/key/target/offer/client, selected path, fixed completion deadlines and transcript digest. |
| `status: success` | The historical match also has an exact still-live report-current cut and fixed observation window. This is functional diagnostic evidence, not permission to admit players. |
| `status: unknown` | Missing, malformed, mismatched, expired, withdrawn, failed, unclean or unexecuted evidence, or absent current authority. A valid historical match can coexist with current unknown. |
| `sourceHistory: unknown` | Correlation does not prove the egress was untouched by earlier target traffic. Source-history allocation/capture remains separate. |

Both UDP observations must show actual sent packets and zero blocked/rejected sends for success; prober `largestPayload:null` truthfully means unmeasured. No failure combination produces a target-firewall diagnosis. Host-only success, prober-only success, aggregate frame counts or historical `currentAtRecord:true` are insufficient. Historical `currentAtRecord` is retained unchanged; eligibility comes from the separately authenticated report-current query.

This asynchronous pure evaluator owns every supplied byte before its hash awaits, but evaluates only the supplied `now` and cut. It is not a delivery capability. Before report storage/publication/release after any await, the caller must recheck the original current-cut owner and live monotonic deadline; a success value cannot carry authority past withdrawal or expiry. Idempotent combined storage must persist original evidence identity separately from current report eligibility, without creating a second prober receipt or extending any original deadline.
