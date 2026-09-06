# NetherNet External Signalling v1

NetherNet External Signalling (NXS) lets a NetherNet server use a signalling
provider chosen by its operator. The server registers with the provider and
publishes the information clients need to connect. Each client then brings a
short-lived token that the server can check locally.

For example, a server can publish its address and certificate fingerprint when
it starts. Later, the provider gives a client those details and a token. The
server checks the token in the client's first packet. It does not need to ask
the provider whether to accept that connection.

This is an experimental open specification, identified by
`urn:nethernet:external-signalling:v1`. This document, the
[schema](nxs-v1.schema.json), and the test fixtures define one versioned protocol.
They use this repository's Apache-2.0 license.

## How a connection works

1. The host registers with the provider and proves that it owns its signing key.
2. The host publishes its address, certificate fingerprint, and connection
   settings. It sends heartbeats to renew its registration lease.
3. The provider uses that information to give a client a connection answer and
   an admission token.
4. The client sends the token in its first STUN packet to the host.
5. The host checks the packet and token, then establishes the connection. The
   client's certificate must match the fingerprint in the token.
6. The host reports connection and game outcomes to the provider afterwards.

Here, **stateless admission** means that the host needs no saved state for that
client before its first packet arrives. The host still keeps its own keys,
registration, and active connections. A conforming implementation MUST NOT
require a push, poll, shared lookup, offer fetch, or pre-staged client state to
admit a client. Reports about the connection or game outcome never determine
whether the host can accept that first packet.

NXS covers communication between the host and provider. Account systems,
credential issuance, billing, Microsoft login, DNS management, and the policy
for choosing a host are outside this specification.

### Terms

| Term | Meaning here |
| --- | --- |
| Host or instance | One running NetherNet server. Its instance ID survives a restart. |
| Provider | The service that registers hosts and gives clients connection information. |
| Service | A provider-assigned registration that can contain one or more instances. |
| Lease | The period for which an instance is eligible to receive new connections. Heartbeats renew it. |
| Generation | A counter advanced on activation. Requests from earlier generations are rejected. |
| Host profile | The address, certificate fingerprint, and other settings clients need to connect. |
| Incarnation | A random ID for one bound UDP endpoint. A newly bound endpoint gets a new ID. |
| Admission | Checking a client's token and first packet before creating its native peer. |
| Key epoch | One version of an admission key, identified by `keyId`. |

ICE checks network reachability using STUN packets. DTLS authenticates and
encrypts the connection. SCTP carries the data channels over that connection.
A UDP tuple identifies a packet's source address and port at a host endpoint.

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

