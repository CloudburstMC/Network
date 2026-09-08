# NXS wire reference

This is the normative format reference for the [three integration flows](README.md).
Protocol identifiers describe the current experimental format; this revision
replaces its earlier operation surface in place.

## Version and discovery

| Field | v1 value |
| --- | --- |
| Registration/request protocol | `nethernet-external-signalling-v1` |
| Machine request signature | `nxs-es384-v1` |
| Operational profile | `nxs-admission-v1` |
| Discovery path | `/.well-known/nethernet-external-signalling` |
| Stateless capability | `nethernet.stateless-admission.v1` |
| Stateless carrier prefix | `NXS1` |

### Provider origin and operation URLs

The configured origin MUST use HTTPS. HTTP is permitted only for loopback
development. Normalize the origin by lowercasing its scheme and host and omitting
default ports. It cannot contain credentials, a path, a query, or a fragment.

Fetch discovery with an unauthenticated `GET`. Its `provider` and `controlOrigin`
MUST equal the configured origin. Each operation URL MUST have that same origin
and contain no userinfo or fragment. Clients MUST disable redirects for discovery
and for calls that carry credentials. Sign encoded paths and query strings
exactly as transmitted.

Discovery contains `provider`, `controlOrigin`, the arrays `protocols`,
`signatures`, `profiles`, and `modes`, an `operations` map, `authorization`,
`limits`, and optional `extensions`. Before sending credentials, clients reject
an unsupported protocol, profile, signature, mode, or required extension.

