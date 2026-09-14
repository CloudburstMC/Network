// Independent Node digest vectors. Operation bodies are envelope fixtures, not full operation conformance.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

const file = fileURLToPath(new URL('control-v1.lifecycle.fixtures.json', import.meta.url));
const hash = bytes => createHash('sha256').update(bytes).digest('base64url');
export function intentText(intent) {
  return JSON.stringify(['nethernet-control-lifecycle-intent-v1', 1, intent.audience, intent.operation,
    intent.instanceId, intent.generation, intent.sequence, intent.idempotencyKey, intent.payloadSha256]);
}
if (process.argv.includes('--write')) {
  const vectors = [
    ['heartbeat-unicode', 'heartbeat', '{"healthy":true,"label":"café 😀","line":"\u2028","surrogate":"\\ud800"}'],
    ['rotation-intent', 'rotate', '{"candidateKeyThumbprint":"candidate_machine_key_02"}'],
    ['empty-body', 'deregister', '']
  ].map(([name, operation, bodyUtf8], index) => {
    const intent = {version: 1, audience: 'https://provider.example', operation, instanceId: 'instance_test_01',
      generation: 3, sequence: index + 12, idempotencyKey: `lifecycle_intent_000${index}`,
      payloadSha256: hash(Buffer.from(bodyUtf8, 'utf8'))};
    const intentDigest = hash(Buffer.from(intentText(intent), 'utf8'));
    const receipt = {version: 1, intentDigest, operation, instanceId: intent.instanceId,
      generation: intent.generation, sequence: intent.sequence, idempotencyKey: intent.idempotencyKey,
      disposition: 'committed', committedAt: 1789400001000, commitRevision: index + 41, code: null};
    return {name, bodyUtf8, intent, intentText: intentText(intent), intentDigest, receipt};
  });
  writeFileSync(file, JSON.stringify({format: 'nethernet-control-lifecycle-fixtures-v1', vectors}, null, 2) + '\n');
}
const fixture = JSON.parse(readFileSync(file, 'utf8'));
for (const vector of fixture.vectors) {
  assert.equal(vector.intentText, intentText(vector.intent));
  assert.equal(vector.intent.payloadSha256, hash(Buffer.from(vector.bodyUtf8, 'utf8')));
  assert.equal(vector.intentDigest, hash(Buffer.from(vector.intentText, 'utf8')));
  assert.equal(vector.receipt.intentDigest, vector.intentDigest);
  for (const field of ['operation', 'instanceId', 'generation', 'sequence', 'idempotencyKey']) {
    assert.equal(vector.receipt[field], vector.intent[field]);
  }
  assert.deepEqual(Object.keys(vector.receipt).sort(), ['version', 'intentDigest', 'operation', 'instanceId', 'generation',
    'sequence', 'idempotencyKey', 'disposition', 'committedAt', 'commitRevision', 'code'].sort());
}
console.log(`Verified ${fixture.vectors.length} draft stable intent/receipt vectors.`);
