# Optional NXS control transport — draft v1

**Draft for independent review. This file does not change the published NXS
schema, fixtures, discovery response or Java provider lifecycle.** Field names,
signing vectors and operation integration must be frozen together before either
side enables this capability. The existing [wire reference](wire-reference.md)
remains authoritative for the seven current operations.

## Purpose and compatibility

Control transport carries the existing operational exchange over optional WSS,
and allows independently addressed messages when both parties advertise that
capability. Registration and recovery remain HTTPS. A connected socket does not
establish game readiness, UDP reachability or successful gameplay.

The ordinary request/response capability does not imply addressed delivery.
Assisted offer/answer exchange is separately negotiated; stateless admission
retains its existing no-per-join-control behavior. Providers choose their storage,
socket ownership, routing, consistency and deployment implementation.

Existing HTTP-only integrations remain supported. After a generation participates
in control-session authority, every operational path for that generation,
including HTTP fallback, must enforce its writer binding. An older HTTP handler
must not bypass that binding. This is a control-session transition, not another
registration/completion state machine or an additional process generation.

### Required canonical-reference amendment before activation

The current optional-extension rule says extensions cannot change core protocol
rules. This draft's shared lifecycle-intent/envelope rules therefore **cannot be
enabled solely by emitting optional extension metadata**. At protocol freeze,
amend the canonical wire reference to distinguish ordinary product metadata from
this explicitly standardized and negotiated transport capability. The proposed
replacement rule is:

> Product extensions cannot change core authentication, lifecycle or replay rules.
> A standard protocol capability explicitly defined by this reference may select
> its specified transport/envelope profile through an authenticated negotiation.
> `nethernet.control` is such a capability; its generation-level selection and
> session binding govern both WSS and subsequent HTTP operational delivery.
> Providers must accept only the explicitly selected operational profile for that
> generation and path. They must not try another verifier after a signature fails.

Registration/completion bootstrap and nonparticipating HTTP-only generations keep
the existing core request rules. Selecting control is a strongly authenticated
transition recorded with the generation; transport failure does not clear it.
HTTP fallback must use the selected control envelope and writer fence. Freeze the
HTTP envelope and duplicate-receipt mapping together with this amendment and the
active-frame schema. Until then this remains a draft codec, not an enabled
alternate verifier or a published optional-extension exception.

## Proposed negotiation

Advertise optional `extensions["nethernet.control"]` with `version: 1`,
`critical: false` and `data` containing:

| Field | Proposed meaning |
| --- | --- |
| `subprotocol` | Exactly `nethernet-control-v1` |
| `sessionUrl` | Authenticated HTTPS session prepare/activate/status endpoint |
| `webSocketUrl` | WSS upgrade endpoint |
| `capabilities` | `request-response`, optionally `addressed`, `assisted-gameplay`, `assisted-diagnostic` |
| `limits` | Frame, payload, pending-work, assembly, handshake, close and attempt bounds |
| `authorityPolicy` | Maximum delegated authority age, clock allowance and key verification metadata |

The host selects the intersection of explicitly supported capabilities. Assisted
exchange additionally requires separately negotiated native/profile support;
`addressed` alone does not authorize creating peers. Diagnostic admission is a
separate native/profile capability and must not imply game admission. The
`assisted-diagnostic` control capability permits addressed diagnostic exchange and
requires `addressed`; stateless probes do not require it. `assisted-gameplay` also
requires `addressed`. Every active connection selects `request-response`.

Map the configured trusted HTTPS origin to WSS by scheme only; host and effective
port must match. Reject userinfo, fragments, foreign authorities, credential-bearing
redirects and unsupported required extensions before sending credentials. Retain
the existing explicit loopback-development exception for plain HTTP/WS. Never
fall back from a failed WSS attempt to plaintext. Never put credentials in URLs.
Clients require the exact selected WebSocket subprotocol in the upgrade result.

