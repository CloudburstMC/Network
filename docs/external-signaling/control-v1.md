# Optional NXS WebSocket transport

WebSocket carries the existing signed NXS operations.
Registration, completion and recovery use HTTPS. `ProviderClient` defaults to
`HTTP`; `AUTO` selects the advertised WebSocket and falls back to HTTPS for
ordinary operations. Both carriers share identity, sequencing and recovery.

## Discovery and authentication

The noncritical `dev.opencollab.nxs.websocket` discovery extension has `version: 1`
and `data: {url, subprotocol}`. The endpoint is the provider's same-authority
`wss://HOST/v1/nxs/control`, with subprotocol `nethernet-external-signaling-v1`.
Loopback development may use WS. Validate the provider origin and use
normal TLS verification; credentials must not follow redirects.

Authenticate the upgrade with the existing NXS request headers and signature
for `GET /v1/nxs/control`, an empty body, and the HTTPS provider origin.

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

Response `headers` maps HTTP field names to string values. Names are case-insensitive
ASCII HTTP tokens, at most 128 characters; values are at most 1,024 characters
without control characters. Accept up to 32 distinct names, reject duplicates
ignoring case, and ignore fields the client does not use. No provider-specific
header is required.

The Java adapter limits complete UTF-8 messages to 524,288 bytes, response bodies
to 65,536 bytes and queued transport sends to four. `ProviderClient` serializes
ordinary operations. Existing NXS operation limits still apply. The host sends
literal `ping` and expects `pong` for socket liveness; these strings do not
acknowledge operations or renew a heartbeat lease.
