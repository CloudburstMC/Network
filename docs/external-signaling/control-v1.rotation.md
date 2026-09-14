# Machine rotation body for the optional control profile — draft

The opted-in `rotate` body is exactly `{version:1,newKeyId,publicKeyJwk,proof}`.
The public JWK is exactly `{crv:"P-384",kty:"EC",x,y}` with canonical 48-byte
base64url coordinates. `proof` is a canonical base64url raw 96-byte ES384 signature
by the candidate private key. The body limit is 4096 UTF-8 bytes.

The host generates a new opaque key ID with at least 128 cryptographically random
bits. It atomically persists that ID, the candidate private key, the stable
idempotency ID, the original complete operation body and its lifecycle intent
**before first transmission**. A lost acknowledgment therefore cannot lose the
credential ID needed to authenticate candidate-key status reconciliation.

Candidate possession proof signs UTF-8 JSON.stringify of exactly:

```text
["nethernet-control-machine-rotation-v1",1,audience,instanceId,generation,
 oldKeyId,newKeyId,candidatePublicKeyThumbprint,idempotencyKey]
```

The candidate thumbprint uses the existing canonical P-384 public-JWK thumbprint.
The old/current machine key separately authenticates the outer lifecycle delivery.
The provider checks that oldKeyId is the strongly current selected key, checks the
candidate proof in that exact context, and atomically commits the new credential,
selected key/revision, immutable receipt and authority publication event. Global
credential-ID conflict is a rejection, never an overwrite. Key IDs and selected
revision floors must survive retirement/deregistration so an old identity cannot
be reused. Candidate-key possession alone never authorizes the rotation.
An already committed retry first authenticates its current delivery key and
matches the immutable journaled intent; it returns the original receipt without
reapplying the fresh-mutation old-key predicate or issuing another credential.

After losing the acknowledgment, the host can issue strongly authenticated status
using the persisted candidate ID/key. It is accepted only if that credential became
current. An unauthorized candidate is not evidence that an earlier in-flight
rotation can no longer commit; retain the pending intent and reconcile using the
current authorized key. Unknown or transport errors never clear the journal.

This is a draft control-specific body/domain. The existing core `rotate` schema
and behavior remain unchanged until a route has explicitly negotiated this profile.
