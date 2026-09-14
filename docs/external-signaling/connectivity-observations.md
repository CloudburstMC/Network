# Optional endpoint discovery and same-mux observations

This slice provides an executable local controller and an explicit attachment API on
`NativeAdmissionServerChannel`. It does not activate a new provider mode, alter HTTP
messages, publish STUN endpoints, or change Geyser configuration. The existing
`ProviderEndpoint.resolve` path and its additive `advertise-addresses` behavior are
unchanged until the consumer opts into the new policy.

`EndpointSelection.select` receives a completed local discovery cut. Any configured
endpoint makes the complete configured set authoritative across both families.
Invalid configured values fail closed; an omitted family stays disabled. Explicit
forwarders can translate families. With no explicit entries, only public addresses
from the bound interface, host settings, local interfaces and same-mux native host
candidates are selected. Every local hint must use the gameplay UDP port. A Java
backend `server.properties` port or separately gathered ephemeral ICE port must not
be reused as that port. Host adapters supply appropriate server-property/native
hints; the library does not read another application's configuration files.
`discover` can enumerate local interfaces without DNS/STUN. IPv4 wildcard binding
supports IPv4 discovery only; the existing dual-stack IPv6 wildcard mux supports
both families. Address provenance is separate from a connectivity result.

After the gameplay listener is bound, an opt-in caller invokes:

```java
EndpointSelection plan = EndpointSelection.discover(bind, configuredEndpoints, localHints);
EndpointConnectivityController controller = channel.enableConnectivity(
    plan, numericStunServersByFamily, Duration.ofSeconds(45)).toCompletableFuture().get();
```

The selected plan must match the listener binding exactly. At most one controller
can attach to a channel, and each family owns at most one native monitor. Native
monitors inherit the actual listener's bind spelling and port; no separate utility
socket is created. Closing the channel closes monitors before its listener. Closing
a monitor leaves the listener and other peers owned by the channel intact.

For a family with direct candidates, `beginDirectCheck` supplies an opaque token and
its exact candidate list. `completeDirectCheck` accepts only the current token once;
replaced, duplicated, expired and post-close results are ignored. Request and result
share one fixed monotonic deadline from `beginDirectCheck`, default 30 seconds and
explicitly bounded to five minutes. Completion cannot renew that deadline. Fresh
snapshots expire old results to `UNKNOWN`; `directCheckAt` checks a retained report's
deadline too. Initially `UNKNOWN` remains awaiting a check; only a timely `FAILED`
result selects STUN fallback. A completed discovery cut with no direct
candidate can proceed to STUN immediately. Configured endpoints never enable STUN,
even after a failed check. These outcomes describe the caller's check; a successful
regional check does not prove reachability from arbitrary sources. Authenticating,
scoping those external check results belongs to the integration layer. Reporting
expiry or a later `UNKNOWN` does not retire an already selected STUN fallback: its
native monitor stays warm during a reporting/control-plane outage. A fresh direct
success, explicit monitor/configuration replacement or closure retires that monitor.

`snapshot()` starts eligible monitors and copies native observations; it sends no
refresh packet itself. Native code refreshes independently with zero players.
Consumers poll on a bounded background cadence to observe changes; polling or an
HTTP heartbeat never renews observation age. Every fresh observation includes a
monotonic deadline. A retained snapshot must not be used beyond that deadline.
Unchanged successful refreshes retain the candidate revision. Remaps, expiry,
monitor replacement and closure withdraw/change the available STUN endpoint and
advance the revision; monitor epochs disambiguate native counters after replacement.
The scope of both revisions is this live controller, not provider generation or
admission incarnation. A failed native transaction retains historical metadata only
until its original freshness bound. A terminal monitor read/open failure remains
failed without an allocation retry loop; an explicit server replacement can retry.
If native close itself fails, the controller withdraws the endpoint and retains the
owned handle for explicit close/replacement retry. Snapshots do not spin retries or
reopen a duplicate. `close()` reports failed teardown and can be retried; a `CLOSED`
snapshot withdraws authority but does not claim native destruction succeeded.

Only public mapped addresses appear as `freshStunEndpoint`. Private/loopback
responses remain visible as ineligible observations. No observation marks a host
reachable, supplies an assisted peer identity, or modifies a live admission key,
transport incarnation or established peer. All observed STUN endpoints remain
outside the legacy `host`-only candidate profile.

The DNS adapter remains a separate integration seam. Resolve off the channel event
loop with bounded outstanding work and explicit per-family numeric selection;
calling `replaceStunServer` closes the old monitor and withdraws its mapping before
the new epoch starts. A hostname's first two native resolver results do not establish
dual-family coverage. This API deliberately accepts numeric family-matched servers
and performs no hidden DNS fallback.

Before provider activation, coordinate the candidate/provenance/revision and empty
withdrawal schema, prompt profile-update delivery, authenticated direct-check
feedback and expiry, ingress-dependent IPv6 eligibility, assisted candidate
restrictions, bounded local-interface rediscovery with revision/token invalidation,
host logging, and Geyser's authoritative config wording. The current
`NativeProviderTransport` requires a nonempty candidate list and labels all entries
`host`; wiring this controller's `srflx` observations into that list would mislabel
them and cannot safely withdraw the final expired endpoint. Geyser currently passes
`nxs.advertise-addresses` as `advertisedEndpoints` and calls it additional; it has no
opt-in selection/controller or backend property hint adapter yet.
Startup must also separate binding the listener from publishing its first usable
profile; the legacy factory validates a nonempty endpoint list before binding.

Compilation/runtime require the new native chain (local reviewed JNI `25454a4`,
libdatachannel `616f4f2`, libjuice `2798b0f`). No remote dependency pins or artifacts
are changed here. Source-compatible local substitution was tested with the matching
JNI library; publication and coordinated consumer pins remain required. Unit tests
cover policy, correlation, family separation, expiry, remap, failure and cleanup.
The native integration test runs two actual loopback Binding responders against the
same dual-stack gameplay mux, verifies the source port and two responses per family,
zero player creation, unchanged admission profile, and port release on channel close.
This is socket/lifecycle evidence, not public-NAT, stock-client or gameplay proof.
