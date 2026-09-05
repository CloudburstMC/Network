# NetherNet External Signalling v1

Status: experimental open specification. Identifier: `urn:nethernet:external-signalling:v1`.
Licensed under this repository's Apache-2.0 license. The schema, canonical fixtures,
and this document form one versioned contract. Product account systems, credential
issuance, billing, Microsoft login, DNS management, and host-selection policy are
outside the contract.

NXS connects a running NetherNet host to an operator-selected signalling provider.
The provider can issue an answer using previously published host information.
The host validates the connecting client's first STUN packet without a per-client
request to the provider. A conforming implementation MUST NOT require any push,
poll, shared lookup, offer fetch, or pre-staged client state to admit that client.
Transport and game outcomes are asynchronous observations, never admission prerequisites.

## Version and discovery

| Field | v1 value |
| --- | --- |
| Registration/request protocol | `nethernet-external-signalling-v1` |
| Machine request signature | `nxs-es384-v1` |
| Operational profile | `nxs-admission-v1` |
| Discovery path | `/.well-known/nethernet-external-signalling` |
| Stateless capability | `nethernet.stateless-admission.v1` |
| Stateless carrier prefix | `NXS1` |

The configured origin MUST use HTTPS; HTTP is permitted only for loopback
development. Origins are normalized by lowercasing scheme/host and omitting default
ports. Credentials, path, query and fragment are not permitted in the configured
origin. Discovery is unauthenticated `GET`. `provider` and `controlOrigin` MUST
equal that origin. Each operation URL MUST have the same origin, no userinfo or
fragment. Clients MUST disable redirects for discovery and credential-bearing calls.
Encoded paths and query strings are signed exactly as transmitted.

Discovery contains `provider`, `controlOrigin`, arrays `protocols`, `signatures`,
`profiles`, `modes`, an `operations` map, `authorization`, `limits`, and optional
`extensions`. Clients reject an unsupported protocol/profile/signature/mode or
required extension before transmitting any credentials. This profile defines
all operation names in the table below; URL paths are discovered, not hard-coded.
`/v1/nxs/<operation>` is a recommended mapping, not a routing requirement.

`authorization` has `header: "Authorization"` and `schemes` entries containing
`scheme` and supported `modes`. Schemes are `anonymous-proof-of-work` and
`bearer-token`; an implementation need only advertise the schemes it accepts.
Anonymous creation permits `new-service`. Bearer authorization permits
`new-service` and/or `attach-instance`. Token scope, reuse policy and issuance
remain provider decisions. Every flow proves possession of the instance key.

Limits contain `maxBodyBytes` (at most 65536), `clockSkewMs` (at most 60000),
`heartbeatIntervalMs` (1000–30000), `leaseMs`, and `maxControlPage` (at most 100).
`checkInVersion: 1` negotiates response-driven scheduling. A provider MUST advertise
all limits it enforces, reject oversize bodies, and return errors as
`{"code":"lowercase_machine_code"}` with an appropriate HTTP failure status.
Clients bound response bodies before parsing them. On transient transport failure,
429, 502, 503 or 504, the supplied client retries at most three attempts with
bounded exponential delay and jitter; `Retry-After` seconds over ten cause a
retry-later result. Retries never extend a granted lease or challenge expiry.

## Registration and persistent identity

Generate a fresh P-384 machine signing key for each logical instance. Persist it
before requesting a challenge; no live replicas may share a key/state directory.
An instance restart reuses its own durable state. Images/templates MUST contain
neither machine identity nor DTLS private keys. Clients lock their state directory,
write private state atomically with owner-only permissions and durable file/directory
sync, and stop advertising healthy readiness after persistence failure.

The challenge request contains `protocol`, `mode`, `profile`, `publicKeyJwk`,
optional `label`, explicit `authorization: {scheme}`, and optional `placement`.
The bearer credential is sent only as `Authorization: Bearer <token>` to the
challenge operation. It MUST NOT enter JSON, proofs, persistent state, or logs.
`attach-instance` requires bearer authorization and placement. A bearer token
authorizes the service; a client-provided label never grants authority.

