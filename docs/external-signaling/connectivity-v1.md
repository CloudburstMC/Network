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

## Optional startup diagnostic window

A host can advertise `startupDiagnostics:true` in the version 1 heartbeat data
when local diagnostic admission is enabled. This field advertises support for a
bounded startup window. The separate `diagnostics` flag still means diagnostic
permission is already installed on the host.

A participating provider echoes the request's `candidateRevision` and adds
`startupDiagnostics:{"leaseMillis":<integer>}` to the heartbeat reply data.
A positive duration, up to 300,000 milliseconds, is the remaining diagnostic
window measured from when that heartbeat was sent. It is further limited by
the ordinary check-in lease, key retirement, and endpoint expiry. The provider
keeps a fixed deadline for an open window, including across retries.
`leaseMillis:0` closes diagnostic permission and leaves the ordinary check-in
schedule in force. A host advertises `diagnostics:true` in an immediate follow-up
only after installing permission for the current profile. The provider can then
dispatch diagnostics against that profile and may include an integer
`feedbackAfterMillis` in its reply. This hint must be positive and less than
`leaseMillis`. It is measured from the heartbeat that advertised installed
permission and allows one follow-up to retrieve fresh probe results. The host
keeps the earliest hint, sends at most one feedback-only heartbeat per window,
and processes feedback on any earlier ordinary heartbeat. A heartbeat at the
feedback deadline also satisfies the hint if a regular check-in is due then.
Replies cannot re-arm an attempted feedback exchange. A failed feedback-only
request uses one short attempt and does not back off liveness. Invalid feedback
hints are ignored.

The host expires permission locally and follows the regular heartbeat schedule
without a diagnostic renewal. The optional feedback exchange cannot extend the
window and is cancelled when the permission closes or expires. Player-driven
updates and the separate assisted-join authority keep their own schedules.

When the request omits support, the provider uses the existing behavior and omits
the window. When the reply omits the window, a supporting host uses the existing
diagnostic refresh behavior. This lets either side upgrade or roll back
independently. Once the field is present, an invalid duration or mismatched
candidate revision grants no diagnostic permission and the successful ordinary
heartbeat still applies. See the [window fixtures](fixtures/startup-diagnostic-window.fixtures.json).