The client has independent hard upper limits; a provider advertisement can only
tighten those limits. Proposed initial ceilings are a 131072-byte complete UTF-8
frame, 65536 decoded payload bytes and 64 pending outbound messages. Pending bytes,
receive parts, message assembly/handler admission, send, handshake and close waits
must also be bounded. These provisional values need common schema vectors and
load validation; they are not a promise that every host accepts the ceiling.

## Identity and connection activation

Keep these identities distinct:

| Identity | Lifetime |
| --- | --- |
| `registrationId`, `instanceId` | Existing stable NXS registration and backend |
| `generation` | Existing process/registration-recovery fence |
| `sessionEpoch` | Increases on control-writer replacement, without changing generation |
| `sessionId` | Opaque logical session identity |
| `connectionId` | Unique accepted physical connection binding, never reused |
| `keyId` | Current machine authentication key; independent of admission keys |

All identifiers and integer counters have explicit schema bounds. Public endpoint
IDs, hostnames and pool attachments are not substitutes for `instanceId`.

1. The current logical host writer calls `sessionUrl` over authenticated HTTPS to
   **prepare** a bounded pending connection. It binds instance, generation,
   expected writer epoch/key, requested transport/capabilities and a persisted
   idempotency key. This does not replace the current writer.
2. The host opens WSS using scoped upgrade authentication in headers. Each accepted
   socket receives a fresh provider-generated random connection challenge/binding,
   also bound to a host-generated nonce and the pending session. A retry that opens
   a second socket gets a different binding. An upgrade credential alone is not
   authoritative and must not activate multiple sockets.
3. The logical host writer **activates** one selected binding via authenticated
   HTTPS, with a compare-and-swap against the expected current generation, key and
   session epoch. The transition records its idempotency key and exact connection
   binding atomically with the new writer. Repeating that activation returns the
   same result, not another epoch or a grant for another physical socket.
4. The selected socket proves/installs its activation result, synchronizes required
   state, and exchanges readiness. The provider accepts protected frames only when
   the installed grant names this exact connection and its effective authority is
   usable. Partial activation or a disappeared socket is reconciled explicitly;
   an HTTPS commit alone is not evidence that addressed delivery is ready.

Pending/standby sockets can perform the connection challenge, capability negotiation
and local ping/pong only. They cannot mutate lifecycle state, acknowledge installed
keys, receive fresh admission/operational secrets or receive assisted offers.
Preparing/activating a replacement is a narrowly scoped HTTPS authority transition;
it is not a general privilege granted to standby frames.

HTTP fallback uses the same authenticated writer transition with an HTTPS writer
binding; it does not need a WebSocket owner. The binding covers each signed HTTP
request as well as session identity. Ordinary HTTP-only hosts need neither a
standby connection nor periodic session replacement solely to use HTTP.

## One lifecycle intent across HTTPS and WSS

Keep one durable sequence allocator and one pending lifecycle-intent journal per
instance/generation. Existing heartbeat/profile/lease/key updates stay one logical
operation with the same handler. Replaceable observations may coalesce; an accepted
durable lifecycle operation must not silently change payload while awaiting its
result. Outcome event IDs retain their separate deduplication behavior.

Proposed stable intent fields:

```text
{ operation, instanceId, generation, sequence, idempotencyKey, payloadSha256 }
```

`payloadSha256` binds the exact original UTF-8 operation body bytes. The stable
intent does not include physical connection, transport, retry timestamp or the
key currently authenticating delivery. A separate signed authentication envelope
binds the complete stable intent to the current authorized delivery context.
The semantic operation identity must include the operation itself, instance and
generation: equal body bytes do not make two different operations interchangeable.

The same intent may be delivered through either negotiated HTTP or WSS without
reapplying its mutation. On a fresh mutation, validate the current generation,
session and key in the same atomic operation that commits the mutation and its
receipt. Sequence reservation happens before transmission. The provider compares
the immutable intent digest before returning a duplicate receipt; conflicting
content under an existing idempotency key is an error.

The existing request hash includes the signing key and HTTP context. Implementing
this draft therefore requires a deliberate new signed-envelope/receipt contract,
with vectors for both transports. Removing fields from existing signatures or
adding an implicit alternate verification path is not a compatible implementation.

