# Session bootstrap and lifecycle carrier — review proposal

**Draft contract with neutral codecs and fixtures; no route enables these shapes.**
The Java codec validates proof/schema associations. Provider mutation handlers,
atomic state transitions and the ProviderClient lifecycle are separate work.
The [active-frame draft](control-v1.md) and its pending canonical-reference
amendment still apply. Provider state transitions require atomic implementation;
codec/fixture conformance alone does not implement their transaction semantics.

## Common proof representation and bounds

All proofs use `authentication: {scheme:"nxs-control-es384-v1",keyId,signature}`,
canonical base64url raw96-byte ES384/P-384 signatures and the separate trusted
machine/provider-control families already defined for active frames. Domain
separation prevents bootstrap proofs from being accepted as active-frame proofs.
Canonical signing arrays are UTF-8 ECMA-262 JSON.stringify bytes. `payload` is
canonical base64url of original strict UTF-8 bytes; `payloadSha256` binds those
exact bytes. Integer tokens, identifiers, duplicates, key deadlines and audience
checks follow the active-frame rules.

Bootstrap limits: 16384 total JSON bytes; 8192 decoded payload bytes;
proof lifetime at most60000ms. Nested signed proofs are encoded original bytes,
bounded before parsing. Future timestamp tolerance is at most30000ms; effective
parent/key deadlines are never extended by another allowance. Server-generated
session/connection IDs and both peers' nonces contain at least128 bits of entropy.

## Bootstrap request

```text
{version:1,action,requestId,audience,method,encodedPathAndQuery,instanceId,
 generation,sentAt,expiresAt,payload,payloadSha256,authentication}
```

Required fields only. `requestId` is a persisted128-bit-or-greater idempotency ID
for mutating requests, and a fresh correlation ID for reads/upgrades. Allowed
actions: `prepare`, `activate`, `status` with `POST`; `upgrade` with `GET`.
The caller verifies the actual method and exact encoded path/query against its
trusted discovery operation. Upgrade proof/envelope travels in bounded headers,
never URL parameters. The upgraded socket must negotiate `nethernet-control-v1`.

Machine proof signing array:

```text
["nethernet-control-session-request-v1",1,"nxs-control-es384-v1",action,
 audience,method,encodedPathAndQuery,requestId,instanceId,generation,
 sentAt,expiresAt,keyId,payloadSha256]
```

For every action, `requestIntentDigest` is the canonical base64url
SHA-256 digest of:

```text
["nethernet-control-session-intent-v1",1,action,audience,instanceId,
 generation,requestId,payloadSha256]
```

For `prepare`/`activate`, that digest also identifies the durable mutation.
Retry timestamps, carrier path and authenticating key can change only after
reauthorization; the stable intent/payload cannot. A current-key-authenticated
duplicate returns its original result/deadlines after digest comparison and does
not reapply the transition. An uncommitted changed expected fence is a new intent,
not an idempotent retry. Read/status proofs do not reserve or advance the durable
operational sequence; they cannot mutate lifecycle or renew authority.

## Provider response/challenge

```text
{version:1,kind,requestId,requestIntentDigest,audience,instanceId,generation,sentAt,expiresAt,
 payload,payloadSha256,authentication}
```

Kinds: `prepared`, `connection-challenge`, `activated`, `status`. Provider-control
proof array:

```text
["nethernet-control-session-response-v1",1,"nxs-control-es384-v1",kind,
 audience,requestId,requestIntentDigest,instanceId,generation,sentAt,expiresAt,keyId,payloadSha256]
```

The response binds the originating request ID, exact action/body intent digest and
current authenticated subject. The receiver compares both request identity fields
to its persisted/outstanding request context; request ID equality alone is insufficient.
A freshly signed status/duplicate response cannot renew the original prepared
record, session grant, source authorization or child attempt. Their deadlines are
explicit payload fields and checked independently of response-proof expiry.

## Payload schemas and state ownership

`WriterFence` is a closed union:

```text
legacy: {transport:"legacy-http",sessionEpoch:0,sessionId:"",connectionId:"",keyId}
active: {transport:"websocket"|"https",sessionEpoch:positive,
         sessionId:opaque,connectionId:opaque,keyId,machineKeyRevision:positive}
```

`keyId` is the expected current machine key; `machineKeyRevision` is its monotonic
selected revision within this instance/generation. Key IDs are never reused in
that instance/generation. Legacy state has no controlled key revision. Every mutation also checks envelope
instance/generation against the current authority. Legacy shape is valid only
before this generation enters controlled writer state; it cannot bypass a later
writer fence. For HTTPS writers, `connectionId` identifies the logical HTTP writer
binding, not a particular pooled TLS connection. For WebSocket writers it names
the exact accepted physical socket.

