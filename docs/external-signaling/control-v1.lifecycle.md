# Immutable control lifecycle intent and receipt — draft

This optional control capability is **not activated**. Its opt-in revision and
separate proof domains must be negotiated before replacing the existing signed
operation envelope. The [control draft](control-v1.md) describes that boundary.
This codec defines immutable operation identity and redacted receipt syntax;
it does not authenticate a caller, allocate a sequence, commit a mutation or
implement a replay journal.

An intent is exactly:

```text
{version:1,audience,operation,instanceId,generation,sequence,idempotencyKey,payloadSha256}
```

Operations are `heartbeat`, `outcomes`, `rotate`, `retire`, `deregister`. Registration
and completion retain their existing bootstrap. Audience, identifiers, canonical
unsigned safe-integer tokens, strict UTF-8, duplicate-field rejection and canonical
unpadded base64url follow the active-frame draft. Generation and sequence are
positive. `payloadSha256` is SHA-256 of the **original operation body bytes**.

The intent digest is canonical unpadded base64url SHA-256 of UTF-8 JSON.stringify:

```text
["nethernet-control-lifecycle-intent-v1",1,audience,operation,instanceId,
 generation,sequence,idempotencyKey,payloadSha256]
```

Transport, current signing key, timestamps and physical connection do not change
that digest. A separate delivery proof must bind it to the currently authorized
writer. The provider checks the same persisted intent before replay and atomically
fences a fresh mutation against current generation/writer/key. A retry may use a
new authorized delivery key, but cannot change the body, sequence or other intent
fields. Authenticated receipt lookup precedes next-sequence rejection so a committed
operation can be reconciled after losing its acknowledgment.

The WS payload is exactly `{intent,body}`. `body` is canonical unpadded base64url
of the same original strict UTF-8 operation bytes. Its ceiling is 45056 bytes;
the decoded wrapper must also fit the active frame's 65536-byte payload ceiling,
and the final frame must fit its total limit. Larger valid operation bodies use
HTTPS with their original bytes and unchanged intent. HTTPS retains its existing
maximum of **65536 body bytes**. This carrier choice does not replace a WS writer;
persistent writer fallback requires a separate controlled writer transition.
Opaque body validation is not operation-schema validation.

A redacted receipt is exactly:

```text
{version:1,intentDigest,operation,instanceId,generation,sequence,idempotencyKey,
 disposition:"committed"|"rejected"|"expired"|"unknown",
 committedAt:timestamp|null,commitRevision:safeInteger|null,code:identifier|null}
```

Only `committed` carries non-null committedAt and commitRevision, and its code
is null. Other dispositions require null commit fields and may carry a bounded
code. These are outcome records, not delivery acknowledgments or authority grants.
An authenticated receiver compares the digest and all repeated intent identity
fields with its pending intent before clearing that journal entry. A historical
receipt cannot reapply a mutation in a later generation.

No secret, operation result, key material, current-state snapshot or arbitrary
extension is permitted inside the receipt. A separately typed current observation
or connectivity report may accompany it, with its own sample/revision/expiry; that
observation is not part of the committed receipt identity. Fresh sensitive material
has a separately authorized first-success delivery path and is never replayed by
this receipt format.

`ControlLifecycleCodec` limits intent/receipt JSON to 4096 bytes and rejects unknown
or duplicate fields, alternate numeric representations, invalid UTF-8 operation
bytes and noncanonical base64url. Independent Node fixtures in
`control-v1.lifecycle.fixtures.json` include Unicode and exact whitespace/body
digest checks. Their body examples test the carrier and digest contract only;
they do not assert existing operation-schema conformance or deployed replay behavior.

All signed audiences and trusted expected origins use the shared strict
[control origin profile](control-v1.origins.md). IDN/xn-- and trailing-dot
providers cannot advertise this optional profile; existing core origin handling
remains available and unchanged. Never normalize input after signing.

The operational HTTPS carrier sends the original operation bytes as the POST body
with `Content-Type: application/json; charset=utf-8`. `Nxs-Control-Proof` carries
canonical unpadded base64url of the original UTF-8 `ControlHttpRequest` JSON,
bounded to 16384 decoded bytes. Upgrade uses the same header name on its separate
GET route with its own bootstrap proof domain. The exact configured path, origin
and original body digest remain authenticated; header data never selects trust.
A successful operational HTTP response is status **200** with the normal result
envelope. An underlying application's 202 does not change that carrier status.
Redirects are never followed and result bodies remain bounded to 65536 bytes.
