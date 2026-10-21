# NetherNet External Signaling

Java 17 client for the open [NXS v1 specification](../docs/external-signaling/README.md).
Published coordinates follow Cloudburst conventions: `org.cloudburstmc.netty:netty-external-signaling`.
The independent provider and fixtures require no product account or proprietary control plane.

```sh
./gradlew --max-workers=2 :external-signaling:test :transport-nethernet:test
./gradlew --max-workers=2 :external-signaling:providerStub
node docs/external-signaling/tests/nxs-v1.fixtures.mjs
```

The Java tests consume the specification's schema and fixtures and exercise the
independent provider. The optional `:external-signaling:nativeAdmissionTest` task
separately covers local admission and real UDP, ICE, DTLS and SCTP, using the
published binding selected by the `libdatachannel` version in
`gradle/libs.versions.toml`. Its native classifier resolves at that same version,
so headers and native binaries cannot skew apart. Native tests prove transport
conformance, not stock-client gameplay.

## Hosting

```java
var settings = ProviderRuntimeConfiguration.Settings.builder(endpoint)
        .token(token)                       // or a file: path; omit to register anonymously
        .advertiseAddresses(addresses)      // empty to derive them from the listener
        .label(label)                       // omit for the host name
        .connectionChecks(true)             // let the provider check reachability from its regions
        .stunDiscovery(true)                // find public addresses through the provider's STUN servers
        .assistedJoins(false)               // let the provider help players connect, over its WebSocket
        .deregisterOnShutdown(ephemeral)    // for state directories that do not persist
        .gameOutcomes(true)                 // if the game reports joins and rejections
        .location(Location.of(location))    // country, city, latitude, longitude; null to let the provider estimate
        .build();
var runtime = ProviderRuntimeConfiguration.resolve(settings, dataDirectory, bindAddress, udpPort, maxPlayers);
var store = new ProviderStateStore(runtime.stateDirectory());
var host = NativeProviderHostFactory.open(bootstrap, runtime).toCompletableFuture().get();
var client = new ProviderClient(runtime.clientConfiguration(), store, host.transport(),
        statusSupplier, healthSupplier, diagnostics);
client.start();
```

Without a configured label, the instance is named after the container's `HOSTNAME`
or the machine's host name. Hosts whose state directory does not outlive the
process, such as Kubernetes pods without a volume, should deregister on shutdown;
otherwise each restart registers a new instance.

`ProviderClient` supports registration where the bearer token, if any, decides between
a new service and attaching to an existing one, durable recovery, generation-fenced
activation, status/profile publication, scheduled heartbeats, key rotation, drain, and
asynchronous outcomes. Tokens are enrollment-only and excluded from durable state/logs.
One instance owns one private state directory; restarts preserve that directory.
An attached instance may have no public address. Public endpoints can change without
changing the runtime identity.

Applications report `Health(acceptingPlayers, capacity, software, connectedPlayers)`.
`connectedPlayers` is a required `ConnectedPlayers(count, sampledAt)` with the
players actually connected to this runtime. A changed count can wake a scheduled
check-in even if the listing is unchanged; a new timestamp alone causes no extra
network traffic. Listing metadata is `ServerStatus(name, level, maxPlayerCount,
gameType)`; the provider lists the connected count and owns the advertised game
protocol and version. It may take them from `bedrockProtocols`, the
`BedrockProtocols(accepted, version)` a host configures: exactly the protocols it
accepts at login, sent with each host profile. Local `drain()` and `close()` manage
the listener; provider responses do not command it.

With `gameOutcomes`, the application calls `reportGameOutcome` once for each admitted
player when the game admits or rejects them. It never blocks and returns false when
the outcome was dropped.