The [operation table](#operations) defines the operation names. Clients get their
URLs from discovery. `/v1/nxs/<operation>` is a recommended path, but providers
can use other paths.

### Authorization and limits

`authorization` contains `header: "Authorization"` and a `schemes` array. Each
entry has a `scheme` and its supported `modes`:

| Scheme | Allowed modes |
| --- | --- |
| `anonymous-proof-of-work` | `new-service` |
| `bearer-token` | `new-service`, `attach-instance`, or both |

A provider need only advertise the schemes it accepts. It decides how tokens
are issued, what they authorize, and whether they can be reused. Every flow also
requires proof that the instance owns its signing key.

| Limit | v1 constraint |
| --- | --- |
| `maxBodyBytes` | At most 65536 |
| `clockSkewMs` | At most 60000 |
| `heartbeatIntervalMs` | 1000–30000 |
| `leaseMs` | Advertised lease duration |
| `maxControlPage` | At most 100 |

`checkInVersion: 1` enables the provider to set the next check-in time in its
response. A provider MUST advertise every limit it enforces, reject oversized
bodies, and return errors as `{"code":"lowercase_machine_code"}` with an
appropriate HTTP failure status. Clients limit response size before parsing.

On a transient transport failure or HTTP 429, 502, 503, or 504, the supplied
client makes at most three attempts in total. Retries use exponential delays
with jitter and an upper bound. A `Retry-After` value over ten seconds returns
a retry-later result. Retrying never extends a lease or challenge expiry.

## Registration and persistent identity

### Save the instance key

Generate a fresh P-384 machine signing key for each logical instance. Save it
before requesting a challenge. A restart reuses that instance's saved state;
live replicas cannot share a key or state directory. Images and templates MUST
contain neither machine identity nor DTLS private keys.

Clients lock their state directory and write private state atomically with
owner-only permissions. Sync both files and directories to durable storage.
If saving state fails, stop advertising healthy readiness.

### Request a challenge

The request contains `protocol`, `mode`, `profile`, `publicKeyJwk`, explicit
`authorization: {scheme}`, and optional `label` and `placement`.

Send a bearer credential only to the challenge operation, in
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

### Complete registration

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
readiness`, plus optional one-time `ticketKey` and `extensions`. Save the IDs and
key material before activation. Remove secrets from registration results exposed
to applications and from diagnostic output.

## Signed lifecycle and host profile

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

Activation increments the generation and resets the sequence. The provider then
rejects requests from the old process. Signed state-changing operations must use
the active profile. To change profiles, recover the registration and send a
signed activation request.

### Operations

| Operation | Request | Required result or behavior |
| --- | --- | --- |
| `challenges` | POST challenge request, optional bearer | Challenge bound to the registration request |
| `complete` | POST completion proof | New or recovered registration; return secrets only once |
| `recover` | POST `{registrationId,protocol,profile}` | Challenge for the current or pending machine key; preserve assigned IDs |
| `activate` | Signed POST `{profile}` | Increment `leaseGeneration`, return `leaseDeadline`, and reset stale host readiness |
| `readiness` | Signed GET | Whether the host can receive new connections, with reasons and optional extension metadata |
| `host-profile` | Signed POST profile below | A `revision` cannot change once published; updates use a higher revision. Reject unusable candidates or keys |
| `heartbeat` | Signed POST health/status below | Receipt time, renewed lease, and optional check-in schedule |
| `control` | Signed GET, optional cursor | Limited `commands` page, optional `cursor`, and `serverTime` |
| `control/ack` | Signed POST `{cursor}` | Acknowledge only lifecycle commands that have finished |
| `ticket-keys` | Signed POST `{}` | One-time `{ticketKey:{keyId,secret,...}}` for a new key epoch |
| `ticket-keys/ack` | Signed POST `{keyId}` | Confirm the key is installed before using its epoch for new connections |
| `ticket-events` / `events` | Signed POST `{events:[...]}` | Limited batches of asynchronous observations; retries do not duplicate them |
| `rotate` | Signed POST `{publicKeyJwk,proof}` | New `keyId` after proof of ownership of the replacement key |
| `retire` | Signed POST `{keyId}` | Retire the previous machine signing key |
| `drain` | Signed POST `{}` | Stop directing and accepting new connections; preserve existing sessions |
| `deregister` | Signed POST `{}` | Stop directing connections to the instance and end its registration |

### Rotate a machine key

The rotation proof bytes are `[protocol,"rotate",audience,instanceId,oldKeyId,
newThumbprint,generation,idempotencyKey]`. Save the replacement private key before
requesting rotation. Save the result before retiring the old key. After an
interrupted rotation, recovery can use the provider's returned key thumbprint
to identify which key is current.

### Publish the host profile

`host-profile` contains `candidates`, `dtlsFingerprint`, `credentialKeyId`,
`sctpPort`, `maxMessageSize`, and `statelessAdmission: {capability,incarnation}`.
Generate a fresh random 16-byte `incarnation`, encoded as lowercase hex, for
each bound native endpoint. The fingerprint is `sha-256 ` followed by the
certificate's digest bytes in colon-separated uppercase hex.

Each candidate contains `foundation,component,protocol,priority,address,port,type`.
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

### Install admission keys

Each key has a `keyId` of four uppercase alphanumeric characters, a `secret` of
32–256 UTF-8 characters, and optional `notBefore` and `retireAfter` times in epoch
milliseconds. Install at most eight epochs atomically and acknowledge them. Then
publish a profile that uses an active, installed epoch.

Reject tokens before the key's activation time or after its retirement time.
Erase retired key material. Rotating keys does not extend token expiry.

### Send heartbeats and report readiness

A heartbeat contains `healthy,capacity,load,protocolVersion,build,hostProfileRevision,
clockUnixMillis` and optional `region,serverStatus,checkInVersion`. Capacity and
load describe routing capacity; they are independent of the advertised player
and maximum-player counts. Status contains
`name,protocol,version,level,players,maxPlayers,gameType`. A failed publication
does not refresh the timestamp of previously published status.

A host is ready to receive connections only when it has a current identity and
generation, a live lease, a usable fresh host profile, and acknowledged installed
keys. Optional product extensions cannot affect this core readiness check.

With check-in v1, the heartbeat response contains ISO8601 `receivedAt` and:

```text
checkIn: {version:1,afterMillis,nextCheckInAt,leaseExpiresAt,minUpdateIntervalMillis,
          controlPollAfterMillis}
```

Absolute times in `checkIn` are epoch milliseconds. `nextCheckInAt` is before
lease expiry. Hosts use monotonic clocks for scheduling and include network time
in the interval. Changed activity or status can trigger an earlier heartbeat,
subject to the rate limit. On restart, publish immediately and discard the old
schedule. If the provider is unavailable, routing leases expire; existing sessions
are not closed solely because of that outage.

### Handle controls and report outcomes

This profile supports `noop,drain,suspend,revoke`. Do not silently acknowledge an
unknown control. An unknown command can prevent advancing the page cursor, but
later known lifecycle commands still need processing. `join-admission` is not a
v1 control; accepting a client never waits for that command.

Event batches contain at most 100 entries. Keep only redacted correlation data,
stage or type, timestamp, and reason fields with size limits. Never send SDP,
private keys, player identity, or game payloads as telemetry. A working transport
connection is a separate outcome from `ticket.game_joined` (ready to play) or
`ticket.game_rejected`.

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

A token can be valid for at most 120 seconds. The supplied implementation uses
60 seconds. Before assigning the UDP tuple to a peer or creating a native peer,
the host checks expiry, field bounds, GCM authentication, client binding, and the
raw STUN MESSAGE-INTEGRITY. The DTLS handshake MUST then verify the client
fingerprint from the token.

Only a retransmission of the identical token from the same UDP tuple can reuse
a reservation. Reject the same token from another tuple. Also reject a conflicting
admission on an occupied tuple.

Limit the number of sessions, pending handshakes, replay-cache entries, callbacks,
and queued datagrams. Create peers outside the UDP mux callback lock. After
registering the native peer, deliver or replay the authenticated first datagram
so that its STUN request receives a response. Release admission capacity only
after native teardown has actually finished.

## Optional extensions and compatibility

### Extensions

Providers can add optional application metadata without making it part of NXS.
For example, a product could supply an account-claim link. NXS does not define
what claiming an account means or require other providers to implement it.

`extensions` is an object with at most 16 reverse-DNS namespace keys, such as
`com.example.feature`, and at most 16384 bytes of encoded UTF-8 JSON. Keys use
lowercase domain-style labels and have at most 128 characters. Each value is
`{version:positiveInteger,critical:boolean,data:object}`.

Pass through or ignore unknown optional extensions; never execute them
automatically. Reject unsupported critical extensions before sending credentials
or activating. An optional extension cannot change the core protocol rules.
TLS and request signatures still authenticate bodies and operation paths.

An extension can advertise URLs in `data.operations`. An application can request
one of these operations only after validating the namespace, version, and meaning.
The generic transport still requires the same provider origin and signs the
exact path.

### Upgrade and rollback

Recover saved IDs and keys into this profile with
`recover {registrationId,protocol,profile}`, then signed `activate {profile}`.
Verify the same key and origin, preserve IDs and DTLS files, and record the new
profile and generation atomically. Legacy protocol bytes MUST NOT be relabelled
as v1. Providers may keep separately negotiated legacy adapters; the neutral
Java module implements only NXS.

Rollback uses the previous client with explicit recovery and signed profile
activation. Never bypass machine authentication or copy a live state directory.

## Conformance

Run `node docs/external-signalling/fixtures.mjs` to verify the independent
JavaScript signing, encryption, and fixture hashes. `--write` regenerates public
test signatures. The JVM suites load these same files through Gradle resources.

The independent test provider covers registration, signed operations, status,
keys, outcomes, drain, and recovery without a product account system. Native
tests separately check raw STUN admission and DTLS transport.

Report stock-client admission, gameplay, and routing across two hosts separately
from fixture and native tests. Passing those tests does not prove that a stock
client can join and play.
