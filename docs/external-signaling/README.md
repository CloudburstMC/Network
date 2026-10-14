# NetherNet External Signaling

NXS lets a NetherNet host use a signaling provider chosen by its operator.
Accounts, token issuance and fleet administration belong to the provider. The
[wire reference](wire-reference.md), [schema](nxs-v1.schema.json) and
[fixtures](fixtures/nxs-v1.fixtures.json) specify the exact formats under
Apache-2.0. Version 1 only grows by addition; see
[compatibility](wire-reference.md#compatibility).

## 1. Register with a provider

The host fetches discovery from the provider's HTTPS origin, then creates and
saves a P-384 machine key and calls `register`. A bearer token, if the host has
one, decides whether the provider creates a new service or attaches the instance
to an existing one; without a token the host creates a service using proof of
work. The host signs the returned challenge and calls `complete`, which returns
its IDs, a fresh process generation and the initial admission key.

On restart the host proves the same key again to keep its IDs and fence the
previous process. Each live replica needs its own key and state directory. See
[registration](wire-reference.md#registration-and-persistent-identity).

## 2. Heartbeat to the provider

The host sends a signed `heartbeat` at startup and whenever the reply's schedule
says to. It reports whether it accepts players, its capacity and actual player
count, its host profile and its installed admission keys. The reply carries
readiness, the routing lease and any admission-key updates. The provider can stop
routing to a host but never commands its listener; see
[heartbeat](wire-reference.md#heartbeat) and
[serving control](wire-reference.md#serving-control).

## 3. Accept a stateless join and report the outcome

The provider gives the client the host's connection details and a short-lived
admission token. The client carries the token in its first STUN packet, and the
host validates it locally before creating a peer; see the
[stateless admission carrier](wire-reference.md#stateless-admission-carrier).
The host reports transport and game observations asynchronously in signed
[`outcomes`](wire-reference.md#outcomes) batches.

## Operation reference

Every operation is a POST to `/v1/nxs/<operation>` on the provider origin. All
except `register` and `complete` carry the
[machine request signature](wire-reference.md#sign-operational-requests).

| Operation | Purpose |
| --- | --- |
| `register` | Request an enrollment or recovery challenge |
| `complete` | Prove the challenge and start the process generation |
| `heartbeat` | Report acceptance, counts and profile/key acknowledgements |
| `outcomes` | Report transport and game observations |
| `rotate` | Prove and install a replacement machine signing key |
| `retire` | Retire the previous machine signing key |
| `deregister` | Permanently end this instance's registration |
| `operator-action` | Request a fresh [operator link](wire-reference.md#operator-actions), when one is offered |

`rotate` and `retire` maintain the machine signing key, separately from
admission keys.

## Optional extensions

- [WebSocket transport](control-v1.md) carries ordinary operations and opt-in
  assisted joins.
- [Connectivity observation](wire-reference.md#optional-connectivity-observation)
  publishes fresh endpoints and uses regional checks to adjust later offers.
- [Diagnostics](diagnostic-v1.md) define signed diagnostic admission and a single
  PING/PONG exchange.

The [Java client](../../external-signaling/README.md) is a reference
implementation.