`operatorAction()` returns the provider's current
[operator link](../docs/external-signaling/wire-reference.md#operator-actions), such as
one to claim a new service, requesting a fresh one when the provider offers it.
Show its text and URL to the operator; never open or save the URL.

`ProviderClient` diagnostics use `Consumer<ProviderDiagnostic>`; integrations must route
its `DEBUG`, `INFO` and `WARN` levels to the matching logger methods and hide debug by
default. Each failing background operation warns once, sends retries to
debug, and reports recovery at info only after that operation succeeds. HTTP fallback
does not mark the live WebSocket connection as recovered. Player assisted-join failures
remain individual warnings; background diagnostic attempts remain debug observations
until their completed regional results are reported by the host integration.

`ProviderClient.extensions()` exposes bounded optional provider metadata. Applications explicitly interpret
known namespaces and invoke only their advertised same-origin operations.

Geyser and WaterdogPE name their NXS options after these settings.

### Control transport and retries

`ProviderClient` defaults to `ControlTransport.HTTP`. With `ControlTransport.AUTO`,
which the settings builder selects for assisted joins, it uses the
[WebSocket transport](../docs/external-signaling/control-v1.md) when
discovery advertises `dev.opencollab.nxs.websocket` and falls back to HTTPS for
ordinary operations; registration and recovery always use HTTPS. The same
lifecycle signs and handles ordinary operations over either carrier, and
`ProviderClient` serializes them. The WebSocket adapter queues at most four
transport sends.

On a transient transport failure or HTTP 429, 502, 503 or 504, the client makes
at most three attempts in total, with capped exponential delays and jitter. A
`Retry-After` value over ten seconds returns a retry-later result. Outcome uploads
use one attempt with a three-second timeout and a ten-second failure backoff so
they cannot starve heartbeat renewal. The outcome queue retains at most 1000
pending entries and flushes at most 100 per tick, independently of idle heartbeat
timing.

### Endpoint publication

`NativeProviderTransport` publishes its UDP endpoints and certificate fingerprint
before accepting clients. Its supplier overload of `open` refreshes a deduplicated
snapshot of 1 to 32 numeric IP/port pairs at each background profile publication.
Adapters can provide all suitable addresses of a wildcard listener plus
operator-configured forwarding endpoints.
`EndpointAddress`, in the NetherNet transport module, provides numeric parsing and
public/private/loopback/unusable classification for adapters, and is the same
classifier candidate filtering uses. Publication does not test network reachability or
configure port forwarding. An IPv6 wildcard listener accepts IPv4 and IPv6 with the
pinned native stack; a concrete IPv6 bind does not imply IPv4 coverage.

`openMaintained` can bind before discovering an endpoint and maintains reflexive candidates
on the gameplay UDP socket. STUN servers are resolved off the native event loop.
Candidate snapshots retain their original expiry and ownership
through profile publication. A changed mapping withdraws the old candidate; existing peers,
keys and the native identity survive. Fresh mappings are published immediately.
Completed connectivity checks can withhold failed public endpoints from player offers while preserving recovery probes
and STUN warming. Configured endpoints suppress automatic discovery; see the
[publication policy](../docs/external-signaling/connectivity-v1.md).

`captureHostProfile` returns immutable profile bytes and a nonblocking ownership guard.
The client retains that guard through persistence and the HTTPS or WebSocket send queue,
then rechecks it before processing the response. Replacing endpoint material invalidates old
snapshots even when an address changes back to its previous value.

### Admission

All endpoints share one admission incarnation. The first authenticated source tuple
owns its ticket, including when several address families are advertised; subsequent
tuples cannot use that ticket to create another peer. This does not introduce path
migration after admission.

The host accepts tokens with up to 120 seconds of remaining lifetime. libjuice
retains the first STUN packet and sends parsed ICE metadata to Java, which
validates the NXS1 token asynchronously. libdatachannel verifies STUN integrity
before creating the peer, outside the UDP receive lock, then processes the
retained request after the application has installed its callbacks, so
acceptance does not depend on a client retry. Established transport packets stay
native, and capacity remains reserved until native teardown finishes.

`TransportIdentityBinding.mismatch(channel, validatedLoginKey)` implements the
[login binding](../docs/external-signaling/wire-reference.md#bind-signaling-identity-to-the-game-login)
for both built-in signaling and NXS. Install its channel evidence before
publishing the child. Consumers MUST invoke it at their validated login boundary;
a library update alone cannot add an application login check. Behind a trusted
forwarding listener, call `TransportIdentityBinding.acceptForwardedIdentity`
after validating the forwarded fields to complete the unused admission lifecycle.

Repeated key snapshots reuse unchanged epochs. `StatelessAdmissionValidator.clear()`
explicitly revokes installed and retained epochs and invalidates pending logins.
Admission principals expose no ticket secret or key handle.

### Assisted joins and diagnostics

Both builders accept `assistedJoins(true)`; it defaults to `false` and requires `NativeProviderTransport`
and the client's `ControlTransport.AUTO`, which the settings builder selects. Assistance reuses the
gameplay listener, identity, capacity and CPK verifier. Wrappers must delegate `supportsAssistedJoins()` and
`assistedJoin(join, requireCurrent)` with the original guard and future. Assistance uses per-join discovery instead of background
warming; connectivity feedback never changes the configured choice. The
[WebSocket reference](../docs/external-signaling/control-v1.md) defines the exchange.
The WebSocket adapter bounds in-flight assisted requests to 32.

Diagnostics are independently opt-in through `diagnosticAdmission`, which the
settings builder calls `connectionChecks`. After a
successful heartbeat, `ProviderClient` configures `DiagnosticHostPolicy` through
`NativeProviderTransport.configureDiagnostics`; `disableDiagnostics` revokes it.
The policy bounds trusted context, up to eight keys, 32 concrete endpoint/revision
targets and two assisted families with original deadlines. Lower-level callers use
`NativeAdmissionServerChannel.enableDiagnostics(policy)`. `NativeDiagnosticHostGate`
bounds active attempts to four, retained attempt IDs to sixteen and queued results
to thirty-two, while respecting shared listener capacity.

`NativeDiagnosticProbeAttempt` performs one caller-authorized check on a bounded
worker. Its `Signaling.exchange(Request)` callback supplies SDP over authenticated provider
HTTPS. The runner checks the authorized host pin and destination before starting transport and retains capacity until
native termination. An explicit test seam permits loopback destinations of the
matching family. Probe workload credentials and scheduling are provider-specific
and stay outside this library. Wire formats, PING/PONG and cleanup requirements are in the
[diagnostic reference](../docs/external-signaling/diagnostic-v1.md).
