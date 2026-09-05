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

`ProtocolExtensions` carries bounded optional metadata. Applications explicitly interpret
known namespaces and invoke only their advertised same-origin operations. The core never
performs product account/claim actions or stages individual joins from provider control.

`NativeProviderTransport` publishes the actual bound UDP endpoint and certificate
fingerprint before accepting clients. Its admission validator verifies an NXS1 token and
raw STUN integrity before creating a native peer. The optional native test task is
`:external-signalling:nativeAdmissionTest`; native packaging must match the immutable JNI
revision in `native-dependencies.properties`. Never combine new headers with older native
binaries. Native tests prove transport conformance, not stock-client gameplay.
