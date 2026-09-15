# Draft authority renewal proofs

This optional extension renews short-lived cached control authority without
replacing the fixed session or querying a primary database per socket/frame.
It is staged and is not advertised by provider discovery. The codec does not
perform HTTP/WebSocket I/O, load keys, establish readiness or validate a source
publisher's completeness.

Use separate ES384 signature domains `nethernet-control-authority-request-v1`
and `nethernet-control-authority-response-v1`, with the existing
`nxs-control-es384-v1` authentication scheme and raw 96-byte signatures. Exact
signing arrays and independent public test keys are in the shared fixtures.
Both envelopes have an 8 KiB UTF-8 ceiling. Exact fields, duplicate rejection,
canonical unsigned integer tokens, canonical origin and base64url, sorted
capabilities and sorted permissions are mandatory beyond the JSON schema.

A request binds the exact trusted HTTPS POST target, provider, instance,
generation, current physical writer, selected machine key/revision, negotiated
capabilities and a fresh identifier with at least 128 random bits. HTTPS can
carry a request for a WebSocket writer. Its authority request is independently
authenticated from the selected public key in the current cached source; an
expired *previous* authority proof does not prevent refresh. Responses use the
separate trusted provider-control verification key family. Never discover trust
from an incoming key ID or an embedded public key.

The request digest is SHA-256 of its canonical signing bytes, including delivery
times, path, capabilities and selected key. Freeze all request bytes/deadlines
on retry. This exchange has no durable lifecycle mutation or replay journal;
the client must retain one outstanding request and consume its nonce when it
accepts a result. A retry cannot give that request a new lifetime.

| Deadline | Meaning |
| --- | --- |
| Request/response `expiresAt` | Delivery proof, at most 30 seconds from its `sentAt`; response also fits the original request. |
| Request `authorityNotAfter` | Maximum requested grant, at most `sentAt + 300000` and the client's fixed session expiry. It never grants authority. |
| `sourceExpiresAt` | Raw authenticated checkpoint deadline, capped by that checkpoint verification key's validity. No clock allowance, request limit or response signer limit is added here. |
| `subjectExpiresAt` | Immutable source subject deadline, including its session and selected machine-key retirement bounds. |
| `authorityExpiresAt` | Minimum of raw source, immutable subject, fixed session, requested upper bound and provider-control signing-key deadlines. |

The raw checkpoint interval `sourceExpiresAt - sourceCheckedAt` is at most
300 seconds. The verifier permits at most 30 seconds of explicit future-clock
uncertainty, but never adds it to a delegated deadline. Near machine-key
retirement, a request may ask for a later upper bound; its short delivery must
fit the trusted key/subject lifetime and the response is still capped by that
earlier subject expiry. A source must not derive any deadline from the time an
edge receives or rereads old data.

Pin `sourceId` on the first trusted positive proof for the provider/instance.
Retain that source floor across reconnect and key changes. Resetting a source
ID needs an explicit trusted migration or reenrollment. Revisions, watermarks,
source time and generation cannot regress. At unchanged content revision,
generation, writer/key binding, capabilities, permissions, state summary and subject expiry
must stay identical. Within one generation, desiredRevision cannot regress even
when the source content revision advances. A freshly checked timestamp may extend raw checkpoint
freshness at that revision. At unchanged revision and source time, a known
shorter raw source deadline cannot be extended. The requested/effective grant
deadline is excluded from this floor, so a short request does not permanently
pin a longer valid source window. Scope the floor to its provider and instance.

Every response includes the closed `state` object from its immutable source:
`{desiredRevision, desiredState, appliedBasisSha256}`. Desired revision is a safe
nonnegative integer; state is serving, draining or closed; the digest is canonical
SHA-256 base64url or null. Canonical response signing bytes append these three
values, in that order, after permissions and before sentAt. A null digest is a
valid control authority view with no accepted current application basis. A
non-null digest is application synchronization evidence only when the caller has
actually applied and persisted the matching basis on its current native instance.
Neither case establishes readiness, admission permission or external reachability
by itself. State changes do not renew or reset any source/session deadline.

After cryptographic verification completes, recheck the delivery deadline and
latest retained floor with `requireFreshControlAuthorityDelivery` (Java:
`Verified.requireFreshDelivery`). In the same serialized installation step,
require that the original nonce is still outstanding, the writer and key are
still current, and the provider verification key remains trusted. Consume the
nonce and retain the floor before exposing the new proof. A captured old floor
cannot overwrite a newer proof that completed during asynchronous verification.

After installation, the short delivery deadline no longer controls protected
actions. Use `requireUnexpiredControlAuthority` (Java: `requireUnexpired`) for the
currently installed proof and separately enforce its permissions, current
writer/key and normal frame/attempt bounds. These helpers cannot select the
current proof or establish readiness on behalf of their caller. Initial readiness
still requires state/key synchronization. Renewal never resets frame sequences,
selects another writer/key, or makes a standby socket authoritative.

Only a positive exact-binding response grants authority. Missing, older,
superseded or unavailable cached source data produces unavailable, with no
authoritative disabled/revoked inference. Use bounded refresh backoff that
preserves the socket; cache lag must not enter repeated primary status or
session-CAS recovery. The source service must recheck its captured source and
signing-key tuple after asynchronous work before releasing a proof.

Validation includes independent Node signatures in both directions, matching
Java/TypeScript vectors, wrong route/key/family/request association, delivery
versus installed lifetime, source/generation/permission rollback, request-cap
versus raw-expiry separation, retiring keys, immutable ownership and late
asynchronous completion. These are control checks, not UDP/gameplay evidence.