| Payload | Exact proposed fields |
| --- | --- |
| prepare request | `{transport:"websocket"|"https",capabilities,clientNonce,expectedWriter,sessionDurationMillis}` |
| prepared response | `{pendingSessionId,transport,capabilities,clientNonce,connectionId,expectedWriter,intentDigest,preparedAt,expiresAt,sessionDurationMillis}` |
| upgrade request | `{preparedProof}` |
| connection-challenge response | `{pendingSessionId,transport:"websocket",capabilities,clientNonce,connectionId,preparedProofSha256,expiresAt,sessionDurationMillis}` |
| activate request | `{expectedWriter,preparedProof,connectionProof}` |
| activated response | `{intentDigest,writer,capabilities,activatedAt,sessionExpiresAt,authoritySourceCheckedAt,authorityExpiresAt}` |

`preparedProof` and `connectionProof` contain canonical base64url original provider
response-envelope bytes, not reserialized objects. A reference digest hashes those
original bytes. The total decoded payload and envelope ceilings also apply to
combined nested proofs and metadata; individually permitted maximum field sizes
do not guarantee that every maximum can fit together. Reject an oversized
combination without dropping identity fields or rewriting its bytes.

`sessionDurationMillis` is negotiated explicitly, positive and at most 86400000
(24 hours). It must match in prepare/prepared/challenge. A deployment may choose
a 6-hour default separately. Preparation/challenge expiry is the same original
fixed deadline, at most 60000ms after preparedAt, and cannot slide on retry.
Activation commits activatedAt and sessionExpiresAt=activatedAt+sessionDurationMillis
once; replay returns those same fields. This long session grant is independent
of the shorter, fixed source-authority checkpoint deadline. Neither status nor
transport liveness extends either deadline or causes a minute-by-minute durable
session renewal.

For WebSocket preparation, `prepared.connectionId` is null; every
accepted upgrade receives a new random physical `connectionId`. A second upgrade
using the same prepared record is another non-authoritative candidate socket,
never a second active owner. The challenge references the exact prepared proof.

For HTTPS preparation, the provider assigns a fresh logical `connectionId` directly
in the prepared result; `activate.connectionProof` is null. For WebSocket activation,
`connectionProof` must be the provider-control-signed challenge from that exact
socket, referencing the supplied preparation. These are separate closed variants.
An HTTPS writer selects only `request-response`; it cannot advertise addressed
readiness. A WebSocket selection is exact, sorted and within the advertised set.

Activation verifies both proofs, their subject/nonces/capabilities/deadlines and
the caller's current machine key, then compares `expectedWriter` to authoritative
state in the same atomic operation as the new writer/epoch and idempotent receipt.
Two candidates can share an expected epoch, but only one may commit against it.
The losing activation receives conflict and no active grant. The winner's duplicate
returns the same epoch and exact binding. A disappeared socket or partial owner
installation is reconciled before addressed readiness; there is no implicit
distributed transaction between authority storage and a socket owner.

Pending/standby candidates may negotiate and receive their challenge; they cannot
mutate lifecycle, acknowledge installed keys, receive operational secrets or
receive assisted offers. Preparation expiry and bounded pending capacity release
abandoned candidates. Idempotent replays do not refresh preparation expiry.

## Stable lifecycle intent

Operations are the existing `heartbeat`, `outcomes`, `rotate`, `retire`,
`deregister`; registration/completion remain their existing bootstrap. Intent:

```text
{version:1,audience,operation,instanceId,generation,sequence,idempotencyKey,
 payloadSha256}
```

Its digest is canonical base64url SHA-256 of:

```text
["nethernet-control-lifecycle-intent-v1",1,audience,operation,instanceId,
 generation,sequence,idempotencyKey,payloadSha256]
```

`payloadSha256` hashes the original operation body bytes. Signing key, retry time,
transport and physical session do not enter this stable digest; the enclosing
authenticated delivery proof binds the intent to the current authorized context.
This remains one persisted pending-intent journal and one sequence allocator.
Changing body, operation, provider, instance, generation or reserved sequence
changes the intent and is never an idempotent replay.

For WS, `lifecycle.request.payload` decodes to `{intent,body}`, where `body` is
canonical base64url original operation bytes. Its body ceiling is45056 bytes;
metadata and total decoded/encoded frame bounds are additionally enforced. Larger
otherwise-valid operation bodies use HTTPS without altering their bytes/digest,
sequence or idempotency key. The HTTP body ceiling remains the negotiated core
limit, at most65536 bytes.

## HTTPS carrier and persistent fallback

An HTTPS operational request keeps the original body bytes. A bounded signed
header envelope contains:

```text
{version:1,audience,method,encodedPathAndQuery,sentAt,expiresAt,intent,
 sessionId,sessionEpoch,connectionId,writerTransport,capabilities,authentication}
```

Machine signature array:

