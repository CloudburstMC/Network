# NXS control transport

Status: simplification in progress. This replaces the experimental control-session design; it is not a production-readiness claim. The existing seven NXS operations remain the basis of the single host lifecycle:

`register`, `complete`, `heartbeat`, `outcomes`, `rotate`, `retire`, `deregister`.

## Ownership

The game server owns its actual serving state, bound socket, candidate observations, installed keys, local health and player counts. Warden owns registration, authorization, issued admission material and routing eligibility. Warden can stop routing players to a server; it does not tell the game listener to serve, drain or close. Regional probes supply independent external-connectivity observations.

The only unsolicited control-plane exchange is an authorized assisted player join, exclusively over WebSocket. Ordinary joins remain stateless. There is no generic remote-command or desired-serving-state channel.

## Registration and normal operation

Discovery, registration and recovery use HTTPS. Preserve automatic, new-service and attach-instance registration, proof-of-work and bearer authorization, saved challenges, lost completion responses, metadata/extensions and pending-key recovery. Selecting a transport does not select another registration or application runtime.

The host sends heartbeat observations. The response carries check-in scheduling, needed admission-key material and the latest connectivity result. The host reports successful key installation. Replaceable status is a latest-state observation; registration, key mutations, deregistration and loss-sensitive outcomes retain required replay protection. Transport reconnect must not create another registration.

There are no separate public prepare, activate, session-status, authority-renewal or cancel-intent operations. Internal authentication and stale-writer checks remain implementation responsibilities. A carrier change never bypasses signature, generation, replay or authorization checks.

## WebSocket carrier

The provider advertises a same-authority WebSocket endpoint through the noncritical `org.nethernet.websocket` extension (`version: 1`, data containing `url` and `subprotocol`). The Java client defaults to `HTTP`; `AUTO` opts into an advertised endpoint. The initial carrier implementation uses `GET /v1/nxs/control`, subprotocol `nethernet-external-signaling-v1`, and the existing NXS request signature over that exact GET target with an empty body. Production uses WSS and normal TLS verification; credentials cannot follow a redirect to another authority.

After authentication, ordinary operations use this bounded text envelope:

```json
{"operation":"heartbeat","headers":{"nxs-instance-id":"...","nxs-key-id":"...","nxs-timestamp":"...","nxs-signature-version":"nxs-es384-v1","nxs-generation":"...","nxs-sequence":"...","idempotency-key":"...","nxs-signature":"..."},"body":"original JSON body"}
```

The body is the original signed string. The signature binds the same POST operation target, identity, generation, sequence and idempotency key used over HTTPS. Only the five operational methods are permitted; registration/completion remain HTTPS. An extension operation may continue to use its advertised HTTPS endpoint within the same lifecycle. Frames cannot supply another URL, method or platform metadata.

The response contains the request's idempotency key as `id`, the actual HTTP `status`, relevant response `headers` and original response `body` string. Both carriers enter the same handler and preserve its errors, retry hints and replay behavior. Socket-open, local send completion and ping/pong are not operation acknowledgments.

The current adapter bounds complete UTF-8 messages to 524,288 bytes, original bodies to the existing NXS body limit, and queued operations to four per connection. These are transport bounds, not permission to increase original operation limits. Literal `ping` receives `pong`; this verifies socket liveness only and never renews authorization or commits a heartbeat.

Ordinary hosts can use HTTPS or WSS. Assisted mode requires a live, authenticated, addressable WebSocket and bounded offer/answer/cancellation handling. An ordinary Worker socket cannot later be found by an unrelated request; an assisted socket must be owned by the designated Durable Object. Assisted wire integration remains unfinished and must not be advertised before it works.

## Failure and expiry

Reconnect after graceful close, abrupt loss or detected blackhole with bounded backoff and jitter. Reuse the same identity and reconcile non-repeatable operations. Do not replay expired assisted offers. Superseded sockets cannot change current state or deliver a result for a different attempt.

Cached authority has a fixed maximum age of five minutes. Reconnect, ping/pong and re-reading the same cached snapshot cannot extend it. Key publication/retirement must accommodate delayed visibility. Ordinary observations should avoid strong database commits; key and identity mutations still require their authoritative checks. The simplified adapter currently uses existing operation authentication; cached authentication/observations and cost validation remain open.

Reporting frequency, socket keepalive, authorization deadlines and UDP STUN refresh are independent. A long report interval never suspends needed UDP maintenance.

## Connectivity diagnostics

A probe uses normal-strength signed admission and Minecraft-compatible WebRTC settings to establish ICE, DTLS and SCTP. A small data-channel ping/pong is optional corroboration. No Minecraft login, diagnostic ownership installation protocol, dual-party completion journal or delivery receipt is required for a connectivity verdict. Record the attempted family/candidate revision, observed stage, timestamp and bounded failure reason. Preserve isolation from gameplay and resource limits.

The previous implementation is recoverable from Git history and the `archive/connectivity-control-20260915` branch. Remaining diagnostic/native adapters are being simplified to this scope; their presence is not a commitment to retain the experimental control wire formats.