When a response is lost, reconcile the same intent before issuing a conflicting
one. Durable receipts contain the stable intent digest, disposition, committed
revision/generation and a bounded redacted result. Distinguish:

| Disposition | Meaning |
| --- | --- |
| `committed` | The durable mutation committed, or its original receipt was recovered |
| `accepted` | A transient observation was accepted/queued; no durable-commit claim |
| `applied` | The host finished the named desired-state/key action |
| `rejected` / `expired` | Nothing may infer successful application |

Local WebSocket send completion, ping/pong and frame receipt never stand in for
these acknowledgments. Providers reauthorize sensitive response release; a valid
historical receipt does not authorize disclosing fresh secrets to an old writer.
Replays never return one-time secrets again or extend their original deadlines.

For machine rotation, persist the replacement private key before sending the
intent. If rotation committed but the acknowledgment was lost, authenticate a
status/reconciliation request using the persisted candidate key and the original
intent identity. The provider authorizes against the current key; successful
reconciliation returns a redacted receipt without reissuing secrets. A retired key
does not retain general read/write authority. If current-key ownership cannot be
reconciled, use the existing ownership-proof recovery path, which may start a new
generation. Never replay a previous generation's mutation into the new generation.

## Proposed frame envelope

Each application frame is one complete UTF-8 JSON text message. Binary messages
are unsupported. Reject unknown versions/types and invalid limits before expensive
processing. Do not enable implicit unbounded JSON or compression expansion.

```text
{ version: 1, type, id, sequence, direction, audience, instanceId, generation,
  sessionId, sessionEpoch, connectionId, capabilities, sentAt, expiresAt,
  payload, payloadSha256,
  authentication: { scheme: "nxs-control-es384-v1", keyId, signature } }
```

`payload` is canonical unpadded base64url of the bounded original payload bytes.
This permits HTTP and WSS delivery to share an exact lifecycle-body digest,
including Unicode and escaping behavior. Frame-size validation includes encoding
overhead; decoded-size validation precedes payload parsing. Payload schemas are
specific to `type`; an opaque byte carrier does not relax semantic validation.

Frame `sequence` starts at one and advances by exactly one per direction on the
physical connection. It is separate from durable lifecycle sequence inside a
lifecycle payload. The canonical signature input is exactly UTF-8 of the ECMA-262
`JSON.stringify` representation of:

```text
["nethernet-control-frame-v1",1,"nxs-control-es384-v1",direction,audience,type,
 id,sequence,instanceId,generation,sessionId,sessionEpoch,connectionId,
 capabilities,sentAt,expiresAt,keyId,payloadSha256]
```

`direction` is `host-to-provider` or `provider-to-host`. Capabilities are sorted,
unique and exactly equal to the authenticated selection. `payloadSha256` is
canonical unpadded base64url of the 32-byte SHA-256 digest of the original payload
bytes. Reject duplicate JSON names, including differently escaped names, unknown
fields, wrong types, noncanonical encodings and invalid UTF-8 payloads. A reconnect
starts a new frame counter; it does not reset durable lifecycle sequence or replay
old offers. The caller serializes verification/sequence advancement and rechecks
current authority/time before dispatch after any asynchronous verification wait.

All integer tokens use canonical unsigned decimal `0|[1-9][0-9]*` and fit the
JavaScript safe-integer range. Fractions, exponents, negative zero and values that
only become integers through floating-point rounding are rejected before semantic
validation. Sequence, generation and epoch are positive. Frame/session/connection
IDs match `[A-Za-z0-9_-]{16,128}`; instance/key IDs match
`[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}`. The canonical provider audience is at most
2048 characters. These constraints are part of wire parsing, not loose JSON
Schema numeric coercion.