```text
["nethernet-control-http-request-v1",1,"nxs-control-es384-v1",audience,
 method,encodedPathAndQuery,sentAt,expiresAt,instanceId,generation,
 sessionId,sessionEpoch,connectionId,writerTransport,capabilities,keyId,
 intentDigest,payloadSha256]
```

The intent supplies instance/generation/body digest, and its audience must equal
the carrier audience. Verify the actual HTTP method/path/body bytes against this
envelope. The selected operation must match the trusted discovered route. Validate
current writer/key/generation atomically with a fresh lifecycle mutation; matching
proof bytes alone do not establish current writer authority.

A WebSocket-owned writer may send an individual HTTPS lifecycle/key exchange under
the same active binding. This changes only the carrier and does not clear addressed
readiness. Persistent fallback after a failed WS instead prepares/activates a new
`https` writer through compare-and-swap, increments its epoch and clears addressed
readiness. Neither path drops the writer fence or accepts the old core verifier
for an already opted-in generation.

## Redacted receipts and interrupted machine rotation

Proposed minimal replay receipt contains only bounded non-secret metadata:

```text
{version:1,intentDigest,operation,instanceId,generation,sequence,idempotencyKey,
 disposition:"committed"|"rejected"|"expired"|"unknown",
 committedAt:timestamp|null,commitRevision:safeInteger|null,code:identifier|null}
```

Only committed receipts carry committedAt/commitRevision; other dispositions
cannot imply successful application. **No arbitrary result/body/secret field is
permitted.** A first successful typed operation response may have its separately
authorized one-time delivery; replay receipts never repeat it. Recover fresh
non-secret state through guarded status or a subsequent ordinary exchange after
the pending intent is reconciled. This intentionally separates mutation history
from current state and sensitive response delivery. A separately typed current
observation/connectivity report may accompany a receipt with its own sample,
revision and expiry; it never enters the immutable receipt identity.

Status requests are closed variants:

```text
{query:"current-writer"}
{query:"intent-receipt",intentDigest}
```

Status response payloads are closed variants:

```text
{query:"current-writer",writer,capabilities,activatedAt,sessionExpiresAt,
 authoritySourceCheckedAt,authorityExpiresAt}
{query:"intent-receipt",intentDigest,receipt}
```

Current legacy writer uses capabilities:[] and all four time fields:null. A
controlled writer carries its selected key ID/revision and the original fixed
deadlines, which may already be expired. For an unknown digest, receipt is null;
a digest-only lookup cannot invent missing operation/sequence identity. A known
receipt must match intentDigest. These are current non-secret observations, not
authority grants or liveness acknowledgments. A status response does not
grant operational permission or refresh the referenced grant. Historical receipt
lookup cannot replay a previous generation's mutation into a current generation.

Machine rotation keeps the same writer epoch/socket and atomically advances
selected machineKeyId and machineKeyRevision with its receipt and authority
publication event. Fresh lifecycle commits/status use the new current key
immediately. An old cached view can authorize old-key transient frames only
until its original effective deadline; it cannot be renewed from a stale view.
Frames bind keyId; trusted session authority selects the allowed key/revision,
never the unverified frame key ID. Cooperative hosts pause protected WS work and
addressed readiness during rotation until resynchronization confirms the new
selected revision. New-key HTTPS operations may continue while cached source
views converge. Pending activation with an old expected key fails the fresh CAS.
No immediate global transient-revocation or gap-free rotation promise is made.

For lost rotation acknowledgment, the original rotation intent/body includes its
original old/new-key proof. The host retains the pending replacement private key
and intent digest. It can authenticate status using that candidate key; the provider
checks the actually current key and returns only a redacted receipt/current public
key identity. If rotation did not commit, the candidate is unauthorized and the
old current key may reconcile. A retired old key gets no general read/write bypass.
The current key authenticates delivery; it never silently changes the immutable
intent hash. If ownership cannot be reconciled, use normal ownership-proof recovery.

## Review/fixture gates

The matching closed schemas and codecs implement the structural/proof rules.
Stateful provider/client integration must implement the separate transition rules.
Independent fixtures must cover two distinct upgrade challenges for one preparation;
one winning activation plus conflict on the same expected epoch; exact idempotent
activation replay; stale HTTP writer rejection; one-off HTTPS under WS authority;
same lifecycle digest across carriers and current-key changes; mutated body/operation
rejection; interrupted rotation reconciled using the persisted candidate key; no
secret fields in replay receipts; expired preparation/authority; and proof-domain,
direction, audience and connection substitution. Fixture state examples are not
evidence of a deployed atomic authority implementation.

All signed audiences and trusted expected origins use the shared strict
[control origin profile](control-v1.origins.md). IDN/xn-- and trailing-dot
providers cannot advertise this optional profile; existing core origin handling
remains available and unchanged. Never normalize input after signing.
