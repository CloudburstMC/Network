package org.cloudburstmc.netty.signaling.control;

import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signaling.ProviderCrypto;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.List;
import java.util.Optional;

/** One exclusively owned durable journal. commit must finish durably before the coordinator sends anything. */
public interface ControlClientJournal extends AutoCloseable {
    Optional<Snapshot> read() throws IOException;
    void commit(Snapshot snapshot) throws IOException;
    @Override void close() throws IOException;

    record Subject(String audience, String instanceId, long generation) {
        public Subject { ControlOrigin.requireCanonical(audience); ControlJson.identifier(instanceId); ControlJson.safe(generation, true); }
    }

    /** Private material is written only to the protected journal, never a transport body or diagnostic. */
    record Credential(String keyId, String publicKeyJwk, String privateKeyPkcs8) {
        public Credential {
            ControlJson.identifier(keyId);
            JsonObject jwk = ControlJson.parse(publicKeyJwk, 1024); ControlJson.fields(jwk, "crv", "kty", "x", "y");
            ControlJson.base64(privateKeyPkcs8, 4096, false);
            try {
                PrivateKey key = ProviderCrypto.privateKey(privateKeyPkcs8);
                String challenge = "nethernet-control-journal-key-check-v1";
                if (!ProviderCrypto.verify(jwk, ProviderCrypto.sign(key, challenge), challenge)) throw ControlJson.invalid("journal key pair");
            } catch (GeneralSecurityException failure) { throw new IllegalArgumentException("Invalid control journal key", failure); }
        }
        public static Credential from(String keyId, KeyPair pair) {
            return new Credential(keyId, ProviderCrypto.publicJwk(pair.getPublic()).toString(), ProviderCrypto.base64(pair.getPrivate().getEncoded()));
        }
        public KeyPair keyPair() throws GeneralSecurityException { return new KeyPair(ProviderCrypto.publicKey(ControlJson.parse(publicKeyJwk, 1024)), ProviderCrypto.privateKey(privateKeyPkcs8)); }
        @Override public String toString() { return "Credential[keyId=" + keyId + ", privateKey=redacted]"; }
    }

    /** Receipt is retained while a committed rotation is awaiting strongly current key/writer reconciliation. */
    record Pending(ControlLifecycleCodec.Intent intent, String originalBody, Credential candidate, ControlLifecycleCodec.Receipt receipt) {
        public Pending {
            ControlLifecycleCodec.verifyBody(intent, ControlJson.base64(originalBody, ControlLifecycleCodec.MAX_HTTP_BODY_BYTES, true));
            if (candidate != null && !intent.operation().equals("rotate")) throw ControlJson.invalid("candidate outside rotation");
            if (receipt != null) ControlLifecycleCodec.verifyReceipt(receipt, intent);
        }
        public byte[] bodyBytes() { return ControlJson.base64(originalBody, ControlLifecycleCodec.MAX_HTTP_BODY_BYTES, true); }
        @Override public String toString() { return "Pending[operation=" + intent.operation() + ", sequence=" + intent.sequence() + ", body=redacted]"; }
    }

    /** Exact signed prepare/activate request; retry updates its delivery signature/time, not its original payload. */
    record Bootstrap(String originalRequest) {
        public Bootstrap {
            var request = ControlSessionCodec.decodeRequest(originalRequest);
            if (!request.action().equals("prepare") && !request.action().equals("activate")) throw ControlJson.invalid("journal bootstrap action");
        }
        @Override public String toString() { return "Bootstrap[originalRequest=redacted]"; }
    }

    /** Persisted grants never establish readiness after restart; the live coordinator must resynchronize. */
    record Grant(List<String> capabilities, long activatedAt, long sessionExpiresAt, long authoritySourceCheckedAt, long authorityExpiresAt) {
        public Grant {
            capabilities = List.copyOf(capabilities); ControlProof.capabilities("websocket", capabilities);
            for (long time : new long[]{activatedAt, sessionExpiresAt, authoritySourceCheckedAt, authorityExpiresAt}) ControlJson.safe(time, false);
            if (sessionExpiresAt <= activatedAt || sessionExpiresAt - activatedAt > ControlSessionPayloadCodec.MAX_SESSION_DURATION_MILLIS
                    || authorityExpiresAt <= authoritySourceCheckedAt || authorityExpiresAt > sessionExpiresAt) throw ControlJson.invalid("journal grant");
        }
    }

