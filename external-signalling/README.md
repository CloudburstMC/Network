# NetherNet External Signalling

Java 21 client for the open [NXS v1 specification](../docs/external-signalling/README.md).
Published coordinates follow Cloudburst conventions: `org.cloudburstmc.netty:netty-external-signalling`.
The independent provider and fixtures require no product account or proprietary control plane.

```sh
./gradlew --max-workers=2 :external-signalling:test :transport-nethernet:test
./gradlew --max-workers=2 :external-signalling:providerStub
node docs/external-signalling/fixtures.mjs
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
`EndpointAddress` provides numeric parsing and public/private/special-purpose
classification for adapters. Publication does not test network reachability or
configure port forwarding. An IPv6 wildcard listener accepts IPv4 and IPv6 with the
pinned native stack; a concrete IPv6 bind does not imply IPv4 coverage.

All endpoints share one admission incarnation. The first authenticated source tuple
owns its ticket, including when several address families are advertised; subsequent
tuples cannot use that ticket to create another peer. This does not introduce path
migration after admission. Java validates the NXS1 token from incoming
ICE metadata; native code verifies STUN integrity before creating a peer. The
first request stays native during asynchronous validation, and acceptance does
not depend on a client retry. Established transport packets stay native. The optional native test task is
`:external-signalling:nativeAdmissionTest`; native packaging must match the immutable JNI
revision in `native-dependencies.properties`. Never combine new headers with older native
binaries. Native tests prove transport conformance, not stock-client gameplay.
