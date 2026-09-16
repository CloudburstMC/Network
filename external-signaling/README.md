# NetherNet External Signaling

Java 17 client for the open [NXS v1 specification](../docs/external-signaling/README.md).
Published coordinates follow Cloudburst conventions: `org.cloudburstmc.netty:netty-external-signaling`.
The independent provider and fixtures require no product account or proprietary control plane.

```sh
./gradlew --max-workers=2 :external-signaling:test :transport-nethernet:test
./gradlew --max-workers=2 :external-signaling:providerStub
node docs/external-signaling/fixtures.mjs
```

`ProviderClient` supports new-service registration by advertised anonymous proof of work
or bearer token, token-authorized instance attachment, durable recovery, generation-fenced
activation, status/profile publication, scheduled heartbeats, key rotation, drain, and
asynchronous outcomes. Tokens are enrollment-only and excluded from durable state/logs.
One instance owns one private state directory; restarts preserve that directory.
A pool attachment may have no public address. Public endpoints can change without
changing the runtime identity. `Health` accepts an optional `PlayerCount` with actual
connected players and sample time, separate from the public `ServerStatus` supplier.

`ProtocolExtensions` carries bounded optional metadata. Applications explicitly interpret
known namespaces and invoke only their advertised same-origin operations. The core never
performs product account/claim actions or stages individual joins from provider control.

`NativeProviderTransport` publishes its UDP endpoints and certificate fingerprint
before accepting clients. Its supplier overload of `open` refreshes a deduplicated
snapshot of 1–32 numeric IP/port pairs at each background profile publication. Adapters
can provide all suitable addresses of a wildcard listener plus operator-configured
forwarding endpoints; the original single-endpoint overload remains available.
`EndpointAddress`, in the NetherNet transport module, provides numeric parsing and
public/private/loopback/unusable classification for adapters, and is the same
classifier candidate filtering uses. Publication does not test network reachability or
configure port forwarding. An IPv6 wildcard listener accepts IPv4 and IPv6 with the
pinned native stack; a concrete IPv6 bind does not imply IPv4 coverage.

Controlled integrations can explicitly call `NativeProviderTransport.openControlledVersion2`
with a `NativeCandidateSnapshot`, then call `replaceCandidates` as endpoint material changes.
This is a separate API opt-in; the existing factory and all unversioned overloads retain
1–32 candidates. A v2 profile includes `version: 2` and allows 0–32 candidates. Empty startup
binds the gameplay listener before discovery; withdrawing the final candidate preserves its
incarnation, certificate, admission keys and established peers. The controlled application
reports `acceptingPlayers: false` for an empty advertisement while truthfully acknowledging
its native serving state. This withdraws new endpoint discovery; it does not revoke already
issued tickets or prove network reachability.

Candidate snapshots own resolved numeric UDP endpoints, derive IPv4/IPv6 family, and carry
`HOST` or `SRFLX` type. Canonical ordering and the material revision ignore input order and
duplicates. Unchanged replacements preserve ownership; A → B → A creates new ownership even
though A has the same deterministic material revision. `captureHostProfile` returns immutable
profile bytes and a nonblocking ownership guard, retained through asynchronous publication,
native staging, durable storage and readiness confirmation. The synchronization carrier retains
that guard through its own journal save and signing, checks it immediately before actual
HTTPS/WebSocket sends, and guards response delivery and signed readiness confirmation. A
failed guard preserves any durable original intent for strong reconciliation. Controlled WebSocket
links must implement `sendText(wire, requireCurrent)`: lifecycle, readiness and authority messages
retain their original guard through the bounded transport queue and check it immediately before
the actual JDK send. A failed queued guard aborts that physical link and fails its remaining queue;
it does not skip a frame or change the retained intent. Guards run outside the transport lock and
transport ownership is checked again after any reentrant callback. Generic unguarded links cannot
silently substitute for this handoff. Legacy adapters cannot return versioned profiles through the
default capture method without providing that guard.

`SRFLX` is representable for later discovery work but is rejected by both v2 open and
replacement publication until a bounded candidate lease protocol exists. This API does not
start STUN, open another UDP socket or enable automatic mapped-candidate publication. Typed
factory/platform hint integration and same-mux warm STUN remain separate work.

All endpoints share one admission incarnation. The first authenticated source tuple
owns its ticket, including when several address families are advertised; subsequent
tuples cannot use that ticket to create another peer. This does not introduce path
migration after admission. Java validates the NXS1 token from incoming
ICE metadata; native code verifies STUN integrity before creating a peer. The
first request stays native during asynchronous validation, and acceptance does
not depend on a client retry. Established transport packets stay native. The optional native test task is
`:external-signaling:nativeAdmissionTest`, which runs against the published binding
selected by the `libdatachannel` version in `gradle/libs.versions.toml`. Its native
classifier resolves at that same version, so headers and native binaries cannot skew
apart. Native tests prove transport conformance, not stock-client gameplay.

### Assisted player joins

The existing `ProviderRuntimeConfiguration.Settings` and `ProviderClient.Configuration`
accept a final `assistedJoins` boolean, defaulting to `false` in existing constructors.
It requires `ControlTransport.AUTO` and the existing `NativeProviderTransport`; it
uses that gameplay listener, DTLS identity, admission capacity and CPK verifier.
Wrappers must delegate `supportsAssistedJoins()`, `assistedFallbackReadyFamilies()`
and `assistedJoin(join, requireCurrent)` without replacing the original guard/future.
No second registration, native listener or command channel is added.

Permission alone keeps the ordinary socket. Direct public endpoints remain preferred;
unknown or expired checks are not failures. Enable the existing diagnostic-admission
option to obtain public-endpoint failure evidence. With maintained candidates enabled,
a failed direct check gives the existing STUN monitor its attempt before assistance;
an empty profile while that monitor is pending cannot activate assistance. Finished
private-only/empty discovery can activate it without an impossible public-target probe.
Fresh positive public checks restore direct preference. Active fallback advertises
`per_join` in the existing signed connectivity extension and upgrades with
`nxs-assisted: 1`; the provider may reject this optional capability if not configured.

An `assisted-join` carries the authenticated full offer, player CPK and identity,
original host context, fixed expiry and host ICE credentials. The transport creates
the peer before any inbound client packet and sends outbound ICE on the same gameplay
UDP mux. Returning STUN attaches to that already-owned peer. The actual native answer
is returned only while the original native/profile/key/deadline guard remains live.
Pending joins are bounded at 32 and preserve their original expiry. Established peers
use the existing gameplay child and two data channels; no game-login bypass exists.
IPv4/IPv6 tests cover ICE/DTLS/SCTP, CPK association and two-way reliable/unreliable
bytes, including a client with no host candidates. They do not establish universal
NAT traversal or a stock game login.