Placement is `{region,pool,tags?}`. Region and pool are immutable routing labels
matching `[A-Za-z0-9_-]{1,32}` and `[A-Za-z0-9_-]{1,64}` respectively. Tags have at
most 16 keys matching `[A-Za-z0-9_.-]{1,32}` and trimmed string values of 1–64
characters without control characters. Exact placement is bound into the challenge
and revalidated against token authority at atomic completion. No provider selection
algorithm is implied by these fields.

The public JWK is EC/P-384 with canonical unpadded base64url `x` and `y` encoding
exactly 48 bytes each, and MUST NOT contain `d`. RFC 7638 thumbprint is SHA-256 of
UTF-8 JSON with members exactly `crv,kty,x,y` in that order. ES384 signatures are
96-byte IEEE-P1363 `r || s`, unpadded base64url; DER and noncanonical base64url fail.

A challenge contains `protocol`, `signature`, `challengeId`, `nonce`, `audience`,
`thumbprint`, `context`, `contextDigest`, `expiresAt`, `serverTime`, and
`pow: {algorithm:"sha256-leading-zero-bits-v0",difficulty}`. Difficulty is 0–24;
bearer-authorized and recovery flows use zero. An authorization reference is opaque,
never the credential itself. Expiry/server times use integer epoch milliseconds.

Canonical arrays are UTF-8 JSON without whitespace or Unicode normalization.
Missing context strings are empty strings. `contextDigest` is unpadded base64url
SHA-256 of `[mode,profile,label,authorizationId,serviceId,region,pool,registrationId]`.
When tags are nonempty, append `tagsDigest`, the same digest of sorted `[key,value]`
pairs. The completion proof is:

```text
[protocol,"complete",audience,challengeId,nonce,thumbprint,contextDigest,
 expiresAt,proofNonce,idempotencyKey]
```

PoW counts leading zero bits of SHA-256 over those bytes. Completion sends
`protocol,challengeId,proofNonce,idempotencyKey,signature`. The provider MUST check
expiry, binding, signature, difficulty, current authority and single-use completion
atomically with resource creation. Retrying completion MUST NOT replay one-time
key secrets. Recover an interrupted completion through proof of the same key.

Completion returns `protocol,provider,registrationId,serviceId,instanceId,keyId,
profile,publicAddress,placement,heartbeatIntervalMs,leaseGeneration,leaseDeadline,
readiness` and optional one-time `ticketKey` and `extensions`. Persist returned IDs
and key material before activation. Strip secret material from application-facing
registration results and diagnostic output.

## Signed lifecycle and host profile

Every operational request uses the registered machine key, never the enrollment
bearer token. Required headers are `nxs-instance-id`, `nxs-key-id`, `nxs-timestamp`,
`nxs-signature-version`, `nxs-generation`, `nxs-sequence`, `nxs-signature`, and
`idempotency-key`. Timestamp is epoch milliseconds; generation and sequence are
nonnegative integers. Reserve sequence durably before sending. Authentication binds:

```text
[protocol,signatureVersion,audience,method,encodedPathAndQuery,timestamp,
 instanceId,keyId,idempotencyKey,generation,sequence,base64url(sha256(bodyBytes))]
```

The empty body hashes as zero bytes. Providers reject stale generations, reused
sequence numbers, invalid timestamps and signatures. An idempotent retry with the
same intent and unchanged semantic request can return its recorded non-secret result;
it cannot reapply an operation. Accepted activation increments generation and resets
sequence; old processes are fenced. Signed stateful operations belong to the active
profile. Recovery and a signed activation are the explicit profile migration boundary.