The [operation table](README.md#operation-reference) defines the operation names. Clients get their
URLs from discovery. `/v1/nxs/<operation>` is a recommended path, but providers
can use other paths.

### Authorization and limits

`authorization` contains `header: "Authorization"` and a `schemes` array. Each
entry has a `scheme` and its supported `modes`:

| Scheme | Allowed modes |
| --- | --- |
| `anonymous-proof-of-work` | `automatic`, `new-service` |
| `bearer-token` | `automatic`, `new-service`, `attach-instance` |

A provider need only advertise the schemes it accepts. It decides how tokens
are issued, what they authorize, and whether they can be reused. Every flow also
requires proof that the instance owns its signing key.

| Limit | v1 constraint |
| --- | --- |
| `maxBodyBytes` | At most 65536 |
| `clockSkewMs` | At most 60000 |
| `heartbeatIntervalMs` | 1000–30000 |
| `leaseMs` | Advertised lease duration |

`checkInVersion: 1` enables the provider to set the next check-in time in its
response. A provider MUST advertise every limit it enforces, reject oversized
bodies, and return errors as `{"code":"lowercase_machine_code"}` with an
appropriate HTTP failure status. Clients limit response size before parsing.

On a transient transport failure or HTTP 429, 502, 503, or 504, the supplied
client makes at most three attempts in total. Retries use exponential delays
with jitter and an upper bound. A `Retry-After` value over ten seconds returns
a retry-later result. Outcome uploads use one attempt with a three-second timeout
and a ten-second failure backoff so they cannot starve heartbeat renewal.
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

The request contains `protocol`, `mode`, `profile`, `publicKeyJwk`, explicit
`authorization: {scheme}`, and optional `label` and `placement`.

`mode: "automatic"` lets the provider select `new-service` or `attach-instance`
from the credential's authority. Without a bearer token it can only select
`new-service`. Discovery must advertise automatic support for the selected scheme.
The challenge contains the selected concrete mode, bound into its digest and proof;
hosts reject unknown modes and anonymous attachment. Opaque token contents are never
parsed by the host. Explicit modes remain available to protocol integrations.

Metadata may be supplied on anonymous new-service registration when permitted by
the provider. It applies only to the new service and cannot authorize attachment.
Placement is still echoed and digest-bound, including every tag.

Send a bearer credential only to the enrollment `register` operation, in
`Authorization: Bearer <token>`. It MUST NOT appear in JSON, proofs, saved state,
or logs. `attach-instance` requires both bearer authorization and placement.
The token authorizes access to the service; a client-provided label grants no
permission.

Placement is `{region,pool,tags?}`:

| Field | Constraint |
| --- | --- |
| `region` | Immutable routing label matching `[A-Za-z0-9_-]{1,32}` |
| `pool` | Immutable routing label matching `[A-Za-z0-9_-]{1,64}` |
| `tags` | At most 16 keys matching `[A-Za-z0-9_.-]{1,32}`; values are trimmed strings of 1–64 characters with no control characters |

The challenge binds the exact placement. At completion, the provider rechecks
that the token authorizes it as part of the same atomic operation that creates
the registration. These fields do not prescribe how a provider selects a host.

The public JWK is EC/P-384. Its `x` and `y` values use canonical, unpadded
base64url and each encode exactly 48 bytes. It MUST NOT contain `d`. The RFC 7638
thumbprint is SHA-256 of UTF-8 JSON with members in this exact order:
`crv,kty,x,y`. ES384 signatures use the 96-byte IEEE-P1363 form `r || s`, encoded
as unpadded base64url. Reject DER signatures and noncanonical base64url.

The challenge response contains `protocol`, `signature`, `challengeId`, `nonce`,
`audience`, `thumbprint`, `context`, `contextDigest`, `expiresAt`, `serverTime`,
and `pow: {algorithm:"sha256-leading-zero-bits-v0",difficulty}`.

Proof-of-work difficulty is 0–24. Bearer-authorized and recovery flows use zero.
An authorization reference is an opaque identifier, never the credential itself.
Expiry and server times are integer epoch milliseconds.

### `complete` request

Canonical arrays use UTF-8 JSON with no whitespace or Unicode normalization.
Use an empty string for a missing context string. `contextDigest` is the
unpadded base64url SHA-256 digest of:

```text
[mode,profile,label,authorizationId,serviceId,region,pool,registrationId]
```

When tags are nonempty, append `tagsDigest` to that array. Compute `tagsDigest`
in the same way from sorted `[key,value]` pairs. The completion proof is:

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

Completion returns `protocol,provider,registrationId,serviceId,instanceId,keyId,
profile,publicAddress,placement,heartbeatIntervalMs,leaseGeneration,leaseDeadline,
readiness`, plus optional one-time `ticketKey` and `extensions`. Completion atomically starts a new generation, clears previous readiness and
resets the operational sequence to zero. Save the IDs and key material before
heartbeat. Recovery uses `register {registrationId,protocol,profile}` and the
same completion proof. A deregistered instance cannot recover. Remove secrets from registration results exposed
to applications and from diagnostic output.

## Signed requests and machine-key maintenance

### Sign operational requests

Use the registered machine key for every operational request. The enrollment
bearer token is used only for the challenge request.

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

Each completed registration/recovery starts a generation exactly once. The
provider rejects writes from previous generations. A replay cannot advance the
generation or extend the original lease. Replaying a consumed completion returns
a recovery-required error; recover with a fresh challenge.

### Rotate a machine key

The rotation proof bytes are `[protocol,"rotate",audience,instanceId,oldKeyId,
newThumbprint,generation,idempotencyKey]`. Save the replacement private key before
requesting rotation. Save the result before retiring the old key. After an
interrupted rotation, recovery can use the provider's returned key thumbprint
to identify which key is current.

`retire` carries `{keyId}` and must be signed by a different, current machine
key. `deregister` carries `{}` and permanently ends registration. Neither is an
admission-key rotation or an ordinary graceful drain.

## `heartbeat`

Required fields: `healthy,capacity,load,protocolVersion,clockUnixMillis,
checkInVersion,state,appliedStateRevision,gameOutcomes`.
Optional fields: `build,region,serverStatus,hostProfile,hostProfileRevision,
installedKeyIds,keyRequestId,extensions`.

- `capacity` is an integer from 0 to 1000000; `load` is a finite number from 0 to 1.
- `state` is `serving`, `draining` or `closed`. A draining endpoint cannot resume
  serving in the same generation; a fresh endpoint requires recovery/completion.
- `gameOutcomes` is `available` when the integration observes game acceptance and
  rejection, otherwise `unavailable`.
- `appliedStateRevision` is a nonnegative integer. A response carries
  `desiredState: {revision,state}`. Reject unknown states or regressing revisions;
  acknowledge only state that finished applying. Pending application triggers
  a bounded earlier heartbeat. Receipt alone is not acknowledgement.
- `clockUnixMillis` is an increasing snapshot clock within the generation and
  must be within 30000 milliseconds of provider time.
- `region` cannot change authorized placement. `serverStatus` contains
  `name,protocol,version,level,players,maxPlayers,gameType`; it is independent of
  routing capacity/load. Omitted or failed status publication does not refresh
  a previous status snapshot.

### Publish the host profile

`heartbeat.hostProfile` contains `candidates`, `dtlsFingerprint`, `credentialKeyId`,
`sctpPort`, `maxMessageSize`, and `statelessAdmission: {capability,incarnation}`.
Generate a fresh random 16-byte `incarnation`, encoded as lowercase hex, for
each bound native endpoint. The fingerprint is `sha-256 ` followed by the
certificate's digest bytes in colon-separated uppercase hex.

Each candidate contains `foundation,component,protocol,priority,address,port,type`.
Publish 1–32 candidates. Foundations match `[A-Za-z0-9._:-]{1,32}`; component is
1, protocol is `udp`, priority is 1–2147483647, port is 1–65535, and type is
`host`, `srflx` or `relay`. Addresses are IP literals.
Publish only reachable UDP candidates that are explicitly chosen for advertisement.
The bind address and the advertised address serve different purposes. A host can
bind to all interfaces, but it cannot advertise wildcard `0.0.0.0` or `::`.
The deployment or provider must establish reachability through NAT or a relay;
a passing registration test does not prove that clients can reach the address.

Prepare the host's DTLS certificate and key before publishing its profile. Keep
the private key local. All peers using that profile use that certificate, so
clients see the fingerprint the provider advertised. The host may use a new
certificate for a later endpoint incarnation after publishing its new fingerprint.
A permanent certificate shared across a fleet is neither required nor advised.

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

`installedKeyIds` contains at most eight distinct four-character uppercase
alphanumeric IDs, ordered with the active epoch last. Save and install every
listed key before sending it. The last ID must be the provider's current or
pending epoch. Publish a matching profile when changing the active epoch.

To provision a replacement, include a random `keyRequestId` of 16–128 URL-safe
characters, saved before sending. The response includes
`keyRequest: {id,keyId}` and, on first delivery only, `ticketKey: {keyId,secret}`.
A key secret has 32–256 UTF-8 characters; optional `notBefore` and `retireAfter`
are epoch milliseconds. An idempotent retry cannot mint another key or return
the secret again. If its delivery was lost, use a fresh request ID to provision
a replacement. A provider may retire an unacknowledged, superseded pending key.

Install the key atomically, then immediately publish its profile and acknowledge
it in heartbeat. Replies include `retirements: [{keyId,retireAfter}]` for older
reported epochs. Repeated replies preserve the original deadlines; they cannot
extend key life. Providers must allow outstanding tokens their defined overlap
window. Reject tokens before activation or after retirement and erase retired
material. Key rotation never extends token expiry.

### Readiness, lease and schedule

The reply includes `receivedAt` (ISO8601), `hostProfileRevision`, `activeKeyId`,
`leaseGeneration`, `readiness: {routable,reasons}`, and:

```text
checkIn: {version:1,afterMillis,nextCheckInAt,leaseExpiresAt,minUpdateIntervalMillis}
```

Schedule timestamps are epoch milliseconds. `nextCheckInAt` precedes lease
expiry. `checkInVersion: 1` requests scheduling; while an initial usable profile
is unavailable the provider can omit `checkIn` and use its discovery cadence
and `staleAfter` ISO8601 deadline. A draining/closed host is never routable.

Readiness is a current provider observation; only the recorded `checkIn` or
`staleAfter` grants a lease. Request replay returns that original grant. Hosts
use monotonic timers, count network time against the interval, and publish
changed activity/status earlier subject to the returned rate limit. Restart
immediately publishes fresh state and discards the prior schedule. Existing
sessions survive a control-plane outage.

## `outcomes`

Request: `{events:[{ticketId,stage,occurredAt,reason?}]}` with at most 100 events.
`occurredAt` is ISO8601; `reason` is a bounded code of at most 128 characters.
The ticket ID derives from the authenticated admission carrier, never from an
unauthenticated packet. The provider scopes correlation to the signed instance.
No provider-specific routing decision ID is required.

Required stages are `ticket.data_channels_open` and `ticket.failed` for observed
transport attempts, plus `ticket.game_joined`/`ticket.game_rejected` when
`gameOutcomes` is `available`. Optional diagnostic stages are `ticket.ice_seen`,
`ticket.ice_connected`, `ticket.dtls_connected` and `ticket.sctp_connected`.
Success and failure describe the observed boundary, not an inferred later stage.

A successful response acknowledges the whole batch. Repeated observations must
be deduplicated by instance, ticket, stage, occurrence time and reason, including
when a retry uses a new request ID. Queue and persist redacted reports with a
finite bound; the reference client retains at most 1000 pending entries and
flushes at most 100 per tick independently of idle heartbeat timing. Backpressure
must not block native admission or lease renewal. Neither absent reports nor an
unreachable host proves a particular client's outcome. Never send SDP, private
keys, player identity or game payloads.

## Stateless admission carrier

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
| 36 | 2 | Client SCTP port, 1–65535 |
| 38 | 4 | Client maximum message size, 1–262144 |
| 42 | 16 | Opaque caller-context hash, no account-specific interpretation |
| 58 | 8 | NetherNet network ID, unsigned 64-bit |
| 66 | 1 | Client ICE password length, 22–91 |
| 67 | N | Client ICE password in ICE base64 alphabet |

The host's local ICE password is the unpadded standard base64 encoding of the
first 24 bytes of
`HMAC-SHA256(secret, "nxs-stateless-ice-v1" || NUL || audience || NUL || answerUfrag)`.
The ticket correlation ID is the first 16 bytes of SHA-256 of the ASCII answer
ufrag, encoded as lowercase hex.

### Validate the first packet

A token can be valid for at most 120 seconds. Before assigning the UDP tuple to a
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

#### Reference implementation

The supplied Network implementation uses a 60-second token limit. libjuice
retains the first STUN packet and sends parsed metadata to Java for asynchronous
token validation. libdatachannel verifies STUN integrity before creating the
peer, outside the UDP receive lock. It then processes the retained request after
the application has installed its callbacks. Established transport packets stay
native, and capacity remains reserved until native teardown finishes.

Other implementations may meet the requirements above using different languages,
threading models, and transport libraries.

## Optional extensions

Providers can add optional application metadata without making it part of NXS.
For example, a product could supply an account-claim link. NXS does not define
what claiming an account means or require other providers to implement it.

`extensions` is an object with at most 16 reverse-DNS namespace keys, such as
`com.example.feature`, and at most 16384 bytes of encoded UTF-8 JSON. Keys use
lowercase domain-style labels and have at most 128 characters. Each value is
`{version:positiveInteger,critical:boolean,data:object}`.

Pass through or ignore unknown optional extensions; never execute them
automatically. Reject unsupported critical extensions before sending credentials
or publishing readiness. An optional extension cannot change the core protocol rules.
TLS and request signatures still authenticate bodies and operation paths.

An extension can advertise URLs in `data.operations`. An application can request
one of these operations only after validating the namespace, version, and meaning.
The generic transport still requires the same provider origin and signs the
exact path.

## Conformance

Run `node docs/external-signalling/fixtures.mjs` for independent signature and
admission fixtures. The Java tests consume the same schema/fixtures and exercise
an independent provider with no product accounts. Native tests separately cover
local admission and real UDP/ICE/DTLS/SCTP. Report stock-client gameplay separately
from these checks.
