# Controlled heartbeat cancellation

This staged optional action settles a retained heartbeat which a restarted native instance cannot truthfully replay. It is unavailable until an exact cancellation route and service are configured. It does not require application readiness, mount a production route or enable enrollment.

Use the existing signed bootstrap envelope with `action: "cancel-intent"`, method `POST`, and the exact configured target. Its payload has exactly these fields:

```json
{"intent":{"version":1,"audience":"https://provider.example","operation":"heartbeat","instanceId":"host_example","generation":1,"sequence":3,"idempotencyKey":"original_intent_example","payloadSha256":"original_body_digest"},"expectedWriter":{"transport":"https","sessionEpoch":2,"sessionId":"current_session_example","connectionId":"current_connection_example","keyId":"current_key","machineKeyRevision":1},"reason":"native-application-replaced"}
```

The illustrative digest above must be replaced with the canonical original 32-byte SHA-256 base64url value. Retain the full original intent unchanged. The nested intent must be a heartbeat for the envelope's audience, host and generation. The expected writer must be controlled and its key ID must match the signing machine key. Current canonical key/material/scopes, writer, policy and the fresh request's original expiry are checked before the atomic mutation. No original body or one-time secret is sent in cancellation, and cancellation consumes no new lifecycle sequence.

The provider signs the existing response envelope with `kind: "cancel-intent"`. Its payload is exactly `{ "intentDigest": "...", "receipt": { ... } }`. The receipt must match every field of the original intent and have disposition `committed`, `rejected` or `cancelled`. A committed or rejected original result is returned unchanged. Unknown, null and carrier expiry do not settle the intent.

A new `cancelled` receipt has `operation: "heartbeat"`, `committedAt: null`, `commitRevision: null`, and `code: "native-application-replaced"`. It promises that the original operational heartbeat and applied-basis acknowledgement did not commit and cannot commit later. It permits historical archive handoff which was already authorized or in flight. It is distinct from the existing no-effects `rejected` disposition. Lifecycle result bodies accompanying cancellation are exactly `{}`; cancellation returns no secret, application observation or authority grant.

After a lost physical connection, the host may prepare and activate a fresh writer while preserving the pending original intent. It must suppress unsafe heartbeat replay, cancel before application readiness, durably consume the verified terminal receipt, then apply the new native instance and submit the next sequence. A lost cancellation response is recovered through existing signed `intent-receipt` status. Missing cancellation support leaves the original intent unresolved; it does not authorize changing its body or abandoning it.

`control-v1.sessions.fixtures.json` includes an independently Node-signed cancellation request and cancelled, committed and rejected responses. All existing signature domains remain unchanged; the distinct signed action/kind provides separation. Duplicate JSON fields, invalid numbers, incompatible subjects, wrong key IDs, unsupported reasons/operations and mismatched receipt fields are rejected.
