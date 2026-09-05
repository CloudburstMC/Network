# Contribution provenance and proposed submission slices

This maintained proposal targets CloudburstMC/Network `develop` at
`508ed83c8a1fe1ce4287ad6d192c9d38bb0d2ffd`. `upstream` mirrors that exact baseline;
`nxs-dev` is the integrated development proposal. An aggregate internal draft
against `upstream` is for review and must not be merged into the mirror.

| Slice | Origin and retained source | Reconstruction commit | Dependencies |
| --- | --- | --- | --- |
| N1: NetherNet prerequisites | Kas-tle/NetworkCompatible transport subtree at `9f3c0b7e72fb6f8934a7d36a518c74afe02c8a6d`, including introduction `77489fb0e4b86f5ffffef69230c81c7f4a857fa8` and SDP fixes `dcea2bab06ab35a53912b686a7dc59e0eb5bf62a` | `04ac4abb4d4e9d6f369c38de78ef2a40952ddecc` | Cloudburst baseline; original historical backend is replaced by N2 |
| N2: HTTP signalling and native backend | rtm516/NetworkCompatible transport subtree at `8d1c989ee6fb5eb7a28a9130573d30634f39cba4`; HTTP introduction `37f82650d6268a3470459d758ad89c3cc2026f2e`, backend migration `f4ba1e7b826e932e12974d5a6f329647eab6ee05` | `b96f3094ad1139848cd94f1984bf4122b5136348` | N1 and libdatachannel Java binding |
| N3: Open NXS contract and lifecycle client | teamziax/NetworkCompatible provider work through `01df7a0b4d3629306a72d78955f9b2645c174b1f`, adapted into a new negotiated specification and neutral client | `fc7dcc3534d34a0268e9a26ead49a48794b32c0b` | N2 for integrated transport; specification independently reviewable |
| N4: Stateless native admission | teamziax admission/teardown/first-datagram work at the same source tip, plus separate advertised-address support and downstream primitive probe | `9bf6674c43e6d59eba2805283e94b662d4b6c0ee` | N3 and the maintained mux/lifecycle/identity/ICE binding chain |
| N5: Integration and maintained dependency packaging | Exact dependency manifest, neutral bootstrap, conformance workflow, fixture handoff and final validation | Following integration commit(s) in this proposal | N1–N4; cross-repository pins in `native-dependencies.properties` |

Cloudburst is the foundation, not newly authored work in this proposal. The two
histories share ancestor `1e26b20d9b9e13726edac99ee47711c958196fa2`; the source
proposal has 100 commits absent from current Cloudburst, while Cloudburst has 36
commits absent from that source. Only the required transport subtree was imported.
Cloudburst's production `transport-raknet/src/main`, `codec-query` and `codec-rcon`
trees are preserved exactly. Two existing split-helper test constructor calls are
adapted to the upstream `(partId, expectedLength)` API so the baseline suite can
compile; this is the same minimal test repair previously recorded in
`ziaxzulu/Network` commit `2910ce99fcf96075da6b74d84c6d614bc2e3b207`.
Its throttle fixture also limits clients to one attempt, as recorded in
`aa3055bd4c6b3880913cd74911cba50ec4f1cea3`, so retries beyond the test's one-second
window do not invalidate its immediate-throttle assertion. No production RakNet
changes accompany these three test-line repairs.
The fork's removed codecs, unrelated RakNet compatibility behavior,
publishing configuration and historical merge topology were not transplanted.

N1 removes the source module's additional publishing plugin and uses Cloudburst's
existing Gradle and Maven conventions. N2 adapts the inherited TLS detection call
to Cloudburst's existing Netty API; HTTP behaviour still rejects encrypted traffic
on the plaintext handler. It does not upgrade unrelated RakNet dependencies.
Original contributor names and file notices remain in the source and commit trailers.

N3 replaces product-labelled packages, signature headers, registration/profile IDs
and domains with explicitly negotiated NXS v1 values. Canonical bytes are versioned;
the change is not a relabelling of old signatures. Durable same-instance recovery
preserves IDs and signing material through explicit recovery/activation. The client
implements no individual join control command and treats extension metadata as
transient, bounded opaque data. An optional application adapter owns its own
account actions. The independent provider exercises the full host lifecycle and
four authorization/placement journeys without such an adapter.

N4 authenticates raw STUN before promotion/allocation, bounds replay and capacity,
keeps creation outside mux locks, and retains capacity until native teardown.
The admission token binds the client fingerprint, ICE credentials, endpoint
incarnation, expiry and opaque caller context. Native integration tests cover
both data channels, first-datagram response, invalid ingress, replay, key retirement,
concurrency and shutdown. These tests do not establish stock-client gameplay.
The moved `AdmissionPrimitiveProbe` preserves its original MPL-2.0 file license;
the license text is in `LICENSES/MPL-2.0.txt`.

Native patch acceptance is not required to build this proposal: the manifest pins
maintained forks. Fork packaging is intentionally separated from generic API
contributions. Native `master` already provides certificate and ICE-credential C
APIs; the Java binding uses those upstream APIs rather than duplicate entrypoints.
The native artifact provenance records source revisions, platform, ABI assumptions
and SHA-256 hashes. A new native chain must be rebuilt together with its JNI headers.

Before eventual external submission, refresh the mirror, remove already accepted
slices, review current contribution rules, and describe dependencies explicitly.
NetworkCompatible belongs to a different GitHub fork network from Cloudburst;
this local Git ancestry reconstruction does not change repository ownership or
create an external PR. No repository transfer is part of this maintained proposal.
