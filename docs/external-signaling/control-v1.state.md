# Draft control application state

These opt-in codecs define a shared application-state basis, ticket-policy digest,
and the payload of `state.applied` / `session.ready`. They do not enable routes,
alter current authority proofs, persist server state, or establish readiness.
The matching JSON schema and independent Python SHA-256 fixtures accompany them.

An applied basis names generation, exact desired revision/state, admission state,
published host-profile revision and admission-key policy digest. Serving requires
enabled admission and both profile/policy references. Draining or closed requires
disabled admission and null references. Control synchronization while admission is
disabled is distinct from player admission or external connectivity.

The digest is SHA-256, canonical unpadded base64url, over UTF-8 bytes of this JSON
array (no whitespace):

```text
["nethernet-control-applied-state-v1",1,generation,desiredRevision,state,admission,hostProfileRevision,ticketPolicySha256]
```

Ticket policy contains `version:1`, `activeKeyId` and one to eight epoch objects
with `keyId`, `notBefore` and `acceptUntil`. Key IDs match `[A-Z0-9]{4}`, are unique
and strictly ascending, and must include the active key. This canonical ordering
is independent of native installation ordering; adapters preserve the explicit
active key when constructing a native snapshot. The digest covers:

```text
["nethernet-control-ticket-policy-v1",1,activeKeyId,[[keyId,notBefore,acceptUntil],...]]
```

Times are nonnegative safe integer milliseconds. A finite `acceptUntil` must be
greater than `notBefore`. Null means an unretired epoch with no cutoff; it is not
a relative lifetime or a freshly chosen large timestamp. Adapters map the native
unbounded sentinel to null. Finite deadlines must remain absolute and unchanged
through retries, reconnects and persistence. The provider must compare the whole
policy with its own key records; installed IDs alone do not establish agreement
on activation or retirement. These codecs contain no secret key material and do
not evaluate present-time key eligibility.

The projected state summary contains only `desiredRevision`, `desiredState` and
`appliedBasisSha256` (nullable when no accepted basis exists). Acknowledgements
add `version:1` and a fresh `syncId`, and require a non-null digest. Their signed
outer frame supplies provider, host, generation, writer, selected machine key,
capabilities, sequence and deadline. A matching tuple is a comparison, not proof
of authority, native application, persisted state or reachability. The caller
must hold and recheck all those independent conditions. A digest never extends
the original cached source or session deadline.

Application owners calculate the basis only after actual transport application
and successful durable storage. A saved basis is recovery input after a process
or native-instance restart, not permission to skip reapplication. The provider
records the accepted basis atomically with the corresponding heartbeat and
receipt, then publishes the small summary through existing cached authority.
Those persistence, projection, handshake and ProviderClient integrations remain
subsequent implementation work.

Wire objects have closed fields. Duplicate names, alternative integer spellings,
noncanonical base64url, unknown states, reordered/duplicate epochs and oversized
inputs fail. Basis/acknowledgement limit: 2 KiB; ticket policy: 4 KiB. The schema
checks structure; codecs additionally check epoch ordering, active membership and
cutoff ordering. ASCII-only identifiers avoid cross-runtime escaping ambiguity.
