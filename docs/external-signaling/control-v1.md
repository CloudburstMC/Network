# Optional NXS WebSocket transport

WebSocket can carry the signed NXS operations and opt-in assisted joins.
Registration, completion and recovery always use HTTPS. Both carriers share
identity, sequencing and recovery, so a client can fall back to HTTPS for
ordinary operations.

## Discovery and authentication

The noncritical `dev.opencollab.nxs.websocket` discovery extension, `version: 1`
with empty `data`, advertises the endpoint. It is `/v1/nxs/control` on the
provider origin with scheme `wss`, and subprotocol `nethernet-external-signaling-v1`.
Loopback development may use WS. Apply the
[canonical origin](wire-reference.md#canonical-origins) rules and normal TLS
verification; credentials must not follow redirects.

Authenticate the upgrade with the
[NXS request headers and signature](wire-reference.md#sign-operational-requests)
for `GET /v1/nxs/control`, an empty body, and the HTTPS provider origin. A host
whose profile declares `assistedFamilies` also sends `nxs-assisted: 1`, a routing
hint for the provider that grants nothing by itself.

## Operation envelopes

`heartbeat`, `outcomes`, `rotate`, `retire` and `deregister` use this text envelope:

```json
{"operation":"heartbeat","headers":{"nxs-instance-id":"...","nxs-key-id":"...","nxs-timestamp":"...","nxs-signature-version":"nxs-es384-v1","nxs-generation":"...","nxs-sequence":"...","idempotency-key":"...","nxs-signature":"..."},"body":"original JSON body"}
```

Sign the original body for the same POST target, generation, sequence and
idempotency key used by HTTPS. The frame cannot choose another URL or method.
Operations advertised by extensions use HTTPS. The response is
`{id, status, headers, body}`: the original idempotency key, HTTP status, response
headers and unmodified response body string. Preserve operation errors, retry
hints and replay handling across fallback. An ambiguous operation is recovered
before sending fresh state.

Response `headers` maps HTTP field names to string values. Names are case-insensitive
ASCII HTTP tokens, at most 128 characters; values are at most 1,024 characters
without control characters. Accept up to 32 distinct names, reject duplicates
ignoring case, and ignore fields the client does not use.

Clients limit complete UTF-8 messages to 524,288 bytes and response bodies to
65,536 bytes. The [NXS operation limits](wire-reference.md#authorization-and-limits)
also apply. The host sends literal `ping` and expects `pong` for socket liveness;
these strings do not acknowledge operations or renew a heartbeat lease.

## Assisted joins

Assistance requires explicit host configuration, host transport support and a
live authenticated WebSocket. The host declares eligible `assistedFamilies` in its
[host profile](wire-reference.md#publish-the-host-profile).
A provider sends an `assisted-join` object with `version: 1` and these fields:

| Fields | Meaning |
| --- | --- |
| `id`, `expiresAt` | Attempt ID (32 lowercase hex digits) and fixed epoch millisecond deadline |
| `instanceId`, `generation`, `incarnation` | Current registered host context |
| `keyId`, `hostFingerprint` | Installed admission key and host DTLS identity |
| `networkId`, `cpk` | Player network ID and canonical P-384 identity key |
| `localUfrag`, `localPassword`, `offer` | Host admission credentials and exact client SDP |

The frame also contains `kind: "assisted-join"`. Player attempts expire within
30 seconds. The host validates the context, credentials and offer before creating
a peer on its gameplay socket. It returns
`{kind:"assisted-join-result",id,accepted:true,answer}` or
`{kind:"assisted-join-result",id,accepted:false}`. Assisted
[diagnostics](diagnostic-v1.md#direct-and-assisted-transport) use the same exchange.

Results require the original socket and unexpired host/profile/key authorization;
superseded or late results are discarded. Successful WebSocket heartbeats refresh
local assisted authorization for at most five minutes. Reconnect and ping/pong do
not refresh it. Publication and assistance policy stay with the host; see
[serving control](wire-reference.md#serving-control).