Every draft control audience now uses the shared strict
[control origin profile](control-v1.origins.md): lowercase ASCII DNS, canonical
IPv4 or bracketed lowercase/compressed IPv6, with canonical non-default ports.
HTTP is limited to exact localhost, 127.0.0.1 and [::1]. Alternate literal or port
spellings are rejected without normalization. IDN/xn-- and trailing-dot providers
cannot advertise this optional profile until a future agreed normalization
profile; existing core origin handling remains unchanged. Shared adversarial
vectors prove Java/Worker agreement across all draft carriers.

Every active frame uses ES384/P-384 with a raw 96-byte IEEE-P1363 signature,
encoded as canonical unpadded base64url. Reject DER and other curves. Host frames
use the registered machine key. Provider frames use a separate trusted published
**provider-control** verification-key family. Never use admission secrets,
checkpoint keys or an embedded untrusted JWK as provider-control authority.
Each trusted key entry binds its ID, family, public P-384 JWK, valid-from and
valid-until times; its publication is authenticated through the trusted HTTPS
provider/bootstrap path and follows the declared overlap policy. TLS hostname
verification remains required independently of the frame proof.

The [active-frame schema](control-v1.frames.schema.json) and independent
[vectors](control-v1.frames.fixtures.json) cover this envelope only. Session
prepare/activation/connection-challenge proofs require separate domains and
schemas; `session.hello` is not an active-frame type. Frame lifetime is at most
60000 milliseconds. The receiver rejects a frame at its expiry, beyond its fixed
effective authority/key deadline, before key activation, or when its timestamp is
too far ahead. Future timestamp tolerance is at most 30000 milliseconds; it never
adds another allowance to frame expiry or an already effective parent deadline.

| Frame family | Required semantics |
| --- | --- |
| `session.ready`, `session.resync` | Active physical binding, selected capabilities, authority deadlines and last committed/applied revisions |
| `lifecycle.request`, `lifecycle.receipt` | Stable intent, current delivery envelope and explicit committed receipt |
| `state.applied` | Named revision/key IDs actually persisted and usable; receipt alone is insufficient |
| `state.desired` | Provider-to-host unsolicited non-secret desired state; requires addressed capability. Ordinary heartbeat desired-state replies remain inside their lifecycle receipt |
| `outcomes.batch`, `outcomes.receipt` | Existing event identity/deduplication; bounded delivery never delays admission |
| `connectivity.report` | Bounded non-secret observation of known state; reading it does not renew health or create a heartbeat |
| `assisted.offer`, `assisted.answer`, `assisted.cancel`, `assisted.error` | Exact scoped attempt and expiry; only on ready addressed connections |
| `diagnostic.offer`, `diagnostic.answer`, `diagnostic.cancel`, `diagnostic.error` | Separate diagnostic principal and bounded target; requires assisted-diagnostic, not a player offer |
| `session.reconnect`, `session.draining` | Bounded advisory drain/replacement request; never assume notice will arrive |

Changes that authoritatively apply keys or lifecycle state still pass through the
same durable mutation path, even if transported in an acknowledgment frame.
Non-secret report receipts must not be labeled committed unless actually committed.

## Delegated authority and revocation semantics

The optional [authority renewal exchange](control-v1.authority.md) uses existing
closed `authority-request` / `authority-response` kind envelopes as raw text on
the owning WebSocket, before ordinary protected frames. These messages have an
8 KiB ceiling and consume no direction sequence. HTTPS writers use the configured
authority POST route. The signed logical POST target remains identical on both
carriers; socket replies are associated with their physical link, not fabricated
HTTP provenance. Source-only timeout retains the current physical writer with
bounded backoff and at most two starts per rolling 30 seconds. A positive proof
does not establish readiness, reset sequences or extend any original deadline.

An authenticated active socket is not unlimited authority. The provider may use
cached, versioned authorization with a declared maximum age. Each protected
operation must satisfy its established connection binding, required signature,
current locally observed authority version, effective expiry and work limits.

An effective deadline is no later than the minimum of the source authorization,
session grant, relevant key/policy validity and operation deadline. An authorization
grant states its source-check time and absolute expiry. Obtaining it later,
re-reading it, reconnecting or deriving a child permit cannot extend that expiry.
Fresh validity requires a new authoritative source cut proving the subject remains
authorized. Clock allowance is explicit and applied once, not at every handoff.

