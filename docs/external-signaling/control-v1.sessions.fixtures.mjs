// Independent Node proof vectors. All bundled private keys are PUBLIC TEST KEYS.
import assert from 'node:assert/strict';
import {createHash, createPrivateKey, createPublicKey, generateKeyPairSync, sign, verify} from 'node:crypto';
import {existsSync, readFileSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

const path = fileURLToPath(new URL('control-v1.sessions.fixtures.json', import.meta.url));
const b64 = value => Buffer.from(value).toString('base64url');
const hash = value => createHash('sha256').update(value).digest('base64url');
const scheme = 'nxs-control-es384-v1';
const intentDigest = value => hash(JSON.stringify(['nethernet-control-lifecycle-intent-v1', 1, value.audience,
  value.operation, value.instanceId, value.generation, value.sequence, value.idempotencyKey, value.payloadSha256]));
export const requestIntentDigest = r => hash(JSON.stringify(['nethernet-control-session-intent-v1', 1, r.action,
  r.audience, r.instanceId, r.generation, r.requestId, r.payloadSha256]));
export const requestSigning = r => JSON.stringify(['nethernet-control-session-request-v1', 1, scheme, r.action,
  r.audience, r.method, r.encodedPathAndQuery, r.requestId, r.instanceId, r.generation, r.sentAt, r.expiresAt,
  r.authentication.keyId, r.payloadSha256]);
export const responseSigning = r => JSON.stringify(['nethernet-control-session-response-v1', 1, scheme, r.kind,
  r.audience, r.requestId, r.requestIntentDigest, r.instanceId, r.generation, r.sentAt, r.expiresAt,
  r.authentication.keyId, r.payloadSha256]);
export const httpSigning = r => JSON.stringify(['nethernet-control-http-request-v1', 1, scheme, r.audience, r.method,
  r.encodedPathAndQuery, r.sentAt, r.expiresAt, r.intent.instanceId, r.intent.generation, r.sessionId, r.sessionEpoch,
  r.connectionId, r.writerTransport, r.capabilities, r.authentication.keyId, intentDigest(r.intent), r.intent.payloadSha256]);
const signingFor = {request: requestSigning, response: responseSigning, http: httpSigning};

let fixtures;
if (process.argv.includes('--write')) {
  let keys = existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')).keys
    : JSON.parse(readFileSync(new URL('control-v1.frames.fixtures.json', import.meta.url), 'utf8')).keys;
  if (!keys.machineCandidate) {
    const pair = generateKeyPairSync('ec', {namedCurve: 'secp384r1'});
    keys.machineCandidate = {keyId: 'machine_candidate_test_02', publicKeyJwk: pair.publicKey.export({format: 'jwk'}),
      privateKeyJwk: pair.privateKey.export({format: 'jwk'}), privateKeyPkcs8: b64(pair.privateKey.export({format: 'der', type: 'pkcs8'}))};
  }
  const now = 1789400000000, duration = 21600000, audience = 'https://provider.example', instanceId = 'instance_test_01';
  const capabilities = ['addressed', 'assisted-gameplay', 'request-response'];
  const currentWriter = {transport: 'websocket', sessionEpoch: 7, sessionId: 'session_vector_0001',
    connectionId: 'connection_vector_0001', keyId: keys.machine.keyId, machineKeyRevision: 2};
  const vectors = [];
  const signature = (value, family, text) => sign('sha384', Buffer.from(text), {
    key: createPrivateKey({format: 'jwk', key: keys[family].privateKeyJwk}), dsaEncoding: 'ieee-p1363'}).toString('base64url');
  function add(kind, name, envelope, family, extra = {}) {
    const signingText = signingFor[kind](envelope);
    envelope.authentication.signature = signature(envelope, family, signingText);
    const vector = {kind, name, keyFamily: family, envelope, signingText, ...extra};
    if (kind === 'request') vector.requestIntentDigest = requestIntentDigest(envelope);
    vectors.push(vector);
    return vector;
  }
  function request(name, action, payload, family = 'machine') {
    const payloadUtf8 = JSON.stringify(payload);
    return add('request', name, {version: 1, action, requestId: `request_${name.replaceAll('-', '_')}_01`, audience,
      method: action === 'upgrade' ? 'GET' : 'POST', encodedPathAndQuery: `/control/${action}?protocol=1`, instanceId,
      generation: 3, sentAt: now, expiresAt: now + 30000, payload: b64(payloadUtf8), payloadSha256: hash(payloadUtf8),
      authentication: {scheme, keyId: keys[family].keyId, signature: ''}}, family, {payloadUtf8});
  }
  function response(name, kind, requestVector, payload, expiry = now + 30000) {
    const payloadUtf8 = JSON.stringify(payload), request = requestVector.envelope;
    return add('response', name, {version: 1, kind, requestId: request.requestId, requestIntentDigest: requestIntentDigest(request),
      audience, instanceId, generation: 3, sentAt: now, expiresAt: expiry, payload: b64(payloadUtf8), payloadSha256: hash(payloadUtf8),
      authentication: {scheme, keyId: keys.providerControl.keyId, signature: ''}}, 'providerControl', {requestName: requestVector.name, payloadUtf8});
  }
  const prepare = request('prepare-ws', 'prepare', {transport: 'websocket', capabilities, clientNonce: 'client_nonce_vector_01',
    expectedWriter: currentWriter, sessionDurationMillis: duration, intentCreatedAt: now, intentExpiresAt: now + 60000});
  const preparedPayload = {pendingSessionId: 'session_candidate_0002', transport: 'websocket', capabilities,
    clientNonce: 'client_nonce_vector_01', connectionId: null, expectedWriter: currentWriter,
    intentDigest: requestIntentDigest(prepare.envelope), preparedAt: now, expiresAt: now + 60000, sessionDurationMillis: duration};
  const prepared = response('prepared-ws', 'prepared', prepare, preparedPayload, now + 60000);
  const preparedWire = JSON.stringify(prepared.envelope), preparedProof = b64(preparedWire);
  const activatedWriter = {...currentWriter, sessionEpoch: 8, sessionId: preparedPayload.pendingSessionId, connectionId: 'connection_candidate_0001'};
  for (let candidate = 1; candidate <= 2; candidate++) {
    const upgrade = request(`upgrade-${candidate}`, 'upgrade', {preparedProof});
    const challenge = response(`challenge-${candidate}`, 'connection-challenge', upgrade, {pendingSessionId: preparedPayload.pendingSessionId,
      transport: 'websocket', capabilities, clientNonce: preparedPayload.clientNonce, connectionId: `connection_candidate_000${candidate}`,
      preparedProofSha256: hash(preparedWire), expiresAt: preparedPayload.expiresAt, sessionDurationMillis: duration});
    const activate = request(`activate-${candidate}`, 'activate', {expectedWriter: currentWriter, preparedProof,
      connectionProof: b64(JSON.stringify(challenge.envelope))});
    if (candidate === 1) response('activated-ws', 'activated', activate, {intentDigest: requestIntentDigest(activate.envelope),
      writer: activatedWriter, capabilities, activatedAt: now + 100, sessionExpiresAt: now + 100 + duration,
      authoritySourceCheckedAt: now, authorityExpiresAt: now + 300000});
  }
  const prepareHttp = request('prepare-https', 'prepare', {transport: 'https', capabilities: ['request-response'],
    clientNonce: 'http_client_nonce_0001', expectedWriter: activatedWriter, sessionDurationMillis: duration, intentCreatedAt: now, intentExpiresAt: now + 60000});
  const preparedHttp = response('prepared-https', 'prepared', prepareHttp, {pendingSessionId: 'http_session_candidate_0001',
    transport: 'https', capabilities: ['request-response'], clientNonce: 'http_client_nonce_0001', connectionId: 'http_writer_candidate_0001',
    expectedWriter: activatedWriter, intentDigest: requestIntentDigest(prepareHttp.envelope), preparedAt: now,
    expiresAt: now + 60000, sessionDurationMillis: duration}, now + 60000);
  const activateHttp = request('activate-https', 'activate', {expectedWriter: activatedWriter,
    preparedProof: b64(JSON.stringify(preparedHttp.envelope)), connectionProof: null});
  const httpWriter = {transport: 'https', sessionEpoch: 9, sessionId: 'http_session_candidate_0001', connectionId: 'http_writer_candidate_0001',
    keyId: currentWriter.keyId, machineKeyRevision: currentWriter.machineKeyRevision};
  response('activated-https', 'activated', activateHttp, {intentDigest: requestIntentDigest(activateHttp.envelope), writer: httpWriter,
    capabilities: ['request-response'], activatedAt: now + 100, sessionExpiresAt: now + 100 + duration,
    authoritySourceCheckedAt: now, authorityExpiresAt: now + 300000});
  const status = request('current-writer', 'status', {query: 'current-writer'}, 'machineCandidate');
  const rotatedWriter = {...currentWriter, keyId: keys.machineCandidate.keyId, machineKeyRevision: 3};
  response('rotated-current-writer', 'status', status, {query: 'current-writer', writer: rotatedWriter, capabilities,
    activatedAt: now - 1000, sessionExpiresAt: now - 1000 + duration, authoritySourceCheckedAt: now, authorityExpiresAt: now + 300000});
  const unknown = request('unknown-intent', 'status', {query: 'intent-receipt', intentDigest: hash('unknown-intent')}, 'machineCandidate');
  response('unknown-intent-result', 'status', unknown, {query: 'intent-receipt', intentDigest: hash('unknown-intent'), receipt: null});
  const bodyUtf8 = ' {"heartbeat":"café 😀 \\u2028", "desired":false}\n';
  const intent = {version: 1, audience, operation: 'heartbeat', instanceId, generation: 3, sequence: 11,
    idempotencyKey: 'operation_intent_vector_01', payloadSha256: hash(bodyUtf8)};
  for (const [name, writer, family, caps] of [['http-over-ws', currentWriter, 'machine', capabilities],
    ['http-after-key-rotation', rotatedWriter, 'machineCandidate', capabilities],
    ['http-persistent-fallback', httpWriter, 'machine', ['request-response']]]) {
    add('http', name, {version: 1, audience, method: 'POST', encodedPathAndQuery: '/signal/heartbeat?version=1', sentAt: now,
      expiresAt: now + 15000, intent, sessionId: writer.sessionId, sessionEpoch: writer.sessionEpoch, connectionId: writer.connectionId,
      writerTransport: writer.transport, capabilities: caps, authentication: {scheme, keyId: keys[family].keyId, signature: ''}}, family,
    {bodyUtf8, intentDigest: intentDigest(intent), writer});
  }
  fixtures = {format: 'nethernet-control-session-fixtures-v1', warning: 'PUBLIC TEST KEYS; NEVER USE IN PRODUCTION',
    now, authorityExpiresAt: now + 300000, keyValidFrom: now - 300000, keyValidUntil: now + 86400000,
    keys, currentWriter, activatedWriter, vectors};
  writeFileSync(path, JSON.stringify(fixtures, null, 2) + '\n');
} else fixtures = JSON.parse(readFileSync(path, 'utf8'));

for (const vector of fixtures.vectors) {
  const envelope = vector.envelope, text = signingFor[vector.kind](envelope), key = fixtures.keys[vector.keyFamily];
  assert.equal(text, vector.signingText, vector.name);
  if (vector.kind === 'http') {
    assert.equal(envelope.intent.payloadSha256, hash(vector.bodyUtf8));
    assert.equal(vector.intentDigest, intentDigest(envelope.intent));
  } else {
    assert.equal(envelope.payload, b64(vector.payloadUtf8));
    assert.equal(envelope.payloadSha256, hash(vector.payloadUtf8));
    assert(Buffer.byteLength(vector.payloadUtf8) <= 8192, `payload limit ${vector.name}`);
    if (vector.kind === 'request') assert.equal(vector.requestIntentDigest, requestIntentDigest(envelope));
  }
  assert(Buffer.byteLength(JSON.stringify(envelope)) <= 16384, `envelope limit ${vector.name}`);
  const signature = Buffer.from(envelope.authentication.signature, 'base64url');
  assert.equal(signature.length, 96);
  assert(verify('sha384', Buffer.from(text), {key: createPublicKey({format: 'jwk', key: key.publicKeyJwk}), dsaEncoding: 'ieee-p1363'}, signature), vector.name);
}
const javaOutput = process.argv.find(value => value.startsWith('--java-output='));
if (javaOutput) for (const result of JSON.parse(readFileSync(javaOutput.slice('--java-output='.length), 'utf8'))) {
  const vector = fixtures.vectors.find(value => value.name === result.name);
  assert(vector);
  assert.equal(result.signingText, vector.signingText);
  assert(verify('sha384', Buffer.from(result.signingText), {key: createPublicKey({format: 'jwk', key: fixtures.keys[vector.keyFamily].publicKeyJwk}),
    dsaEncoding: 'ieee-p1363'}, Buffer.from(result.signature, 'base64url')), result.name);
}
console.log(`Verified ${fixtures.vectors.length} draft bootstrap/HTTP vectors${javaOutput ? ' and Java signatures' : ''}.`);