| Operation | Request | Required behavior/result |
| --- | --- | --- |
| `challenges` | POST challenge request, optional bearer | Bound registration challenge |
| `complete` | POST completion proof | Registration or recovered registration; secrets only once |
| `recover` | POST `{registrationId,protocol,profile}` | Challenge for current/pending machine key; preserves assigned IDs |
| `activate` | Signed POST `{profile}` | Incremented `leaseGeneration,leaseDeadline`; resets stale host readiness |
| `readiness` | Signed GET | Routability and reasons, optional extension metadata |
| `host-profile` | Signed POST profile below | Immutable/monotonic `revision`; reject unusable candidates or keys |
| `heartbeat` | Signed POST health/status below | Received time, renewed lease and optional check-in schedule |
| `control` | Signed GET, optional cursor | Bounded `commands`, optional `cursor`, `serverTime` |
| `control/ack` | Signed POST `{cursor}` | Acknowledge only completed terminal lifecycle commands |
| `ticket-keys` | Signed POST `{}` | One-time `{ticketKey:{keyId,secret,...}}` for a new epoch |
| `ticket-keys/ack` | Signed POST `{keyId}` | Confirm keys installed before routing with their epoch |
| `ticket-events` / `events` | Signed POST `{events:[...]}` | Idempotent bounded asynchronous observations |
| `rotate` | Signed POST `{publicKeyJwk,proof}` | New `keyId` after proof by replacement key |
| `retire` | Signed POST `{keyId}` | Retire previous machine signing key |
| `drain` | Signed POST `{}` | Stop new routing/admissions, preserve existing sessions |
| `deregister` | Signed POST `{}` | Revoke instance from routing; terminate its registration lifecycle |

Rotation proof bytes are `[protocol,"rotate",audience,instanceId,oldKeyId,
newThumbprint,generation,idempotencyKey]`. Persist replacement private key before
rotation, then result before retiring the old key. Recovery can resolve an interrupted
rotation using the key thumbprint returned by the provider.

`host-profile` contains `candidates`, `dtlsFingerprint`, `credentialKeyId`,
`sctpPort`, `maxMessageSize`, and `statelessAdmission: {capability,incarnation}`.
Incarnation is fresh random 16-byte lowercase hex for each bound native endpoint.
The fingerprint is `sha-256 ` followed by colon-separated uppercase certificate
digest bytes. Candidates contain `foundation,component,protocol,priority,address,
port,type`; only reachable, explicitly advertised UDP candidates may be published.
Bind addresses and advertised addresses are separate concepts. Never advertise
wildcard `0.0.0.0`/`::`. NAT and relay reachability must be established by the
deployment/provider; passing a registration test does not prove reachability.

The host provisions its DTLS certificate/key before profile publication and keeps
the private key local. All peers represented by a published profile use that
certificate. Machine signing keys, DTLS identities and admission keys are distinct.
An endpoint can use a newly generated identity on a later incarnation after publishing
the new fingerprint; a shared permanent fleet certificate is neither required nor advised.

Admission keys have `keyId` (four uppercase alphanumeric characters), secret
(32–256 UTF-8 characters), optional `notBefore` and `retireAfter` epoch milliseconds.
Install at most eight epochs atomically, acknowledge them, then publish a profile
using an active installed epoch. Hosts reject before activation/after retirement
and erase retired material. They do not extend token expiry when rotating keys.

Heartbeat contains `healthy,capacity,load,protocolVersion,build,hostProfileRevision,
clockUnixMillis`, optional `region,serverStatus,checkInVersion`. Capacity and load
are routing observations, independent of advertised player/max-player counts.
Status contains `name,protocol,version,level,players,maxPlayers,gameType`.
Publishing failures do not refresh old status timestamps. Readiness requires current
identity/generation, a live lease, usable fresh host profile and installed key acknowledgment.
Optional product extensions cannot gate core readiness.

When check-in v1 is negotiated, the heartbeat response has ISO8601 `receivedAt` and
`checkIn: {version:1,afterMillis,nextCheckInAt,leaseExpiresAt,minUpdateIntervalMillis,
controlPollAfterMillis}`. Absolute times are epoch milliseconds. `nextCheckInAt`
precedes lease expiry. Hosts schedule against monotonic clocks and count network
time against the interval; changed activity/status may prompt an earlier rate-limited
heartbeat. Restarts publish immediately and reset old schedules. Provider outage
expires routing leases but does not itself tear down established sessions.

Lifecycle controls supported by this profile are `noop,drain,suspend,revoke`.
Unknown controls are not silently acknowledged; process later known lifecycle
commands even while an earlier unknown command prevents advancing the page cursor.
`join-admission` is explicitly not a v1 control: admissions never wait for it.
Event batches have at most 100 entries and retain only redacted correlation,
stage/type, timestamp and bounded reason fields. Never send SDP, private keys,
player identity or game payloads as telemetry. Transport establishment is distinct
from `ticket.game_joined` (game play-ready) and `ticket.game_rejected`.

## Stateless admission carrier

