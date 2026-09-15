# Controlled native owner adoption

`ProviderControlConfiguration.NativeOwnership.ISSUED` opts an already enrolled
controlled host into the provider-issued native owner contract. The existing
three-argument configuration constructor keeps ownership disabled. The transport
must explicitly support controlled v2 profiles and `captureNativeIdentity()`;
`NativeProviderTransport.openControlledVersion2` supplies both. The legacy host
factory does not opt into this mode.

The wire `nativeOwnerClaim` and `nativeOwner` shapes are defined in
`nxs-v1.schema.json` and implemented by `CandidateLeaseCodec`. Adoption publishes
an actual full v2 empty or host-only profile with an expected provider epoch and a
fresh claim ID. Admission keys are installed before the claim; a new key request
is a separate operation. Metadata-only discovery obtains the current provider
owner without attaching it to the local listener. A conflict response alone does
not settle a claim: the original immutable intent must be strongly resolved or
cancelled before another claim can be submitted.

## Historical proof and live attachment

The root application state retains a bounded `nativeOwnerReceipt` containing the
issued tuple, original intent and body digests, committed receipt digest,
sequence and full profile digest. Its enclosing registration generation and
verified control journal bind the subject. Replaying an identical receipt is
idempotent; dropping history, moving its sequence backwards or changing a marker
at the same sequence is rejected. The persisted `nativeOwnership: "issued-v1"`
marker prevents restarting the same state with ownership silently disabled.

The coordinator persists the original intent, original bytes and committed
receipt in its journal before the application saves this historical record.
Only after the application callback succeeds may it clear that journal entry.
This ordering covers live operation results, strong status, a cancellation that
lost the race to commit, and restart with an already recorded committed receipt.
A failed or timed-out callback keeps the existing settlement barrier; retries
perform only idempotent local acknowledgement, with no retransmission of a
committed claim. A timed-out callback must actually settle before another starts.

A live binding is separate, in memory only. Attachment requires this process's
original exact claim body, full-profile capture and native listener lifetime
capture before and after durable storage. A committed predecessor claim still
saves history after its original capture retires, but cannot attach a replacement
listener. The next pass discovers the provider's updated epoch before adopting
the replacement. Receipt-only recovery may attach the original still-live
capture; it supplies no application body, key material or readiness authority.

`NativeIdentitySnapshot` is independent of candidate material, admission keys and
control WebSocket epochs. It survives endpoint withdrawal/restoration, key
replacement and reversible desired draining. Closure, permanent drain or a new
native listener invalidates it permanently. The original full-profile capture
also guards initial adoption, so an A → B → A endpoint transition cannot make an
old pending adoption live again. Ordinary material or control transport changes
after attachment do not require a new owner epoch.

## Admission and current scope

An owner tuple never substitutes for the applied native basis, ticket policy,
current signed delivery or application acknowledgement. Serving requires the
existing exact application protocol and an attached current owner. Empty actual
profiles report `acceptingPlayers: false`. Non-serving states require no owner
claim; a listener with a draining reporting floor can prepare an owner when a
current response asks it to serve, while continuing to report its actual applied
state until that transition completes.

This slice does not publish candidate leases, activate a STUN monitor, change
factory discovery defaults or install diagnostic support. Native SRFLX
publication remains rejected. Lease/routing expiry enforcement and actual
provider carrier adoption tests are separate integration gates.
