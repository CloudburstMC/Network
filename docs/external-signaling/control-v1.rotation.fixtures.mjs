import assert from 'node:assert/strict';
import {createHash, createPrivateKey, createPublicKey, sign, verify} from 'node:crypto';
import {readFileSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
const file = fileURLToPath(new URL('control-v1.rotation.fixtures.json', import.meta.url));
const hash = text => createHash('sha256').update(text).digest('base64url');
export function rotationSigningText(body, context) {
  const {crv,kty,x,y} = body.publicKeyJwk;
  return JSON.stringify(['nethernet-control-machine-rotation-v1', 1, context.audience, context.instanceId, context.generation,
    context.oldKeyId, body.newKeyId, hash(JSON.stringify({crv,kty,x,y})), context.idempotencyKey]);
}
let fixture;
if (process.argv.includes('--write')) {
  const key = JSON.parse(readFileSync(new URL('control-v1.sessions.fixtures.json', import.meta.url), 'utf8')).keys.machineCandidate;
  const vectors = ['https://provider.example', 'https://[2001:db8::1]:8443'].map((audience, index) => {
    const context = {audience, instanceId:'instance_test_01', generation:3, oldKeyId:'machine_test_01', idempotencyKey:`rotation_intent_000${index}`};
    const {crv,kty,x,y} = key.publicKeyJwk;
    const body = {version:1, newKeyId:`replacement_key_000${index}`, publicKeyJwk:{crv,kty,x,y}, proof:''};
    const signingText = rotationSigningText(body, context);
    body.proof = sign('sha384', Buffer.from(signingText), {key:createPrivateKey({format:'jwk',key:key.privateKeyJwk}),dsaEncoding:'ieee-p1363'}).toString('base64url');
    return {name:`rotation-${index}`,context,body,signingText};
  });
  fixture = {format:'nethernet-control-rotation-fixtures-v1',warning:'PUBLIC TEST KEY; NEVER USE IN PRODUCTION',key,vectors};
  writeFileSync(file,JSON.stringify(fixture,null,2)+'\n');
} else fixture=JSON.parse(readFileSync(file,'utf8'));
for (const vector of fixture.vectors) {
  assert.equal(rotationSigningText(vector.body,vector.context),vector.signingText);
  assert(verify('sha384',Buffer.from(vector.signingText),{key:createPublicKey({format:'jwk',key:fixture.key.publicKeyJwk}),dsaEncoding:'ieee-p1363'},Buffer.from(vector.body.proof,'base64url')));
}
const output=process.argv.find(value=>value.startsWith('--java-output='));
if(output) for(const result of JSON.parse(readFileSync(output.slice('--java-output='.length),'utf8'))) {
  const vector=fixture.vectors.find(value=>value.name===result.name); assert(vector); assert.equal(result.signingText,vector.signingText);
  assert(verify('sha384',Buffer.from(result.signingText),{key:createPublicKey({format:'jwk',key:fixture.key.publicKeyJwk}),dsaEncoding:'ieee-p1363'},Buffer.from(result.proof,'base64url')));
}
console.log(`Verified ${fixture.vectors.length} control-rotation vectors${output?' and Java signatures':''}.`);
