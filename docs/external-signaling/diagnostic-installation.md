# Owned diagnostic installation (staged)

`ProviderTransport` now exposes an optional neutral diagnostic installation
boundary. `NativeProviderTransport` implements it only for a controlled version 2
listener. No factory setting, ProviderClient heartbeat, Warden policy authority,
public route or deployment enables it here. Normal player keys, application
basis, acceptance state and event reporting remain separate.

The trusted caller first verifies its provider policy document. It passes the
gate subset as `DiagnosticAdmission.Policy`, plus the original nonblocking
authority/native-owner guard, to `installDiagnosticPolicy`. `Binding` contains
the existing diagnostic context, separate control authority incarnation, issued
native owner epoch, exact profile revision and SHA, policy revision and
installation SHA, and the expected host fingerprint. Profile/installation hashes
are canonical base64url SHA-256; fingerprint and numeric address remain lower hex.
This model does not calculate or authenticate an installation digest, import an
answer signing catalog, or prove workload/public-target authorization.

The adapter checks its actual incarnation, DTLS identity, canonical current
profile digest and every explicitly selected endpoint/type. It supports all 32
profile endpoints and eight diagnostic epochs without truncation. The policy
has a fixed parent interval of at most 300 seconds, and each endpoint has its
own fixed expiry no later than that parent. Native admission also refuses a
permit whose original deadline exceeds its selected endpoint deadline. An
expired IPv4 endpoint does not cap a live IPv6 endpoint's native authority.
Configured forwarding and an installation acknowledgement still do not prove
reachability or cross-family translation; the actual gate/selected-path checks
retain their existing same-family constraints.

One underlying install is allowed in flight per native transport. The event-loop
handoff checks the original guard before installation and after it. The returned
`Installation` is an exact acknowledgement capture: its `requireCurrent()` is
synchronous, performs no I/O or blocking native read, and checks native lifetime,
endpoint and player-key snapshot ownership, original times and exact install
identity. `captureDiagnosticInstallation()` returns only a currently usable
capture. A changed/expired endpoint invalidates an acknowledgement of the whole
old document, while other endpoints can remain available in the native gate.

`withdrawDiagnosticPolicy(expected)` compares the exact installation token.
Stale cleanup cannot cancel a newer pending install or withdraw its completed
replacement. Failed post-install guards withdraw only their own installation.
Withdrawal retains the same native gate and its bounded replay/results history;
it does not clear replay reservations, close the gameplay mux or disable players.
Permanent native drain/close retires diagnostic ownership. Temporary controlled
player admission staging alone does not.

The issuing authority must allocate monotone diagnostic endpoint revisions on
owner replacement, endpoint withdrawal/remap/re-addition, or an authorization
break. They are neither profile string revisions nor STUN response sequences.
The native adapter rejects policy revision rollback/conflicting reuse within a
generation, but cannot substitute for the server's durable endpoint revision
allocator: NXD1 carries the numeric endpoint revision and native context, not
the control owner epoch or installation digest. Fresh same-mapping observations
and player-key/profile rebinding may keep an endpoint revision. A replacement
document requires its own exact installation capture/acknowledgement.

Each admitted diagnostic session captures its original `Binding`. Policy refresh
or player-key/profile rebinding does not rewrite that binding or extend the
session deadline. Changing native/control owner, removing its endpoint/key, or
passing the current endpoint/global/original permit expiry terminates affected
authority. Remap A→B→A cannot reopen an already stopped session or its old
acknowledgement capture. Other still-authorized sessions survive.

`pollDiagnosticResults(maximum)` drains at most 0–32 results into neutral immutable
`DiagnosticAdmission.Completion` values; it never drains player events. Native
and mapped results retain the admitting binding, original attempt/target/offer
and client fingerprint, selected tuples, native UDP counters, frame counters,
completion digest and completion time. `cleanupComplete` means the allocated
native peer's cleanup settled successfully, independently of diagnostic success.
Success still requires the complete four-direction exchange and cleanup before
the original deadline. Frame counts alone do not establish partial direction
proof. The existing queue has 32 entries and a dropped-result counter; callers
must own durable delivery/receipt reconciliation separately. Nothing here rebinds
a delayed result to the current profile or turns it into a reachability report.

Low-level `NativeAdmissionServerChannel.enableDiagnostics` remains available for
explicit native fixtures. Its old policy constructor has no installation binding,
so its result association is null. The controlled adapter refuses to take over a
gate created through that unrelated path. No such result is a production install
acknowledgement.

Focused tests exercise actual signed incoming UDP/DTLS/SCTP in both families,
admission-time binding across player-key rebinding, original expiry, owner change,
remap/withdrawal, delayed guards, exact 32-endpoint validation, bounded polling,
and an existing player continuing to exchange data through diagnostic install
and withdrawal. These are local transport tests; application save/heartbeat ACK,
Warden correlation, regional traffic and stock gameplay remain separate gates.