The client's first STUN USERNAME is `<answerUfrag>:<clientUfrag>`.
`answerUfrag = "NXS1" + keyId + unpaddedBase64(nonce || ciphertext || tag)`.
Use standard base64 alphabet (ICE permits `+` and `/`), not base64url. Total ufrag
length is at most 256 characters. Noncanonical encoding, trailing padding, wrong
prefix, unknown epochs and oversized inputs are rejected before allocation.

AES-256-GCM uses random 12-byte nonce and 16-byte tag. Its key is
`HMAC-SHA256(secret, "nxs-stateless-aead-v1" || NUL || audience)`.
Audience is `nxs-stateless-host-v1/<incarnation>`. AAD is
`"nxs-stateless-admission-v1" || NUL || ("NXS1"+keyId) || NUL || audience || NUL || clientUfrag`.

| Plaintext offset | Size | Meaning, unsigned big-endian where numeric |
| --- | --- | --- |
| 0 | 4 | Expiry in epoch seconds, exactly representable in milliseconds |
| 4 | 32 | SHA-256 client certificate fingerprint |
| 36 | 2 | Client SCTP port, 1–65535 |
| 38 | 4 | Client maximum message size, 1–262144 |
| 42 | 16 | Opaque caller-context hash, no account-specific interpretation |
| 58 | 8 | NetherNet network ID, unsigned 64-bit |
| 66 | 1 | Client ICE password length, 22–91 |
| 67 | N | Client ICE password in ICE base64 alphabet |

The host's local ICE password is unpadded standard base64 of the first 24 bytes of
`HMAC-SHA256(secret, "nxs-stateless-ice-v1" || NUL || audience || NUL || answerUfrag)`.
The ticket correlation ID is the first 16 bytes of SHA-256 of the ASCII answer ufrag,
encoded lowercase hex. Maximum admitted token TTL is 120 seconds; the supplied
implementation uses 60 seconds. Hosts validate expiry, bounds, GCM, client binding
and raw STUN MESSAGE-INTEGRITY before tuple promotion or native peer creation.
The resulting DTLS handshake MUST verify the client fingerprint from the token.

Only identical-token retransmissions from the same UDP tuple may reuse a reservation.
Token replay from another tuple and conflicting admission on an occupied tuple fail
closed. Bound sessions, pending handshakes, replay cache, callbacks and datagram queues.
Peer creation happens outside the mux callback lock. Deliver/replay the authenticated
first datagram after native registration so the first STUN request receives a response.
Do not release admission capacity until native teardown actually completes.

## Optional extensions and compatibility

`extensions` is an object with at most 16 reverse-DNS namespace keys and 16384 bytes
of encoded UTF-8 JSON. Each value is `{version:positiveInteger,critical:boolean,data:object}`.
Namespace keys are lowercase domain-style labels, at most 128 characters. Unknown
optional extensions are passed through/ignored, never automatically executed.
Unsupported critical extensions fail before credentials or activation. Core semantics
cannot be redefined by an optional extension. Bodies and operation paths remain
authenticated by the surrounding TLS/signature boundary.

An extension may advertise `data.operations` URLs. An application may explicitly
request an operation only after validating its namespace/version and meaning.
The generic transport still enforces same-origin URLs and signs their exact path.
Account claim actions are a product extension; NXS assigns them no core meaning.

Previously persisted IDs/keys may be recovered into this profile through explicit
`recover {registrationId,protocol,profile}` and signed `activate {profile}`. Verify
the same key and origin, preserve IDs and DTLS files, then atomically record the new
profile/generation. Legacy protocol bytes MUST NOT be relabelled as v1. Providers
may retain separately negotiated legacy adapters; the neutral Java module implements
only NXS. Rollback requires explicit signed profile activation and recovery with the
previous client; never bypass machine authentication or copy a live state directory.

## Conformance

`node docs/external-signalling/fixtures.mjs` verifies independent JavaScript signing,
encryption and fixture hashes. `--write` regenerates public test signatures.
The JVM suites consume these exact files via Gradle resources. The independent
provider implements registration, signed lifecycle, status, keys, outcomes, drain
and recovery without a product account system. Native tests separately exercise
raw STUN admission and DTLS transport. Stock-client admission, gameplay and two-host
routing must be reported separately from fixture/native conformance.
