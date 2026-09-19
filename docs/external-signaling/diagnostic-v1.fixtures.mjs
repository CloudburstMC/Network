// Independent Node verification of the shared NXS1 diagnostic envelope.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createDecipheriv, createHash, createHmac } from "node:crypto";
const fixture=JSON.parse(readFileSync(new URL("diagnostic-v1.fixtures.json",import.meta.url),"utf8"));
const audience=`nxs-stateless-host-v1/${fixture.context.incarnation}`;
const hmac=value=>createHmac("sha256",Buffer.from(fixture.key.secret)).update(value).digest();
const key=hmac(`nxs-stateless-aead-v1\0${audience}`);
for(const v of fixture.vectors) {
  const c=v.claims, header="NXS1"+fixture.key.keyId, envelope=Buffer.from(v.localUfrag.slice(8),"base64");
  assert(v.localUfrag.startsWith(header));
  assert.equal(envelope.toString("base64").replace(/=+$/,""),v.localUfrag.slice(8));
  const decipher=createDecipheriv("aes-256-gcm",key,envelope.subarray(0,12));
  decipher.setAAD(Buffer.from(`nxs-stateless-admission-v1\0${header}\0${audience}\0${v.remoteUfrag}`));
  decipher.setAuthTag(envelope.subarray(-16));
  const p=Buffer.concat([decipher.update(envelope.subarray(12,-16)),decipher.final()]);
  assert.equal(p.readUInt32BE(0)*1000,c.expiresAt); assert.equal(p.subarray(4,36).toString("hex"),c.clientFingerprintHex);
  assert.equal(p.readUInt16BE(36),5000);assert.equal(p.readUInt32BE(38),262144);
  assert.equal(p.subarray(42,58).toString("hex"),c.attemptIdHex);assert.equal(p.readBigUInt64BE(58),0n);
  const tail=67+p[66]; assert.equal(p.subarray(67,tail).toString(),c.clientIcePwd); assert.equal(p.length,tail+59);
  assert.equal(p.subarray(tail,tail+32).toString("hex"),c.offerDigestHex);
  assert.equal(createHash("sha256").update(v.offer).digest("hex"),c.offerDigestHex);
  assert.equal(Number(p.readBigUInt64BE(tail+32)),c.candidateRevision);assert.equal(p[tail+40],c.profile|(c.family===6?128:0));
  assert.equal(p.subarray(tail+41,tail+57).toString("hex"),c.targetAddressHex);assert.equal(p.readUInt16BE(tail+57),c.targetPort);
  assert.equal(hmac(`nxs-stateless-ice-v1\0${audience}\0${v.localUfrag}`).subarray(0,24).toString("base64"),v.icePwd);
  assert.equal(v.localUfrag.length,8+Math.ceil((154+c.clientIcePwd.length)*4/3));assert(v.localUfrag.length<=256);
}
const java=process.argv.slice(2).find(v=>v.startsWith("--java="));
if(java) {
 const actual=JSON.parse(readFileSync(java.slice(7),"utf8"));
 assert.deepEqual(actual,fixture.vectors.map(({name,localUfrag,icePwd})=>({name,localUfrag,icePwd})));
}
console.log(`PASS ${fixture.vectors.length} shared NXS diagnostic vectors${java?" and fresh Java output":""}`);
