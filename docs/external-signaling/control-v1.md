# Optional NXS WebSocket transport

WebSocket carries the existing signed NXS operations and opt-in assisted joins.
Registration, completion and recovery use HTTPS. `ProviderClient` defaults to
`HTTP`; `AUTO` selects the advertised WebSocket and falls back to HTTPS for
ordinary operations. Both carriers share identity, sequencing and recovery.

## Discovery and authentication

The noncritical `org.nethernet.websocket` discovery extension has `version: 1`
and `data: {url, subprotocol}`. The endpoint is the provider's same-authority
`wss://HOST/v1/nxs/control`, with subprotocol `nethernet-external-signaling-v1`.
Loopback development may use WS. Apply the canonical-origin rules below and
normal TLS verification; credentials must not follow redirects.

Authenticate the upgrade with the existing NXS request headers and signature
for `GET /v1/nxs/control`, an empty body, and the HTTPS provider origin. A host
explicitly configured for assistance also sends `nxs-assisted: 1`.

## Operation envelopes

`heartbeat`, `outcomes`, `rotate`, `retire` and `deregister` use this text envelope
when their discovered URL is the standard `/v1/nxs/{operation}` endpoint:

```json
{"operation":"heartbeat","headers":{"nxs-instance-id":"...","nxs-key-id":"...","nxs-timestamp":"...","nxs-signature-version":"nxs-es384-v1","nxs-generation":"...","nxs-sequence":"...","idempotency-key":"...","nxs-signature":"..."},"body":"original JSON body"}
```

Sign the original body for the same POST target, generation, sequence and
idempotency key used by HTTPS. The frame cannot choose another URL or method.
Other advertised operation URLs continue over HTTPS. The response is
`{id, status, headers, body}`: the original idempotency key, HTTP status, response
headers and unmodified response body string. Preserve operation errors, retry
hints and replay handling across fallback. An ambiguous operation is recovered
before sending fresh state.

The Java adapter limits complete UTF-8 messages to 524,288 bytes, response bodies
to 65,536 bytes and queued transport sends to four. `ProviderClient` serializes
ordinary operations. Existing NXS operation limits still apply. The host sends
literal `ping` and expects `pong` for socket liveness; these strings do not
acknowledge operations or renew a heartbeat lease.

## Assisted joins

Assistance requires explicit host configuration, native transport support and a
live authenticated WebSocket. The host advertises eligible `assistedFamilies` in
its [connectivity extension](wire-reference.md#optional-connectivity-observation).
A provider sends an `assisted-join` object with `version: 1` and these fields:

| Fields | Meaning |
| --- | --- |
| `id`, `expiresAt` | Attempt ID (32 lowercase hex digits) and fixed Unix millisecond deadline |
| `instanceId`, `generation`, `incarnation` | Current registered host context |
| `keyId`, `hostFingerprint` | Installed admission key and host DTLS identity |
| `networkId`, `cpk` | Player network ID and canonical P-384 identity key |
| `localUfrag`, `localPassword`, `offer` | Host admission credentials and exact client SDP |

The frame also contains `kind: "assisted-join"`. Player attempts expire within
30 seconds. The host validates the context, credentials and offer before creating
a peer on its existing gameplay socket. It returns
`{kind:"assisted-join-result",id,accepted:true,answer}` or
`{kind:"assisted-join-result",id,accepted:false}`. Diagnostic assistance uses the
same exchange with the [connectivity-check purpose](diagnostic-v1.md#profiles).

The Java client bounds in-flight assisted requests to 32. Results require the
original socket and unexpired host/profile/key authorization; superseded or late
results are discarded. Successful WebSocket heartbeats refresh local assisted
authorization for at most five minutes. Reconnect and ping/pong do not refresh it.
The host retains control of serving, draining, publication and assistance policy.

## Canonical origins

Signed contexts and trusted expected origins use exact ASCII strings. Validate
or reject them without normalization or DNS resolution. An origin is lowercase
`https://HOST`, optionally followed by a decimal port 1–65535 without leading
zeros. Omit default port 443. Development `http://HOST` permits only `localhost`,
`127.0.0.1` or `[::1]`, and omits default port 80. The limit is 2,048 characters;
path (including `/`), query, fragment, userinfo, whitespace, control characters,
percent escapes and backslashes are prohibited.

HOST is one of:

- Lowercase ASCII DNS labels of 1–63 characters, at most 253 total. Labels start
  and end with `[a-z0-9]`, with only `[a-z0-9-]` inside. The final label starts with
  `[a-z]`. Single-label names are allowed; empty labels, trailing dots, Unicode
  and all `xn--` labels are rejected.
- Four decimal IPv4 octets, 0–255, without leading zeros or shortened aliases.
- Bracketed IPv6 with shortest lowercase hex pieces, compressing the longest run
  of at least two zero pieces; the first run wins a tie. IPv4-mapped addresses
  use hex (`[::ffff:c000:280]`), not dotted tails. Zones, expanded aliases and
  unbracketed literals are rejected.

These rules keep Java and JavaScript origin bytes identical. They do not change
core HTTPS origin handling. The shared
[origin fixtures](control-v1.origins.fixtures.json) include rejected aliases and
independently generated IPv6 cases; their
[Node verifier](control-v1.origins.fixtures.mjs) checks accepted WHATWG bytes and
can compare Java results with `--java-output=PATH`.