    /** A retained signed response supplies rollback floors only; it cannot restore live authority after restart. */
    record AuthorityFloor(String originalResponse) {
        public AuthorityFloor { ControlAuthorityCodec.decodeResponse(originalResponse); }
        public ControlAuthorityCodec.Floor value() {
            var response = ControlAuthorityCodec.decodeResponse(originalResponse);
            return new ControlAuthorityCodec.Floor(response.audience(), response.instanceId(), response.generation(), response.source(),
                    response.writer(), response.capabilities(), response.subjectExpiresAt(), response.permissions(), response.state());
        }
        public void requireAtLeast(AuthorityFloor previous) {
            if (previous != null) ControlAuthorityCodec.checkFloor(ControlAuthorityCodec.decodeResponse(originalResponse), previous.value());
        }
        @Override public String toString() { return "AuthorityFloor[source=" + value().source().sourceId() + ", revision=" + value().source().sourceRevision() + "]"; }
    }

    record Snapshot(Subject subject, Credential currentKey, ControlWriterFence writer, long lastSequence, Pending pending,
                    Bootstrap pendingBootstrap, Grant grant, AuthorityFloor authorityFloor) {
        /** Initial/pre-authority snapshots only; a durable journal rejects removing an already retained floor. */
        public Snapshot(Subject subject, Credential currentKey, ControlWriterFence writer, long lastSequence, Pending pending,
                        Bootstrap pendingBootstrap, Grant grant) {
            this(subject, currentKey, writer, lastSequence, pending, pendingBootstrap, grant, null);
        }
        public Snapshot {
            ControlJson.safe(lastSequence, false);
            if (!writer.keyId().equals(currentKey.keyId())) throw ControlJson.invalid("journal selected key");
            if (grant != null) ControlProof.capabilities(writer.transport(), grant.capabilities());
            if (authorityFloor != null) {
                var floor = authorityFloor.value();
                if (!floor.audience().equals(subject.audience()) || !floor.instanceId().equals(subject.instanceId())
                        || floor.generation() > subject.generation()) throw ControlJson.invalid("journal authority floor scope");
            }
            if (pending != null) {
                var intent = pending.intent();
                if (!intent.audience().equals(subject.audience()) || !intent.instanceId().equals(subject.instanceId())
                        || intent.generation() != subject.generation() || intent.sequence() != lastSequence) throw ControlJson.invalid("journal pending subject or sequence");
                if (pending.candidate() != null) {
                    if (pending.candidate().keyId().equals(currentKey.keyId())) throw ControlJson.invalid("journal reused candidate ID");
                    var context = new ControlRotationCodec.Context(subject.audience(), subject.instanceId(), subject.generation(), currentKey.keyId(), intent.idempotencyKey());
                    var rotation = ControlRotationCodec.verify(new String(pending.bodyBytes(), java.nio.charset.StandardCharsets.UTF_8), context);
                    if (!rotation.newKeyId().equals(pending.candidate().keyId())
                            || !ProviderCrypto.thumbprint(ControlJson.parse(rotation.publicKeyJwk(), 1024)).equals(ProviderCrypto.thumbprint(ControlJson.parse(pending.candidate().publicKeyJwk(), 1024)))) {
                        throw ControlJson.invalid("journal candidate association");
                    }
                }
            }
            if (pendingBootstrap != null) {
                var request = ControlSessionCodec.decodeRequest(pendingBootstrap.originalRequest());
                if (!request.audience().equals(subject.audience()) || !request.instanceId().equals(subject.instanceId())
                        || request.generation() != subject.generation()) throw ControlJson.invalid("journal bootstrap subject");
            }
        }
    }
}
