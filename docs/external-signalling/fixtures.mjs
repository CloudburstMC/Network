// Public fixture keys only. Node's independent crypto implementation verifies JVM conformance.
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash, createHmac, createCipheriv, createPrivateKey, createPublicKey, sign, verify } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const protocol = 'nethernet-external-signalling-v1', signature = 'nxs-es384-v1';
const path = name => fileURLToPath(new URL(name, import.meta.url));
const read = name => JSON.parse(readFileSync(path(name), 'utf8'));
const write = (name, value) => writeFileSync(path(name), JSON.stringify(value, null, 2) + '\n');
const digest = value => createHash('sha256').update(value).digest();
const b64 = value => value.toString('base64url');
const hmac = (key, data) => createHmac('sha256', key).update(data).digest();
const update = process.argv.includes('--write');

const f = read('nxs-v1.fixtures.json'), c = f.challenge;
f.protocol = c.protocol = protocol; c.signature = signature; c.context.profile = 'nxs-admission-v1';
c.thumbprint = b64(digest(JSON.stringify(Object.fromEntries(['crv', 'kty', 'x', 'y'].map(k => [k, f.publicKeyJwk[k]])))));
c.contextDigest = b64(digest(JSON.stringify(['mode','profile','label','authorizationId','serviceId','region','pool','registrationId','tagsDigest'].filter(k => k in c.context).map(k => c.context[k]))));
const proof = JSON.stringify([protocol,'complete',c.audience,c.challengeId,c.nonce,c.thumbprint,c.contextDigest,c.expiresAt,f.proofNonce,f.idempotencyKey]);
const i = f.request.input;
const request = JSON.stringify([protocol,signature,i.audience,i.method,i.path,i.timestamp,i.instanceId,i.keyId,i.idempotencyKey,i.generation,i.sequence,b64(digest(i.body))]);
const pub = createPublicKey({ key: f.publicKeyJwk, format: 'jwk' });
for (const [name, payload] of [['proof',proof],['request',request]]) {
  if (update) {
    f[name].payload = payload;
    f[name].signature = b64(sign('sha384', Buffer.from(payload), { key: createPrivateKey({key:f.privateKeyJwk,format:'jwk'}), dsaEncoding:'ieee-p1363' }));
  }
  assert.equal(f[name].payload, payload);
  assert(verify('sha384', Buffer.from(payload), {key:pub,dsaEncoding:'ieee-p1363'}, Buffer.from(f[name].signature,'base64url')));
}
if (update) write('nxs-v1.fixtures.json', f);

const v = read('stateless-admission-v1.fixtures.json'), claims = v.claims, context = v.context;
const plain = Buffer.alloc(67 + claims.clientIcePwd.length);
plain.writeUInt32BE(claims.expiresAt / 1000); Buffer.from(claims.clientFingerprintHex,'hex').copy(plain,4);
plain.writeUInt16BE(claims.clientSctpPort,36); plain.writeUInt32BE(claims.clientMaxMessageSize,38);
Buffer.from(claims.callerContextHashHex,'hex').copy(plain,42); plain.writeBigUInt64BE(BigInt(claims.networkId),58);
plain[66] = claims.clientIcePwd.length; plain.write(claims.clientIcePwd,67,'ascii');
const nonce = Buffer.from(v.nonceHex,'hex'), header = 'NXS1' + context.keyId;
const key = hmac(context.secret, `nxs-stateless-aead-v1\0${context.audience}`);
const cipher = createCipheriv('aes-256-gcm', key, nonce);
cipher.setAAD(Buffer.from(`nxs-stateless-admission-v1\0${header}\0${context.audience}\0${v.clientIceUfrag}`));
const encrypted = Buffer.concat([cipher.update(plain), cipher.final(), cipher.getAuthTag()]); plain.fill(0);
const localUfrag = header + Buffer.concat([nonce,encrypted]).toString('base64').replaceAll('=','');
const icePwd = hmac(context.secret,`nxs-stateless-ice-v1\0${context.audience}\0${localUfrag}`).subarray(0,24).toString('base64');
const expected = {localUfrag,icePwd,ufragLength:localUfrag.length};
if (update) { v.expected = expected; write('stateless-admission-v1.fixtures.json',v); }
assert.deepEqual(v.expected,expected);
const provenance = {specification:'urn:nethernet:external-signalling:v1', files:Object.fromEntries(['stateless-admission-v1.fixtures.json','cloudburst-protocol-vectors.v1.json'].map(name => [name,digest(readFileSync(path(name))).toString('hex')]))};
if (update) write('provenance.json',provenance);
assert.deepEqual(read('provenance.json'),provenance);
console.log('NXS canonical signing, stateless encryption, and fixture hashes verified.');
