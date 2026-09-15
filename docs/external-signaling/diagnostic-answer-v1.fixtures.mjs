// Independent Node built-ins; imports neither answer nor admission implementations.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createHash, createPublicKey, verify } from "node:crypto";
const fixture = JSON.parse(readFileSync(new URL("diagnostic-answer-v1.fixtures.json", import.meta.url), "utf8"));
const utf8 = value => Buffer.from(value, "utf8"), hex = value => Buffer.from(value, "hex");
const cat = (...parts) => Buffer.concat(parts), sha = bytes => createHash("sha256").update(bytes).digest();
const domain = value => utf8(`nxs-diagnostic-${value}-v1\0`);
const u64 = value => { const b = Buffer.alloc(8); b.writeBigUInt64BE(BigInt(value)); return b; };
const u32 = value => { const b = Buffer.alloc(4); b.writeUInt32BE(value); return b; };
const lp = value => { const b = utf8(value), size = Buffer.alloc(2); size.writeUInt16BE(b.length); return cat(size, b); };
const publicKey = point => createPublicKey({ key: cat(hex("3076301006072a8648ce3d020106052b81040022036200"), hex(point)), format: "der", type: "spki" });
const names = new Map();
function verifyAnswer(vector, wire) {
  const w = JSON.parse(wire), e = vector.expected, c = e.claims, context = e.context;
  const ctx = sha(cat(domain("context"), lp(context.providerOrigin), lp(context.hostId), hex(context.incarnation), u64(context.generation)));
  const plain = Buffer.alloc(128 + c.clientIcePwd.length);
  plain.writeUInt32BE(c.expiresAt / 1000, 0); hex(c.clientFingerprintHex).copy(plain, 4);
  hex(c.attemptIdHex).copy(plain, 52); hex(c.offerDigestHex).copy(plain, 68);
  u64(c.candidateRevision).copy(plain, 100); plain[108] = c.profile | (c.family === 6 ? 128 : 0);
  hex(c.targetAddressHex).copy(plain, 109); plain.writeUInt16BE(c.targetPort, 125);
  plain[127] = c.clientIcePwd.length; utf8(c.clientIcePwd).copy(plain, 128);
  const request = sha(cat(domain("assertion"), ctx, plain, lp(e.remoteUfrag)));
  assert.equal(w.version, 1); assert.equal(w.kind, "diagnostic-answer"); assert.equal(w.expiresAt, c.expiresAt);
  assert.equal(w.requestDigestHex, request.toString("hex"));
  const answer = Buffer.from(w.answerSdpBase64, "base64");
  assert.equal(answer.toString("utf8"), vector.answer); assert.equal(sha(answer).toString("hex"), w.answerDigestHex);
  const transcript = cat(domain("answer"), lp(w.keyId), u64(w.expiresAt), request, sha(answer), u32(answer.length));
  assert.equal(transcript.toString("hex"), vector.transcriptHex);
  const selected = fixture.catalog.keys.find(key => key.keyId === w.keyId);
  assert(selected); assert.equal(selected.family, "provider-diagnostic"); assert.equal(fixture.catalog.providerOrigin, context.providerOrigin);
  assert(w.expiresAt <= fixture.catalog.expiresAt && w.expiresAt <= selected.validUntil);
  assert(verify("sha384", transcript, { key: publicKey(selected.publicPointHex), dsaEncoding: "ieee-p1363" }, Buffer.from(w.signatureBase64, "base64")), vector.name);
}
for (const vector of fixture.vectors) { verifyAnswer(vector, vector.wire); names.set(vector.name, vector); }
const javaPath = process.argv.slice(2).find(value => value.startsWith("--java="));
let fresh = 0;
if (javaPath) {
  const answers = JSON.parse(readFileSync(javaPath.slice(7), "utf8")), seen = new Set();
  assert.equal(answers.length, fixture.vectors.length);
  for (const answer of answers) { assert(!seen.has(answer.name)); seen.add(answer.name); const vector = names.get(answer.name); assert(vector); verifyAnswer(vector, answer.wire); fresh++; }
}
console.log(`PASS ${fixture.vectors.length} independent signed diagnostic answer vectors; ${fresh} fresh Java answers`);