Ordinary revocation immediately prevents new authoritative lifecycle commits or
grant issuance. Previously delegated transient actions may remain usable until
supersession is observed or their original effective deadline. The provider must
declare this residual window; no participant may infer immediate global revocation
from a successful local close or administrative update. An old physical connection
can remain open while no longer authorized for any protected action.

Late observations retain their original generation/session/revision and sample
time. They cannot overwrite a replacement's canonical state or freshen evidence.
An unknown verification key or expired authority yields refresh/defer/reject,
never an authorization bypass. Cache refresh is amortized across messages; the
protocol does not require a storage read per frame or a renewal per idle socket.

Keep machine authentication, provider control proof, admission key and native DTLS
identity lifecycles separate. Planned verification-key publication includes an
advertised overlap sufficient for valid residual issuers and child permits.
Host-required material must be installed and acknowledged before it becomes
required. Retire old verification material only after all legitimate old permits
and already-accepted peer verification/drain requirements end.

## Assisted attempts, reconnection and implementation gates

Gameplay `assisted.offer` binds the current instance/generation/session, a unique
attempt ID, original offer digest, authenticated gameplay principal with authorized
player CPK/fingerprint binding, candidate policy and absolute deadline. Validate
the initial gameplay proof before allocating native resources.

Diagnostic work uses the separate `diagnostic.offer` family and a signed diagnostic
principal, never a fabricated player identity or a gameplay offer. Bind diagnostic
job/lease, target instance/generation/native incarnation, candidate revision and
endpoint, address family, region/checker identity, attempt ID, offer digest,
diagnostic identity/fingerprint and original expiry. Validate the provider proof,
purpose, authorized target and candidate policy before native allocation or packet
transmission. Diagnostic peers stay isolated from game admission, capacity and
gameplay handlers. They complete bounded transport exchanges and cleanup without
Minecraft login. Stateless diagnostic admission remains a separate native/profile
capability; it does not require addressed control.

For either principal, retransmitting the same live attempt must find the same
bounded peer; changing its body under the same ID is an error. An active-frame
signature proves the sender/context; typed payload and principal/permit validation
remain required before accepting an offer.

An answer binds the same attempt and offer digest. It reports gathered transport
SDP, not completed ICE/DTLS/gameplay. The provider validates authority, player/native
identity and candidate policy again before releasing the answer to the waiting
request. Expiry, cancellation, rejection or owner loss releases pending state;
late answers do not resurrect it. Already accepted gameplay peers retain required
identity state and cleanup independently of the control connection.

Reconnect with bounded jittered exponential backoff, including the first retry
after fleet-wide loss. Drain old pending attempts, activate one replacement,
reconcile durable intents, synchronize required state and only then mark addressed
readiness. Unexpected loss follows the same recovery without relying on planned
notice. A replacement socket cannot resurrect an interrupted client HTTP wait or
replay an expired offer. Bound concurrent attempts and total pending bytes globally
as well as per host, and preserve the original attempt deadline across retries.

The JDK adapter is only the bounded transport primitive. Integration still owns
trusted WSS origin policy, fixed configuration ceilings, short callback admission
to bounded work queues, timer/executor lifetime, lifecycle journaling, frame proof
and replay validation, session fencing, silent-loss detection and retry policy.
Do not run inbound offers behind synchronous HTTP lifecycle retry sleeps.

Before enabling this draft, freeze schemas and independent Java/JavaScript vectors
for discovery, activation/standby, both envelope directions, HTTP/WS shared intent
identity, lost rotation acknowledgment, one-time-secret suppression, key overlap,
revision rollback, expiry and signed assisted bindings. Integration tests must
cover duplicate upgrades, two racing activations, stale HTTP fallback, delayed old
callbacks, partial activation, restart, rotation, expired authority and unsupported
capability fallback. Provider storage/cache and socket-owner implementation remain
outside this neutral wire specification.
