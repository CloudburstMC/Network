// Independent Node built-in crypto verification; imports neither Java nor TypeScript codec implementations.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createCipheriv, createHash, createHmac, createPublicKey, verify } from "node:crypto";

const fixture = JSON.parse(readFileSync(new URL("diagnostic-v1.fixtures.json", import.meta.url), "utf8"));
const utf8 = value => Buffer.from(value, "utf8"), hex = value => Buffer.from(value, "hex");
const domain = value => utf8(`nxs-diagnostic-${value}-v1\0`);
const sha = bytes => createHash("sha256").update(bytes).digest();
const hmac = bytes => createHmac("sha256", utf8(fixture.key.secret)).update(bytes).digest();
const b64 = bytes => bytes.toString("base64").replace(/=+$/, "");
const cat = (...parts) => Buffer.concat(parts);
const u64 = value => { const b = Buffer.alloc(8); b.writeBigUInt64BE(BigInt(value)); return b; };
const lp = value => { const b = utf8(value), length = Buffer.alloc(2); length.writeUInt16BE(b.length); return cat(length, b); };
const context = fixture.context;
const contextDigest = sha(cat(domain("context"), lp(context.providerOrigin), lp(context.hostId), hex(context.incarnation), u64(context.generation)));
assert.equal(contextDigest.toString("hex"), fixture.contextDigestHex);
const byName = new Map();
for (const vector of fixture.vectors) {
  const c = vector.claims, plain = Buffer.alloc(128 + c.clientIcePwd.length);
  plain.writeUInt32BE(c.expiresAt / 1000, 0); hex(c.clientFingerprintHex).copy(plain, 4);
  hex(c.attemptIdHex).copy(plain, 52); hex(c.offerDigestHex).copy(plain, 68);
  plain.writeBigUInt64BE(BigInt(c.candidateRevision), 100); plain[108] = c.family === 6 ? 129 : 1;
  hex(c.targetAddressHex).copy(plain, 109); plain.writeUInt16BE(c.targetPort, 125);
  plain[127] = c.clientIcePwd.length; utf8(c.clientIcePwd).copy(plain, 128);
  assert.equal(sha(utf8(vector.offer)).toString("hex"), c.offerDigestHex);
  const transcript = cat(domain("assertion"), contextDigest, plain, lp(vector.remoteUfrag));
  assert.equal(transcript.toString("hex"), vector.assertionTranscriptHex);
  const spki = cat(hex("3076301006072a8648ce3d020106052b81040022036200"), hex(vector.publicPointHex));
  const key = createPublicKey({ key: spki, format: "der", type: "spki" });
  assert(verify("sha384", transcript, { key, dsaEncoding: "ieee-p1363" }, hex(vector.signatureHex)), vector.name);
  hmac(cat(domain("identity"), contextDigest, spki)).subarray(0, 16).copy(plain, 36);
  const header = "NXD1" + fixture.key.keyId, nonce = hex(vector.nonceHex);
  const cipher = createCipheriv("aes-256-gcm", hmac(cat(domain("aead"), contextDigest)), nonce);
  cipher.setAAD(cat(domain("admission"), utf8(header), Buffer.alloc(1), contextDigest, Buffer.alloc(1), utf8(vector.remoteUfrag)));
  const encrypted = cat(cipher.update(plain), cipher.final(), cipher.getAuthTag());
  assert.equal(header + b64(cat(nonce, encrypted)), vector.localUfrag);
  assert.equal(b64(hmac(cat(domain("ice"), contextDigest, utf8(vector.localUfrag))).subarray(0, 24)), vector.icePwd);
  assert.equal(cat(Buffer.from([0, 78, 88, 68, 80, 1, 1, 0]), hex(c.attemptIdHex), hex(vector.publicPointHex), hex(vector.signatureHex)).toString("hex"), vector.authHex);
  assert.equal(vector.localUfrag.length, 8 + Math.ceil((156 + c.clientIcePwd.length) * 4 / 3));
  assert(vector.localUfrag.length <= 256); byName.set(vector.name, { key, transcript });
}
const java = process.argv.slice(2).find(value => value.startsWith("--java="));
let fresh = 0;
if (java) {
  const values = JSON.parse(readFileSync(java.slice(7), "utf8"));
  assert.equal(values.length, fixture.vectors.length);
  const names = new Set();
  for (const value of values) {
    const expected = byName.get(value.name); assert(expected); assert(!names.has(value.name)); names.add(value.name);
    assert(verify("sha384", expected.transcript, { key: expected.key, dsaEncoding: "ieee-p1363" }, hex(value.signatureHex)), value.name); fresh++;
  }
}
console.log(`PASS ${fixture.vectors.length} independent diagnostic vectors; ${fresh} fresh Java signatures`);
