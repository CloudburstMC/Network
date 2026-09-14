// Public test keys only. Independent Node ES384 vectors for the draft active-frame codec.
import assert from 'node:assert/strict';
import {createHash, createPrivateKey, createPublicKey, generateKeyPairSync, sign, verify} from 'node:crypto';
import {existsSync, readFileSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

const file = fileURLToPath(new URL('control-v1.frames.fixtures.json', import.meta.url));
const update = process.argv.includes('--write');
const b64 = value => Buffer.from(value).toString('base64url');
const digest = value => createHash('sha256').update(value).digest('base64url');
export function signingText(frame) {
  return JSON.stringify(['nethernet-control-frame-v1', 1, 'nxs-control-es384-v1', frame.direction,
    frame.audience, frame.type, frame.id, frame.sequence, frame.instanceId, frame.generation,
    frame.sessionId, frame.sessionEpoch, frame.connectionId, frame.capabilities, frame.sentAt,
    frame.expiresAt, frame.authentication.keyId, frame.payloadSha256]);
}

function testKey(keyId) {
  const pair = generateKeyPairSync('ec', {namedCurve: 'secp384r1'});
  return {keyId, publicKeyJwk: pair.publicKey.export({format: 'jwk'}),
    privateKeyJwk: pair.privateKey.export({format: 'jwk'}),
    privateKeyPkcs8: b64(pair.privateKey.export({format: 'der', type: 'pkcs8'}))};
}

let fixture;
if (update) {
  const keys = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')).keys : {
    machine: testKey('machine_test_01'), providerControl: testKey('provider_control_test_01')};
  const now = 1789400000000;
  const specifications = [
    ['host-lifecycle-unicode', 'host-to-provider', 'lifecycle.request', 'machine',
      ['request-response'], JSON.stringify({message: 'café 😀 \u2028 \u2029', escaped: '\n"\\', surrogate: '\ud800'})],
    ['provider-gameplay-offer', 'provider-to-host', 'assisted.offer', 'providerControl',
      ['addressed', 'assisted-gameplay', 'request-response'], JSON.stringify({kind: 'gameplay', attemptId: 'attempt_gameplay_01'})],
    ['provider-diagnostic-offer', 'provider-to-host', 'diagnostic.offer', 'providerControl',
      ['addressed', 'assisted-diagnostic', 'request-response'], JSON.stringify({kind: 'diagnostic', jobId: 'probe_job_01'})],
    ['host-empty-payload', 'host-to-provider', 'session.resync', 'machine', ['request-response'], '']
  ];
  const vectors = specifications.map(([name, direction, type, family, capabilities, payloadUtf8], index) => {
    const key = keys[family];
    const frame = {version: 1, type, id: `frame_vector_0000${index}`, sequence: index + 1, direction,
      audience: 'https://provider.example', instanceId: 'instance_test_01', generation: 3,
      sessionId: 'session_vector_0001', sessionEpoch: 7, connectionId: 'connection_vector_0001', capabilities,
      sentAt: now, expiresAt: now + 15000, payload: b64(Buffer.from(payloadUtf8, 'utf8')),
      payloadSha256: digest(Buffer.from(payloadUtf8, 'utf8')),
      authentication: {scheme: 'nxs-control-es384-v1', keyId: key.keyId, signature: ''}};
    const bytes = signingText(frame);
    frame.authentication.signature = sign('sha384', Buffer.from(bytes), {
      key: createPrivateKey({format: 'jwk', key: key.privateKeyJwk}), dsaEncoding: 'ieee-p1363'}).toString('base64url');
    return {name, keyFamily: family, payloadUtf8, frame, signingText: bytes,
      context: {now, authorityExpiresAt: now + 300000, clockSkewMillis: 1000,
        keyValidFrom: now - 300000, keyValidUntil: now + 600000}};
  });
  fixture = {format: 'nethernet-control-frame-fixtures-v1', warning: 'PUBLIC TEST KEYS; NEVER USE IN PRODUCTION', keys, vectors};
  writeFileSync(file, JSON.stringify(fixture, null, 2) + '\n');
} else {
  fixture = JSON.parse(readFileSync(file, 'utf8'));
}

for (const vector of fixture.vectors) {
  const frame = vector.frame, key = fixture.keys[vector.keyFamily];
  assert.equal(vector.signingText, signingText(frame), vector.name);
  assert.equal(frame.payload, b64(Buffer.from(vector.payloadUtf8, 'utf8')));
  assert.equal(frame.payloadSha256, digest(Buffer.from(vector.payloadUtf8, 'utf8')));
  const signature = Buffer.from(frame.authentication.signature, 'base64url');
  assert.equal(signature.length, 96);
  assert.equal(b64(signature), frame.authentication.signature);
  const publicKey = createPublicKey({key: key.publicKeyJwk, format: 'jwk'});
  assert(verify('sha384', Buffer.from(vector.signingText), {key: publicKey, dsaEncoding: 'ieee-p1363'}, signature), vector.name);
  assert(!verify('sha384', Buffer.from(vector.signingText + ' '), {key: publicKey, dsaEncoding: 'ieee-p1363'}, signature));
}

// Optional Java-produced output proves Java -> Node verification as well as Node -> Java fixtures.
const javaOutput = process.argv.find(argument => argument.startsWith('--java-output='));
if (javaOutput) {
  for (const result of JSON.parse(readFileSync(javaOutput.slice('--java-output='.length), 'utf8'))) {
    const vector = fixture.vectors.find(value => value.name === result.name);
    assert(vector);
    assert.equal(result.signingText, vector.signingText);
    assert(verify('sha384', Buffer.from(result.signingText), {
      key: createPublicKey({key: fixture.keys[vector.keyFamily].publicKeyJwk, format: 'jwk'}), dsaEncoding: 'ieee-p1363'
    }, Buffer.from(result.signature, 'base64url')));
  }
}
console.log(`Verified ${fixture.vectors.length} draft control-frame vectors${javaOutput ? ' and Java signatures' : ''}.`);
