# NetherNet External Signalling

NXS lets a NetherNet host use a signalling provider chosen by its operator.
The integration has three flows: register, heartbeat, and accept joins with
asynchronous feedback. This is an experimental contract, updated in place.
The [wire reference](wire-reference.md), [schema](nxs-v1.schema.json) and
[fixtures](nxs-v1.fixtures.json) specify the exact formats under Apache-2.0.

## 1. Register with a provider

Configure the provider's HTTPS origin and fetch
`/.well-known/nethernet-external-signalling`. Check its supported authentication
and use the operation URLs it returns. All URLs must remain on that origin;
never follow redirects when discovering or sending credentials.

Create and save a P-384 machine key for this instance. Call `register` with the
public key and one of these enrollment choices:

- `new-service`: create a service and its first instance, using advertised
  anonymous proof of work or a bearer token.
- `attach-instance`: add an instance to an existing service, using a bearer
  token and authorized placement metadata.

The provider returns a challenge. Sign its bound proof and call `complete`.
Completion returns the assigned IDs, a fresh process generation, lease deadline
and initial admission key. Save them before publishing readiness. Completion
starts the generation; there is no separate activation call.

On restart, reuse the saved machine key and call `register` with
`{registrationId,protocol,profile}`. Prove the returned challenge through
`complete` to preserve IDs and fence the previous process. If a completion reply
was lost, recover using the saved challenge ID. Repeating a consumed completion
cannot start another generation or reveal its secrets again.

Every live replica needs its own key and private state directory. Account/token
issuance, ownership claims and fleet administration belong to the provider.

## 2. Heartbeat to the provider

Send a signed `heartbeat` immediately after startup and whenever its returned
schedule says to check in. The request carries:

- Health, capacity, load and optional public server status.
- `hostProfile` when endpoint details change; otherwise `hostProfileRevision`.
- `installedKeyIds`, listing installed admission epochs with the active one last.
- Local `state` (`serving`, `draining` or `closed`), the applied provider-state
  revision, and whether the integration can report game outcomes.

The reply returns the accepted profile revision, readiness, lease/schedule,
provider state and any admission-key updates. A host becomes routable only with
a live lease, usable profile and acknowledged installed key.

Apply provider state before acknowledging its revision. `draining` stops new
joins and preserves existing sessions; `closed` closes the transport. Provider
routing and credential decisions take effect independently of host check-in.

For a replacement admission key, include a fresh `keyRequestId`. Save and install
the returned key, then immediately heartbeat with the updated profile and
installed IDs. The provider cannot issue new tokens under that epoch before the
acknowledgement. Retain older keys until their reported retirement deadlines.

On orderly shutdown, stop accepting new joins and immediately heartbeat with
`state: "draining"`. Do not wait for the periodic timer. A provider outage lets
routing leases expire; it does not by itself close established sessions.

## 3. Accept a stateless join and report the outcome

The provider gives the client the host's connection details and a short-lived
admission token. The client carries that token in its first STUN packet. The
host validates token authentication, expiry, endpoint/client binding and STUN
integrity locally before creating a peer. DTLS must then verify the client
certificate fingerprint from the token.

No provider push, poll, lookup or pre-staged client state may gate admission.
The host retains its own background keys and active connection state.

Send signed `outcomes` batches asynchronously, independently of heartbeat timing:

| Observation | Required feedback |
| --- | --- |
| Both data channels become usable | `ticket.data_channels_open` |
| An authenticated observed attempt fails before transport becomes usable | `ticket.failed`, with a bounded reason |
| The game admits or rejects the player | `ticket.game_joined` or `ticket.game_rejected`, when the integration observes this boundary |

Declare game-outcome support as `available` or `unavailable` in heartbeat. A
transport connection never proves successful gameplay. Intermediate ICE, DTLS
and SCTP stages are optional diagnostics in the same stream.

Each event contains `ticketId`, `stage`, `occurredAt` and optional `reason`.
Retry bounded batches without duplicating observations. Do not send player
identity, SDP, credentials or game payloads. Reporting failure never delays
admission or renews a lease. Missing feedback means an unknown outcome: a client
may never reach the host, or the host may crash before reporting.

## Operation reference

All operations use POST; all except `register` and `complete` use the machine
request signature. URLs come from discovery.

| Operation | Purpose |
| --- | --- |
| `register` | Request an enrollment or recovery challenge |
| `complete` | Prove the challenge and start the process generation |
| `heartbeat` | Exchange host health, profile, keys, lifecycle state and readiness |
| `outcomes` | Report transport and game observations |
| `rotate` | Prove and install a replacement machine signing key |
| `retire` | Retire the previous machine signing key |
| `deregister` | Permanently end this instance's registration |

Machine-key maintenance is separate from admission-key updates. Exact signing,
request fields, key handling, token layout, bounds and retries are in the
[wire reference](wire-reference.md).

Hosts can request automatic registration: the provider uses token authority to
choose account provisioning or attachment. Anonymous hosts create new services.
Geyser exposes only signalling mode, advertised endpoints, token, provider origin
and registration metadata; see the [Geyser configuration](https://github.com/teamziax/GeyserNetherNet/blob/nxs-dev/PROVIDER.md).
