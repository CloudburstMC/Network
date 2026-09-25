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

`ProviderClient` diagnostics use `Consumer<ProviderDiagnostic>`; integrations must route
its `DEBUG`, `INFO` and `WARN` levels to the matching logger methods and hide debug by
default. This replaces the string-only callback, so update the library and its
consumer together. Each failing background operation warns once, sends retries to
debug, and reports recovery at info only after that operation succeeds. HTTP fallback
does not mark the live WebSocket connection as recovered. Player assisted-join failures
remain individual warnings; background diagnostic attempts remain debug observations
until their completed regional results are reported by the host integration.

`ProtocolExtensions` carries bounded optional metadata. Applications explicitly interpret
known namespaces and invoke only their advertised same-origin operations. The core never
performs product account/claim actions. Assisted joins require explicit host opt-in.

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

`openMaintained` can bind before discovering an endpoint and maintains reflexive candidates
on the gameplay UDP socket. Candidate snapshots retain their original expiry and ownership
through profile publication. A changed mapping withdraws the old candidate; existing peers,
keys and the native identity survive. Fresh mappings are published immediately.
Completed connectivity checks can withhold failed public endpoints from player offers while preserving recovery probes
and STUN warming. Configured endpoints suppress automatic discovery; see the
[publication policy](../docs/external-signaling/wire-reference.md#optional-connectivity-observation).

`captureHostProfile` returns immutable profile bytes and a nonblocking ownership guard.
The client retains that guard through persistence and the HTTPS or WebSocket send queue,
then rechecks it before processing the response. Replacing endpoint material invalidates old
snapshots even when an address changes back to its previous value.

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

Applications report `Health(acceptingPlayers, capacity, build, playerCount)`.
For listing metadata, use `ServerStatus(name, level, maxPlayers, gameType)` when
an actual player count is available, or supply an explicit listing count with
`ServerStatus(name, level, players, maxPlayers, gameType)`. The provider owns the
advertised game protocol and version. Local `drain()` and `close()` manage the
listener; provider responses do not command it.

### Assisted joins and diagnostics

`ProviderRuntimeConfiguration.Settings` and `ProviderClient.Configuration` accept
`assistedJoins`, defaulting to `false`. It requires `ControlTransport.AUTO` and
`NativeProviderTransport`, reusing the gameplay listener, identity, capacity and
CPK verifier. Wrappers must delegate `supportsAssistedJoins()`,
`assistedFallbackReadyFamilies()` and `assistedJoin(join, requireCurrent)` with the
original guard and future. Assistance uses per-join discovery instead of background
warming; connectivity feedback never changes the configured choice. The
[WebSocket reference](../docs/external-signaling/control-v1.md) defines the exchange.

Diagnostics are independently opt-in through `diagnosticAdmission`. After a
successful heartbeat, `ProviderClient` configures `DiagnosticHostPolicy` through
`NativeProviderTransport.configureDiagnostics`; `disableDiagnostics` revokes it.
The policy bounds trusted context, up to eight keys, 32 concrete endpoint/revision
targets and two assisted families with original deadlines. Lower-level callers use
`NativeAdmissionServerChannel.enableDiagnostics(policy)`.

`NativeDiagnosticProbeAttempt` performs one caller-authorized check on a bounded
worker. Its `Signaling.exchange(Request)` callback supplies SDP over authenticated provider
HTTPS. The runner checks the authorized host pin and destination before starting transport and retains capacity until
native termination. Wire formats, PING/PONG and cleanup requirements are in the
[diagnostic reference](../docs/external-signaling/diagnostic-v1.md).
