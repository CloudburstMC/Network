# Optional control operation results, version 1

This staged codec carries an immutable lifecycle receipt and the operation's
separately delivered JSON response. It does not enable a route, journal, provider
discovery, application state owner, or readiness handshake.

Both normal HTTPS results and the payload of `lifecycle.receipt` use one closed
envelope with exactly `version`, `receipt`, and `body`. `version` is 1. `receipt`
retains every existing lifecycle receipt field and its original intent-digest
domain. `body` is canonical unpadded base64url of the original UTF-8 JSON object
bytes. Whitespace, object order, numeric spelling and Unicode escape spelling are
preserved. The body is never reserialized to recover signing bytes.

The **encoded envelope** is limited to 65,536 UTF-8 bytes on both carriers. Its
decoded body is limited to 45,056 bytes and 16 nested containers. The receipt retains
its 4,096-byte bound. This accounts for base64 expansion within the existing WS
payload limit; HTTP request bodies retain their separate 65,536-byte limit. The
body parser rejects malformed UTF-8/JSON, BOMs, duplicate decoded object keys,
non-object roots and excessive nesting. Application numbers may be negative,
fractional or exponential; the provider adapter validates their field meanings.

A normal committed result always includes its actual JSON object body. Every
noncommitted result uses the exact original body bytes `{}` (`body:"e30"`), and
only its receipt/code is handled by the client. The separately signed bootstrap
intent-receipt status query remains receipt-only. Its local reconciliation result
has an explicitly absent body; an empty successful heartbeat is never invented.

WS authentication covers the entire envelope through the enclosing frame's
payload digest and provider-control signature, current physical writer, ordered
sequence, and fixed source/parent deadline. HTTPS uses ordinary TLS, the exact
configured request and response URI/method, no redirects, bounded strict response
parsing, and the pending request's delivery deadline. The codec alone authenticates
neither carrier. After authenticating the carrier, callers verify the unchanged
receipt-to-intent association before making a committed body available.

The same immutable receipt may accompany changed current observations on a retry.
Its committedAt/commitRevision and any recorded lease/check-in times stay fixed.
No result field renews control source authority or the selected session. Current
readiness, desired state and connectivity observations must not be confused with
a new grant, and connectivity observations do not rewrite the host's health.

A body may contain a one-time admission secret. Do not log it or persist it in the
generic receipt journal or archive. The server releases such a body only after a
final current-writer/key check and exact keyRequest/key ID association. Receipt
replays and status queries never replay that secret. A crash after commit but
before application requires fresh state/key recovery, not another application of
the durable mutation.

Java `ControlResultCodec.Result` owns an immutable encoded body and returns fresh
byte copies; its `toString()` redacts the body. TypeScript results and receipts are
frozen and byte accessors return copies. Application code must still validate and
apply required state/keys on its serialized state executor and recheck ownership
after asynchronous work. A committed receipt, or an absent reconciliation body,
does not establish READY.

`control-v1.results.fixtures.json` contains five independently produced Node
vectors, including actual signed provider frames. The first two have an identical
receipt and different retry observations. Fixture keys are public test material
and cannot authenticate remote tests. Regenerate with
`node scripts/build-control-result-fixtures.mjs` in Warden, then copy the resulting
fixture bytes to Network. `control-v1.results.schema.json` is the closed 2020-12
envelope schema; the codecs additionally enforce decoded bytes, canonical base64,
strict JSON bodies and whole-carrier authentication.
