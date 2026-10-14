# Optional NXS connectivity observation

The noncritical `dev.opencollab.nxs.connectivity` version 1 extension carries discovery,
heartbeat capability and regional feedback. Discovery supplies STUN servers:

```json
{"version":1,"critical":false,"data":{"stunServers":[{"host":"stun.cloudflare.com","port":3478}]}}
```

Resolve up to two servers, choosing a numeric address per supported family.
Without configured endpoints, prefer public local addresses, then discover STUN
mappings on the gameplay socket. Publish fresh mappings immediately and maintain
them independently of heartbeat timing. Explicit endpoints are the complete
eligible set and suppress automatic discovery and STUN for all families. An
absent or empty server list leaves STUN discovery unavailable.

Host heartbeats report:

```json
{"version":1,"critical":false,"data":{"diagnostics":false,"candidateRevision":1,"method":"discovered"}}
```

Assistance defaults off and requires the [WebSocket transport](control-v1.md); when enabled it uses bounded
per-join discovery instead of background warming. Configured endpoints restrict
assistance to their public families. Probe verdicts never change this choice.
`method` says how the candidates were found: `defined`, `discovered` or `warm_stun`.
`candidateRevision` changes with candidate material or native mapping ownership,
including replacement with the same address; unchanged STUN refreshes retain it.
When diagnostic targets differ from the player candidates, include `probeCandidates`
in the connectivity data using the same candidate shape. This list replaces the
profile candidates for diagnostics only, so withdrawn endpoints can still be
tested for recovery. Omit it when the two lists match; an empty list withdraws
all direct probe targets.

The provider returns regional feedback in the heartbeat reply's
`extensions["dev.opencollab.nxs.connectivity"]` as `{method,candidateRevision,checks}`.
Up to eighteen checks retain `{region,family,method?,target?,outcome,checkedAt,expiresAt}`.
`method` is how the checked candidate was found, or `per_join` for an assisted
check. A target is an exact numeric `{address,port}`; an assisted failure may omit
it. `established` is a success, `not-established` a failure, and `unknown` and
`unavailable` are inconclusive. Accept fresh results only for the current revision
and endpoint. Newer results replace older ones per region/method/target; failure
wins equal timestamps, while inconclusive results leave the previous decision
intact.

Without assistance, the host withholds failed public endpoints from its published
candidates unless another region has a successful result for that endpoint. Keep those
endpoints eligible for recovery probes and keep STUN warming; a subsequent success
can restore them. Observation expiry alone does not change the last decision.
Mapping replacement discards the old mapping's feedback. With assistance enabled,
failed public candidates remain available. Automatic discovery also includes
private local candidates when no public address exists or a matching check fails.
Private addresses remain useful for LAN/VPN clients but are not regional targets.
Publication changes leave established sessions intact.

Diagnostic admission is a separate local opt-in. A successful heartbeat installs
context, existing admission keys and current probe targets for at most five
minutes, bounded by the check-in lease, key retirement and mapping expiry. Report
`diagnostics:true` only while that installation is current. Withdrawn player
offers may remain diagnostic targets; assisted-only families need no published
player candidate. Retries cannot extend authority; drain/close disable diagnostics.
See [diagnostics](diagnostic-v1.md) for admission, signatures and the exchange.
