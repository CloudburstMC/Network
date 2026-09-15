// Independent Node signing bytes; PUBLIC TEST KEYS, never production credentials.
import assert from 'node:assert/strict';
import {createHash, createPrivateKey, createPublicKey, sign, verify} from 'node:crypto';
import {readFileSync, writeFileSync} from 'node:fs';
const path = new URL('control-v1.authority.fixtures.json', import.meta.url);
const scheme = 'nxs-control-es384-v1';
const writer = v => [v.transport, v.sessionEpoch, v.sessionId, v.connectionId, v.keyId, v.machineKeyRevision];
const text = v => JSON.stringify(v.kind === 'authority-request'
  ? ['nethernet-control-authority-request-v1', 1, scheme, v.audience, v.method, v.encodedPathAndQuery,
    v.requestId, v.instanceId, v.generation, ...writer(v.writer), v.capabilities, v.sentAt, v.expiresAt, v.authorityNotAfter, v.authentication.keyId]
  : ['nethernet-control-authority-response-v1', 1, scheme, v.audience, v.requestId, v.requestDigest, v.instanceId, v.generation,
    ...writer(v.writer), v.capabilities, v.sourceId, v.sourceRevision, v.sourceWatermark, v.sourceCheckedAt, v.sourceExpiresAt,
    v.subjectExpiresAt, v.authorityExpiresAt, v.permissions, v.state.desiredRevision, v.state.desiredState, v.state.appliedBasisSha256, v.sentAt, v.expiresAt, v.authentication.keyId]);
const digest = value => createHash('sha256').update(value).digest('base64url');
let fixture;
if (process.argv.includes('--write')) {
  const {keys} = JSON.parse(readFileSync(new URL('control-v1.sessions.fixtures.json', import.meta.url), 'utf8'));
  const now = 1800000000000, vectors = [];
  const add = (name, envelope, keyFamily, requestName) => {
    const signingText = text(envelope);
    envelope.authentication.signature = sign('sha384', Buffer.from(signingText), {
      key: createPrivateKey({format: 'jwk', key: keys[keyFamily].privateKeyJwk}), dsaEncoding: 'ieee-p1363'
    }).toString('base64url');
    const value = {name, keyFamily, envelope, signingText};
    if (requestName) value.requestName = requestName;
    else value.requestDigest = digest(signingText);
    vectors.push(value); return value;
  };
  for (const mode of ['ws', 'assisted', 'https', 'ipv6']) {
    const transport = mode === 'https' ? 'https' : 'websocket';
    const capabilities = mode === 'assisted' ? ['addressed', 'assisted-diagnostic', 'assisted-gameplay', 'request-response'] : ['request-response'];
    const envelope = {version: 1, kind: 'authority-request', requestId: `authority_${mode}_request_0001`,
      audience: mode === 'ipv6' ? 'https://[2001:db8::1]:8443' : 'https://agent.warden.cloud',
      instanceId: 'game-server-1', generation: 3,
      writer: {transport, sessionEpoch: 7, sessionId: 'session_control_0001', connectionId: 'connection_control_0001', keyId: keys.machine.keyId, machineKeyRevision: 2},
      capabilities, method: 'POST', encodedPathAndQuery: '/control/authority?profile=1', sentAt: now, expiresAt: now + 30000,
      authorityNotAfter: now + 300000, authentication: {scheme, keyId: keys.machine.keyId, signature: ''}};
    const request = add(`${mode}-request`, envelope, 'machine');
    const {method: _method, encodedPathAndQuery: _path, authorityNotAfter: _bound, ...common} = envelope;
    add(`${mode}-response`, {...common, kind: 'authority-response', requestDigest: request.requestDigest,
      sourceId: 'control:p17', sourceRevision: 42, sourceWatermark: 1001, sourceCheckedAt: now - 1000, sourceExpiresAt: now + 299000,
      subjectExpiresAt: now + 900000, authorityExpiresAt: now + 180000,
      state: {desiredRevision: mode === "ipv6" ? 9007199254740991 : 12,
        desiredState: mode === "https" ? "draining" : mode === "ipv6" ? "closed" : "serving",
        appliedBasisSha256: mode === "ws" ? null : digest(`state_${mode}`)},
      permissions: mode === 'assisted' ? ['control.assisted', 'control.status'] : ['control.status'],
      authentication: {scheme, keyId: keys.providerControl.keyId, signature: ''}}, 'providerControl', request.name);
  }
  fixture = {format: 'nethernet-control-authority-fixtures-v1', warning: 'PUBLIC TEST KEYS; NEVER USE IN PRODUCTION',
    now, sessionExpiresAt: now + 1800000, keyValidFrom: now - 60000, keyValidUntil: now + 3600000, keys, vectors};
  writeFileSync(path, JSON.stringify(fixture, null, 2) + '\n');
} else fixture = JSON.parse(readFileSync(path, 'utf8'));
for (const vector of fixture.vectors) {
  assert.equal(text(vector.envelope), vector.signingText);
  if (vector.requestDigest) assert.equal(digest(vector.signingText), vector.requestDigest);
  assert(verify('sha384', Buffer.from(vector.signingText), {key: createPublicKey({format: 'jwk', key: fixture.keys[vector.keyFamily].publicKeyJwk}),
    dsaEncoding: 'ieee-p1363'}, Buffer.from(vector.envelope.authentication.signature, 'base64url')));
}
const output = process.argv.find(value => value.startsWith('--java='));
if (output) for (const value of JSON.parse(readFileSync(output.slice(7), 'utf8'))) {
  const vector = fixture.vectors.find(vector => vector.name === value.name);
  assert(vector); assert.equal(value.signingText, vector.signingText);
  assert(verify('sha384', Buffer.from(value.signingText), {key: createPublicKey({format: 'jwk', key: fixture.keys[vector.keyFamily].publicKeyJwk}),
    dsaEncoding: 'ieee-p1363'}, Buffer.from(value.signature, 'base64url')));
}
console.log(`Verified ${fixture.vectors.length} independent authority proof vectors${output ? ' and Java signatures' : ''}.`);
