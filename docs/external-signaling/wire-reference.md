# NXS wire reference

This is the normative format reference for the [three integration flows](README.md).

## Version and discovery

| Field | v1 value |
| --- | --- |
| Registration/request protocol | `nethernet-external-signaling-v1` |
| Machine request signature | `nxs-es384-v1` |
| Discovery path | `/.well-known/nethernet-external-signaling` |
| Stateless capability | `nethernet.stateless-admission.v1` |
| Stateless carrier prefix | `NXS1` |

### Compatibility

v1 only grows by addition. Required members, existing meanings, signed byte
layouts and limits do not change within v1.

- Receivers MUST ignore JSON members they do not recognize.
- An unrecognized enum value is an error where it decides registration, keys or
  admission. Elsewhere, ignore the item that carries it.
- Senders MUST NOT rely on a new member or value unless the receiver advertised
  support for it in discovery or through an extension.

### Provider origin and operation URLs

The provider origin is a [canonical origin](#canonical-origins), and every
signature's audience is that exact string. A client may lowercase a configured
origin and drop its default port or trailing slash, but it MUST reject a result
that is not canonical rather than sign another form.

Fetch discovery with an unauthenticated `GET`. Its `provider` MUST equal the
configured origin. Clients MUST disable redirects for discovery and for calls that
carry credentials. Sign encoded paths and query strings exactly as transmitted.

#### Canonical origins

Signed contexts and trusted expected origins use exact ASCII strings. Validate
or reject them without normalization or DNS resolution. An origin is lowercase
`https://HOST`, optionally followed by a decimal port between 1 and 65535 without
leading zeros. Omit default port 443. Development `http://HOST` permits only
`localhost`, `127.0.0.1` or `[::1]`, and omits default port 80. The limit is
2,048 characters; path (including `/`), query, fragment, userinfo, whitespace,
control characters, percent escapes and backslashes are prohibited.

HOST is one of:

- Lowercase ASCII DNS labels of 1 to 63 characters, at most 253 total. Labels
  start and end with `[a-z0-9]`, with only `[a-z0-9-]` inside. The final label
  starts with `[a-z]`. Single-label names are allowed; empty labels, trailing
  dots, Unicode and all `xn--` labels are rejected.
- Four decimal IPv4 octets, 0 to 255, without leading zeros or shortened aliases.
- Bracketed IPv6 with shortest lowercase hex pieces, compressing the longest run
  of at least two zero pieces; the first run wins a tie. IPv4-mapped addresses
  use hex (`[::ffff:c000:280]`), not dotted tails. Zones, expanded aliases and
  unbracketed literals are rejected.

These rules keep origin bytes identical across implementations and match the
WHATWG URL origin of every accepted value. The shared
[origin fixtures](fixtures/origins.fixtures.json) include rejected aliases and
independently generated IPv6 cases; their [Node verifier](tests/origins.fixtures.mjs)
checks the accepted WHATWG bytes.

Discovery contains `provider`, the arrays `protocols` and `signatures`,
`authorization`, `limits`, and optional `extensions`. Before sending credentials,
clients reject an unsupported protocol, signature, authorization scheme, or
required extension.

Each [operation](README.md#operation-reference) is at `/v1/nxs/<operation>` on
the provider origin.

### Authorization and limits

`authorization.schemes` lists the schemes the provider accepts:
`anonymous-proof-of-work`, which can only create a service, and `bearer-token`.
The provider decides how tokens are issued, what they authorize, and whether they
can be reused. Every flow also requires proof that the instance owns its signing key.

`limits.heartbeatIntervalMs` is the check-in interval before the first heartbeat
response, between 1000 and 30000. The heartbeat response sets later check-ins.

Request bodies are at most 65536 bytes, and providers accept request timestamps
within 60 seconds of their clock. Providers reject larger bodies. Clients limit
response size before parsing.

### Errors

Errors are `{"code":"lowercase_machine_code"}` with an appropriate HTTP failure
status. A 4xx response means the operation had no effect; a 5xx or missing
response leaves the outcome unknown. Clients act on these codes and treat
any other code as a failure of that request:

| Code | Status | Meaning | Client action |
| --- | --- | --- | --- |
| `recovery_required` | 409 | The completion was already consumed | Recover with a fresh challenge |
| `recovery_unavailable` | 403 | Nothing to recover: the registration is unknown, deregistered, or its challenge was never completed | Complete a saved challenge if there is one, otherwise register again |
| `generation_fenced` | 409 | A newer generation owns this registration | Stop; another process replaced this one |

Transport failures and HTTP 429, 502, 503 and 504 are transient. Clients may
retry them a bounded number of times with capped, jittered exponential backoff.
Retrying never extends a lease or challenge expiry.

## Registration and persistent identity

### Save the instance key

Generate a fresh P-384 machine signing key for each logical instance. Save it
before requesting a challenge. A restart reuses that instance's saved state;
live replicas cannot share a key or state directory. Images and templates MUST
contain neither machine identity nor DTLS private keys.

Clients lock their state directory and write private state atomically with
owner-only permissions. Sync both files and directories to durable storage.
If saving state fails, stop advertising healthy readiness.

### `register` request

The request contains `protocol`, `publicKeyJwk`, and an optional `label`. A host
with a bearer token registers with it; a host without one registers anonymously
with proof of work.

The provider selects the mode from the token's authority: `new-service` or
`attach-instance`. Without a token it can only select `new-service`. The challenge
contains the selected mode, bound into its digest and proof; hosts reject unknown
modes and anonymous attachment. Hosts never parse token contents.

`label` is the instance's initial display name, at most 128 characters. Providers
may let operators rename it later. It grants no permission.

Send a bearer credential only to the enrollment `register` operation, in
`Authorization: Bearer <token>`. It MUST NOT appear in JSON, proofs, saved state,
or logs. `attach-instance` requires bearer authorization. The token alone decides
what the instance attaches to; clients cannot choose provider-owned resource IDs.

The public JWK is EC/P-384. Its `x` and `y` values use canonical, unpadded
base64url and each encode exactly 48 bytes. It MUST NOT contain `d`. The RFC 7638
thumbprint is SHA-256 of UTF-8 JSON with members in this exact order:
`crv,kty,x,y`. ES384 signatures use the 96-byte IEEE-P1363 form `r || s`, encoded
as unpadded base64url. Reject DER signatures and noncanonical base64url.

The challenge response contains `protocol`, `signature`, `challengeId`, `nonce`,
`audience`, `thumbprint`, `context`, `contextDigest`, `expiresAt`, `serverTime`,
and `pow: {difficulty}`. A challenge for a bearer registration also contains
`authorization: {scheme,reference}` naming the token's authority; hosts reject a
bearer challenge without it.

Proof-of-work difficulty is between 0 and 24. Bearer-authorized and recovery
flows use zero. An authorization reference is an opaque identifier, never the
credential itself. Expiry and server times are integer epoch milliseconds.

### `complete` request

Canonical arrays are the UTF-8 encoding of the JSON text that ECMA-262
`JSON.stringify` produces, with no whitespace or Unicode normalization.
Use an empty string for a missing context string. `contextDigest` is the
unpadded base64url SHA-256 digest of:

```text
[mode,label,authorizationId,serviceId,registrationId]
```

The completion proof is:

```text
[protocol,"complete",audience,challengeId,nonce,thumbprint,contextDigest,
 expiresAt,proofNonce,idempotencyKey]
```

Proof of work counts the leading zero bits in SHA-256 of those bytes. Send
`protocol,challengeId,proofNonce,idempotencyKey,signature` to complete registration.
The provider MUST check expiry, binding, signature, difficulty, current authority,
and single-use completion atomically with resource creation.

Retrying completion MUST NOT return one-time key secrets again. If completion
was interrupted, recover the registration by proving ownership of the same key.

Completion returns `protocol,provider,registrationId,instanceId,keyId,
leaseGeneration`, plus optional one-time `admissionKey` and `extensions`. It
atomically starts a new generation, clears previous readiness and resets the
operational sequence to zero. Save the IDs and key material before heartbeat.
Remove secrets from registration results exposed to applications and from
diagnostic output.

Completion may also return `serviceId` and `publicAddress`, always together.
They describe a public endpoint at that observation and are not stable runtime
identity. Attached instances may return neither, since a service may have zero
or several public endpoints. The default standalone registration creates a
public endpoint alongside the runtime.

### Operator actions

A provider may offer the host's operator a link, such as one that takes ownership
of a newly created service. Completion may return
`operatorAction: {url,text,expiresAt}`, and a heartbeat reply may set
`operatorActionAvailable: true` while the provider can issue one. Only after such
a reply may a host without an unexpired action request one with the signed
`operator-action` operation, body `{}`, over HTTPS. Its reply is
`{operatorAction?}`.

`url` is an `https` URL, or `http` on the loopback hosts canonical origins allow,
of at most 2048 characters without userinfo. `text` is 1 to 256 characters without
control characters and `expiresAt` is epoch milliseconds. The URL may carry a
one-time credential: hosts show `text` and `url` to their operator and never open,
forward or save them. A retry with the same idempotency key does not return the
link again, and recovery never returns one; request a fresh link instead.

### Recovery

Recovery uses `register {registrationId,protocol}` and the same completion
proof; its challenge context has `mode: "recover"` and the registration's
`registrationId`.
Recovery preserves `instanceId` and `registrationId`; public endpoints can change
without a new runtime identity. If the completion reply was lost, the host does
not know its `registrationId` yet; it recovers by sending the saved `challengeId`
in that field, and the provider accepts it for the registration that challenge
created. The challenge context then carries the real `registrationId`, which the
host adopts. A deregistered instance cannot recover.

## Signed requests and machine-key maintenance

### Sign operational requests

Use the registered machine key for every operational request.

Required headers are `nxs-instance-id`, `nxs-key-id`, `nxs-timestamp`,
`nxs-signature-version`, `nxs-generation`, `nxs-sequence`, `nxs-signature`, and
`idempotency-key`. The timestamp is epoch milliseconds. Generation and sequence
are nonnegative integers. Save each reserved sequence number before sending its
request. The signature covers this array:

```text
[protocol,signatureVersion,audience,method,encodedPathAndQuery,timestamp,
 instanceId,keyId,idempotencyKey,generation,sequence,base64url(sha256(bodyBytes))]
```

For an empty body, hash a zero-length byte sequence. Providers reject stale
generations, reused sequence numbers, invalid timestamps, and invalid signatures.
An idempotent retry can return the recorded result, with secrets removed, if its
intent and semantic request are unchanged. It cannot apply the operation again.

Each completed registration or recovery starts a generation exactly once. The
provider rejects writes from previous generations. A replay cannot advance the
generation or extend the original lease. Replaying a consumed completion returns
`recovery_required`.

### Rotate a machine key

Send `rotate {publicKeyJwk,proof}` with the replacement key. The proof bytes are
`[protocol,"rotate",audience,instanceId,oldKeyId,newThumbprint,generation,idempotencyKey]`.
The reply is `{keyId,oldKeyRetiresAt?}`: the new key's ID and when the provider
stops accepting the old key unless it is retired sooner. Save the replacement
private key before requesting rotation. Save the result before retiring the old
key. After an interrupted rotation, recovery can use the provider's returned key
thumbprint to identify which key is current.

`retire` carries `{keyId}` and must be signed by a different, current machine
key; it ends that key immediately. `deregister` carries `{}` and permanently ends
registration. Both reply with an empty object. Neither is an admission-key
rotation or a graceful drain. A host whose saved state will not outlive the
process may deregister on shutdown, since it could never recover that
registration.

## `heartbeat`

Required fields: `acceptingPlayers,capacity,clockUnixMillis`.
Optional observations: `playerCount,software,location,serverStatus,gameOutcomes,extensions`.
Profile/key exchange: `hostProfile,hostProfileRevision,installedAdmissionKeyIds,admissionKeyRequestId`.

- `acceptingPlayers` is the host's willingness and ability to accept new players.
  Set it false during a fault, pause or shutdown and true to resume. On orderly
  shutdown, stop accepting new joins and send it immediately rather than waiting
  for the next check-in. Keep counting existing players while paused. Missing
  heartbeats independently expire routing. Neither acceptance nor actual counts
  are derived from public listing values.
- `capacity` is an integer from 0 to 1000000.
- `gameOutcomes` is `available` when the integration observes game acceptance and
  rejection, otherwise `unavailable`. Send it with the initial profile and whenever
  it changes. Omission preserves the value in this generation, initially unavailable.
- `software` is `{name,version}`: the host software, 1 to 64 characters, and its
  version, 1 to 128 characters. Send it with the initial profile and whenever it
  changes. Omission preserves the value in this generation.
- `location` is `{country?,city?,latitude?,longitude?}` or `null`: where the
  operator says the host runs. `country` is an uppercase ISO 3166-1 alpha-2 code;
  `city` is 1 to 120 characters without control characters; `latitude` (-90 to 90)
  and `longitude` (-180 to 180) come together. A location has a country,
  coordinates or both. Send it, or `null` when none is declared, with the initial
  profile and whenever it changes. `null` clears an earlier declaration and
  omission preserves the value. Without one, providers estimate location from the
  connection; they may let operators override either.
- `clockUnixMillis` is an increasing snapshot clock within the generation and
  must be within 30000 milliseconds of provider time.
- `serverStatus` carries `name,level,maxPlayers,gameType` and optional `players`.
  When players is omitted, use the supplied actual `playerCount`; without either,
  the listing is incomplete. Providers own advertised protocol and version.
  Renew metadata together: omission does not refresh a previous snapshot.
  Providers may ignore host listing metadata when several hosts share a public endpoint.
  `name` is 1 to 128 Unicode characters and `level` is 0 to 128; neither permits
  control characters or unpaired surrogates. `players` and `maxPlayers` are
  integers from 0 to 1000000. `gameType` is 0 (survival), 1 (creative) or
  2 (adventure).

The reply is described under [readiness, lease and schedule](#readiness-lease-and-schedule).

### Serving control

The game server owns admission. The provider may stop routing players to it, but
cannot command its listener to serve, drain or close. Only explicitly enabled
assisted joins (players or connectivity checks) can be unsolicited, over
[WebSocket transport](control-v1.md).

### Actual player counts

Optional `playerCount: {connectedPlayers, sampledAt}` reports the actual number of
players connected to this runtime, including existing players while it is draining.
`connectedPlayers` is an integer from 0 to 1000000; `sampledAt` is epoch
milliseconds from the host clock. The heartbeat's admission `capacity` must come
from the same observation. A cached count keeps its original `sampledAt`. Count
may exceed capacity after a capacity reduction. Capacity zero means no admission.

This count is independent of the public `serverStatus.players` and its advertised
`maxPlayers`. A public/global override must never change the count or admission
capacity. Do not estimate connected players from load, successful tickets, reserved
slots or public listing totals. Omit `playerCount` when it is unknown; omission and
zero are distinct. Omission does not refresh the previous count. Providers fence
samples by the authenticated lease generation and sequence, track sample and receipt
times separately, and exclude stale/unknown samples from count-dependent routing
unless an explicit fallback policy applies. A retry must not freshen a sample.

### Publish the host profile

`heartbeat.hostProfile` contains `candidates`, `dtlsFingerprint`, `admissionKeyId`,
`sctpPort`, `maxMessageSize`, and
`statelessAdmission: {capability,incarnation,assistedFamilies?}`. Generate a fresh
random 16-byte `incarnation`, encoded as lowercase hex, for each bound endpoint.
`sctpPort` (1 to 65535) and `maxMessageSize` (1 to 262144) are the host's SCTP port
and largest message; providers carry them into the admission token and client SDP.
`assistedFamilies` lists the address families, 4 and 6, on which the host accepts
[assisted joins](control-v1.md#assisted-joins); omit it when assistance is off. The fingerprint is `sha-256 ` followed by the
certificate's digest bytes in colon-separated uppercase hex.

Each candidate is a UDP ICE candidate for component 1 and contains
`foundation,priority,address,port,type`. Publish between 0 and 32 candidates. An
empty array withdraws advertised routes without draining the listener or
established peers. Foundations match `[A-Za-z0-9._:-]{1,32}`; priority is 1 to
2147483647, port is 1 to 65535, and type is `host`, `srflx` or `relay`.
Addresses are IP literals.

Publish the latest same-socket STUN mapping as an ordinary `srflx` candidate.
The host keeps the mapping warm independently of heartbeat timing and republishes
when it changes or becomes unavailable. Providers use the heartbeat lease to
bound retained profiles. If a host supplies an optional `expiresAt` on a `srflx`
candidate, providers also honor that deadline; other candidate types omit it.

The game server chooses which usable UDP candidates to advertise. A host can bind
to all interfaces, but it cannot advertise wildcard `0.0.0.0` or `::`. The
deployment or provider must establish reachability through NAT or a relay; a
successful registration does not prove that clients can reach the address.

Prepare the host's DTLS certificate and key before publishing its profile. Keep
the private key local. All peers using that profile use that certificate, so
clients see the fingerprint the provider advertised. The host may use a new
certificate for a later endpoint incarnation after publishing its new fingerprint.
Sharing one permanent certificate across a fleet is not advised.

Three types of key have separate jobs:

| Key | Purpose |
| --- | --- |
| Machine signing key | Authenticate the host's requests to the provider |
| DTLS certificate and private key | Authenticate the host during the client connection |
| Admission key | Protect and validate the client's admission token |

The provider assigns `hostProfileRevision` in its reply. Send that revision
on later heartbeats until the profile changes. A request retry returns the same
revision. Profile publication, acknowledgement of its installed key, and the
lease update must commit consistently. A profile using an uninstalled epoch
cannot become routable.

### Admission-key exchange

`installedAdmissionKeyIds` contains at most eight distinct four-character uppercase
alphanumeric IDs, ordered with the active epoch last. Save and install every
listed key before sending it. The last ID must be the provider's current or
pending epoch. Publish a matching profile when changing the active epoch.

To provision a replacement, include a random `admissionKeyRequestId` of 16 to 128 URL-safe
characters, saved before sending. The response includes `admissionKeyRequest: {id,keyId}`
and, on first delivery only, `admissionKey: {keyId,secret}`. A key secret has 32 to
256 UTF-8 characters. An idempotent retry cannot mint another key or return the
secret again. If its delivery was lost, use a fresh request ID to provision a
replacement. A provider may retire an unacknowledged, superseded pending key.

Install the key atomically, then immediately publish its profile and acknowledge
it in heartbeat. The provider cannot issue tokens under a new epoch before that
acknowledgement. Replies include `admissionKeyRetirements: [{keyId,retireAfter}]` for older
reported epochs; hosts retain those keys until `retireAfter`. Repeated replies
preserve the original deadlines; they cannot extend key life. Providers must
allow outstanding tokens their defined overlap window. Reject tokens under a
retired epoch and erase retired material. Key rotation never extends token expiry.

### Readiness, lease and schedule

The reply includes `hostProfileRevision`, `readiness: {routable,reasons}`,
`admissionKeyRetirements`, and:

```text
checkIn: {afterMillis,leaseMillis,minUpdateIntervalMillis}
```

Each duration counts from when the host sent the heartbeat, so it does not depend
on either clock. Check in again after `afterMillis`, 1000 to 86400000. The
provider routes to the host until `leaseMillis` passes without a newer heartbeat;
it exceeds `afterMillis` by at most 300000. Publish changed activity or status no
sooner than `minUpdateIntervalMillis` apart, at least 1000 and at most
`afterMillis`. Hosts reject a schedule outside these bounds. While an initial usable profile is unavailable,
the provider can omit `checkIn`; the host then checks in at the discovery
interval. A host is routable only with a live lease, a usable profile and an
acknowledged installed key, and never while it reports `acceptingPlayers: false`.

Readiness is a current provider observation; only the recorded `checkIn` grants a
lease. Request replay returns that original grant. Hosts use monotonic timers and
count network time against the interval. Restart immediately publishes fresh state
and discards the prior schedule. A control-plane outage lets leases expire but
does not close established sessions.

## `outcomes`

Request: `{events:[{ticketId,stage,occurredAt,reason?}]}` with at most 100 events.
`occurredAt` is epoch milliseconds; `reason` is a bounded code of at most 128 characters.
The ticket ID derives from the authenticated admission carrier, never from an
unauthenticated packet. The provider scopes correlation to the signed instance.

| Observation | Stage |
| --- | --- |
| Both data channels become usable | `ticket.data_channels_open` |
| An authenticated observed attempt fails before transport becomes usable | `ticket.failed`, with a bounded `reason` |
| The game admits or rejects the player | `ticket.game_joined` or `ticket.game_rejected` |

The transport stages are required for observed attempts; the game stages are
required when `gameOutcomes` is `available`. `ticket.ice_seen` is an optional
diagnostic stage. Success and failure describe the observed boundary, not an
inferred later stage.

When discovery advertises `dev.opencollab.nxs.connectivity` version 1, events may
also include the observed client `remoteAddress` (numeric IP) and `remotePort`
(1 to 65535) as a pair. Use the actual transport observation; omit the pair when
unavailable.

Send batches asynchronously, independently of heartbeat timing. Reporting never
delays admission or renews a lease. A successful empty response acknowledges the
whole batch. Repeated observations must be deduplicated by instance, ticket,
stage, occurrence time and reason, including when a retry uses a new request ID.
Queue and persist redacted reports with a finite bound. Backpressure must not
block admission or lease renewal. Neither absent reports nor an unreachable host
proves a particular client's outcome. Never send SDP, credentials, private keys,
player identity or game payloads.

## Stateless admission carrier

The host validates admission locally with its installed keys. No provider push,
poll, lookup or pre-staged client state may gate admission.

### Carry the token in the ICE username

The client's first STUN USERNAME is `<answerUfrag>:<clientUfrag>`, where:

```text
answerUfrag = "NXS1" + keyId + unpaddedBase64(nonce || ciphertext || tag)
```

Use the standard base64 alphabet, including `+` and `/`, which ICE permits.
Do not use base64url. The total ufrag length is at most 256 characters. Before
allocating peer state, reject noncanonical encoding, trailing padding, a wrong
prefix, unknown key epochs, and oversized input.

AES-256-GCM uses a random 12-byte nonce and a 16-byte tag. Its key is
`HMAC-SHA256(secret, "nxs-stateless-aead-v1" || NUL || audience)`.
The audience is `nxs-stateless-host-v1/<incarnation>`. The additional authenticated
data (AAD) is
`"nxs-stateless-admission-v1" || NUL || ("NXS1"+keyId) || NUL || audience || NUL || clientUfrag`.

| Plaintext offset | Size | Meaning, unsigned big-endian where numeric |
| --- | --- | --- |
| 0 | 4 | Expiry in epoch seconds, exactly representable in milliseconds |
| 4 | 32 | SHA-256 client certificate fingerprint |
| 36 | 2 | Client SCTP port, 1 to 65535 |
| 38 | 4 | Client maximum message size, 1 to 262144 |
| 42 | 16 | Identity binding: first 16 raw bytes of the HMAC below |
| 58 | 8 | NetherNet network ID, unsigned 64-bit |
| 66 | 1 | Client ICE password length, 22 to 91 |
| 67 | N | Client ICE password in ICE base64 alphabet |

Network ID **zero is reserved for [diagnostics](diagnostic-v1.md)**. For zero,
the identity-binding slot carries the attempt ID and a mandatory 59-byte target
extension follows the password. Player issuers reject zero; nonzero admissions
reject trailing diagnostic data. Hosts select the diagnostic handler only after
AEAD verification, before any player reservation or pipeline event.

### Bind signaling identity to the game login

The provider MUST verify the client's signaling assertion, including its proof
of possession over the offered DTLS fingerprint. It MUST derive the binding from
that verified assertion's `cpk`, never from request headers, account identifiers
or other account metadata:

```text
identityBinding = HMAC-SHA256(secret,
    UTF8("nxs-identity-binding-v1") || 0x00 || UTF8(audience) || 0x00 || UTF8(cpk))[0:16]
```

`secret` is the UTF-8 encoding of the installed admission key secret, **not** a
base64 decoding. `audience` is exactly `nxs-stateless-host-v1/<incarnation>`, as used
for this token's encryption. `cpk` is standard base64 of canonical P-384 public-key
SPKI DER: `id-ecPublicKey`, named curve `secp384r1`, and an uncompressed SEC1 point
(`04 || X[48] || Y[48]`). Validate curve parameters and the point; normalize the
validated key rather than retaining the client's original encoding. Standard
base64 padding rules apply (this 120-byte SPKI needs no padding). The 16 raw MAC
bytes occupy offset 42; hex is only a fixture/debug representation.

After normal Bedrock LoginPacket chain/token and client-data signature validation,
the component terminating the direct client transport MUST canonicalize the
validated login identity key the same way, recompute the MAC using the admitted
token's secret and audience, and compare all 16 bytes in constant time. Reject
missing, expired, revoked or mismatched binding evidence before accepting the
player. Validity of the LoginPacket alone does not bind it to this transport.

The verifier is one-shot. Retain the actual admitted key epoch until comparison,
channel closure, rejection or the token's login deadline; do not look up the
current secret by key ID later. Routine key replacement or retirement stops new
admissions but preserves pending comparisons, even if a key ID is reused.
Explicit revocation discards installed and retained epochs and invalidates
pending logins. Convert the remaining token lifetime to a monotonic deadline at
admission. Opening data channels does not complete login; pending logins expire
even after transport connection. Successful game sessions may outlive the
token. Release retained material on capacity or replay rejection and failed
transport setup as well as channel closure.

An explicitly configured trusted forwarding listener delegates player
authentication to its proxy under its own forwarding contract. A proxy may
re-sign the backend chain, so the backend MUST NOT compare that chain to the
original player's signaling key. After validating the forwarded fields, the
backend completes the admission without that comparison. Only such a trusted
listener skips it: do not infer forwarding from client fields or a missing or
mismatched binding. The proxy terminating a direct NetherNet client is
responsible for the equivalent signaling and login comparison there.

The host's local ICE password is the unpadded standard base64 encoding of the
first 24 bytes of
`HMAC-SHA256(secret, "nxs-stateless-ice-v1" || NUL || audience || NUL || answerUfrag)`.
The ticket correlation ID is the first 16 bytes of SHA-256 of the ASCII answer
ufrag, encoded as lowercase hex.

### Validate the first packet

A token can be valid for at most 120 seconds; hosts accept any token within that
limit, measured on their own clock. Providers mint lifetimes short enough to absorb
clock skew between them. Before assigning the UDP tuple to a
peer or allocating a peer connection, the host checks expiry, field bounds, GCM
authentication, client binding, and STUN MESSAGE-INTEGRITY. The DTLS handshake
MUST then verify the client fingerprint from the token.

Only a retransmission of the identical token from the same UDP tuple can reuse
a reservation. Reject the same token from another tuple. Also reject a conflicting
admission on an occupied tuple.

Limit the number of sessions, pending handshakes, used-token records, queued
validation tasks, and retained requests. Duplicate requests for the same pending
attempt share one decision. Preserve enough of the first request to respond after
acceptance: completing admission MUST NOT depend on the client retransmitting.
Release admission capacity only after the connection's resources have been
released. A failed integrity check MUST NOT consume the token, since a copied
token alone does not prove that the sender has its ICE password.

## Optional extensions

Providers can add optional application metadata, such as an account-claim link,
without making it part of NXS.

`extensions` is an object with at most 16 reverse-DNS namespace keys, such as
`com.example.feature`, and at most 16384 bytes of encoded UTF-8 JSON. Keys use
lowercase domain-style labels and have at most 128 characters. Each value is
`{version:positiveInteger,critical:boolean,data:object}`.

Extensions defined by this specification use `dev.opencollab.nxs.*`, including
`dev.opencollab.nxs.connectivity` and `dev.opencollab.nxs.websocket`.
Provider-specific extensions use the provider's own reverse-DNS namespace.

Pass through or ignore unknown optional extensions; never execute them
automatically. Reject unsupported critical extensions before sending credentials
or publishing readiness. An optional extension cannot change the core protocol rules.
TLS and request signatures still authenticate bodies and operation paths.

An extension can advertise URLs in `data.operations`. An application can request
one of these operations only after validating the namespace, version, and meaning.
The generic transport still requires the same provider origin and signs the
exact path.

## Optional connectivity observation

The noncritical `dev.opencollab.nxs.connectivity` version 1 extension carries discovery,
heartbeat capability and regional feedback. Discovery supplies STUN servers:

```json
{"version":1,"critical":false,"data":{"stunServers":[{"host":"stun.cloudflare.com","port":3478}]}}
```

Resolve up to two servers, choosing a numeric address per supported family.
Without configured endpoints, prefer public local addresses, then discover STUN
mappings on the gameplay socket. Publish fresh mappings immediately and maintain
them independently of heartbeat timing. Explicit endpoints are the complete
eligible set and suppress automatic discovery and STUN for all families. An
absent or empty server list leaves STUN discovery unavailable.

Host heartbeats report:

```json
{"version":1,"critical":false,"data":{"diagnostics":false,"candidateRevision":1,"method":"discovered"}}
```

Assistance defaults off and requires WebSocket; when enabled it uses bounded
per-join discovery instead of background warming. Configured endpoints restrict
assistance to their public families. Probe verdicts never change this choice.
`method` says how the candidates were found: `defined`, `discovered` or `warm_stun`.
`candidateRevision` changes with candidate material or native mapping ownership,
including replacement with the same address; unchanged STUN refreshes retain it.
When diagnostic targets differ from the player candidates, include `probeCandidates`
in the connectivity data using the same candidate shape. This list replaces the
profile candidates for diagnostics only, so withdrawn endpoints can still be
tested for recovery. Omit it when the two lists match; an empty list withdraws
all direct probe targets.

Provider feedback uses `{method,candidateRevision,checks}`. Up to eighteen checks
retain `{region,family,method?,target?,outcome,checkedAt,expiresAt}`. `method` is
how the checked candidate was found, or `per_join` for an assisted check. A target
is an exact numeric `{address,port}`; an assisted failure may omit it. Outcomes are
`established`, `not-established`, `unknown` and `unavailable`. Accept fresh results
only for the current revision and endpoint. Newer results replace older ones per
region/method/target; failure wins equal timestamps, while inconclusive results
leave the previous decision intact.

Without assistance, failed public endpoints are withheld from player offers
unless another region has a successful result for that endpoint. Keep those
endpoints eligible for recovery probes and keep STUN warming; a subsequent success
can restore them. Observation expiry alone does not change the last decision.
Mapping replacement discards the old mapping's feedback. With assistance enabled,
failed public candidates remain available. Automatic discovery also includes
private local candidates when no public address exists or a matching check fails.
Private addresses remain useful for LAN/VPN clients but are not regional targets.
Publication changes leave established sessions intact.

Diagnostic admission is a separate local opt-in. A successful heartbeat installs
context, existing admission keys and current probe targets for at most five
minutes, bounded by the check-in lease, key retirement and mapping expiry. Report
`diagnostics:true` only while that installation is current. Withdrawn player
offers may remain diagnostic targets; assisted-only families need no published
player candidate. Retries cannot extend authority; drain/close disable diagnostics.
See [diagnostics](diagnostic-v1.md) for admission, signatures and the exchange.

## Conformance

Run `node docs/external-signaling/tests/nxs-v1.fixtures.mjs` to verify the
signature and admission fixtures independently. Report stock-client gameplay
separately from these checks.
