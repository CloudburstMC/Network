// Deterministic public protocol vectors. No keys, native sockets or provider traffic.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync, writeFileSync} from 'node:fs';

const file = new URL('control-v1.candidate-leases.fixtures.json', import.meta.url);
const incarnation = '0123456789abcdef0123456789abcdef';
const nativeOwnerEpoch = 5;
const digest = text => createHash('sha256').update(text, 'utf8').digest('base64url');
const entry = o => [o.family, o.addressHex, o.port, o.monitorEpoch, o.mappingRevision,
  o.observationSequence, o.observedAt, o.expiresAt];
const profileText = p => JSON.stringify(['nethernet-control-host-profile-v2', 2,
  p.candidates.map(c => [c.address, c.port, c.component, c.foundation, c.priority, c.protocol, c.type]),
  [p.statelessAdmission.capability, p.statelessAdmission.incarnation], p.credentialKeyId,
  p.dtlsFingerprint, p.maxMessageSize, p.sctpPort]);
const observationText = (epoch, inc, o) => JSON.stringify(['nethernet-control-candidate-observation-v1', 1, epoch, inc, entry(o)]);
const leaseText = l => JSON.stringify(['nethernet-control-candidate-leases-v1', 1,
  l.profileSha256, l.nativeOwnerEpoch, l.nativeIncarnation, l.observations.map(entry)]);
const candidate = (address, port, type, foundation) => ({address, port, component: 1,
  foundation, priority: 2130706431, protocol: 'udp', type});
const profile = candidates => ({version: 2, candidates,
  statelessAdmission: {capability: 'nethernet.stateless-admission.v1', incarnation},
  credentialKeyId: 'A001', dtlsFingerprint: 'sha-256 ' + Array(32).fill('aB').join(':'),
  maxMessageSize: 262144, sctpPort: 5000});
const v4 = {family: 'ipv4', addressHex: '08080808', port: 43000, monitorEpoch: 2,
  mappingRevision: 3, observationSequence: 7, observedAt: 1789400000000, expiresAt: 1789400270000};
const v6 = {family: 'ipv6', addressHex: '26064700470000000000000000001111', port: 43001,
  monitorEpoch: 4, mappingRevision: 1, observationSequence: 11,
  observedAt: 1789400000123, expiresAt: 1789400270123};
const dual = profile([candidate('8.8.8.8', 43000, 'srflx', 'srflx-v4'),
  candidate('2606:4700:4700::1111', 43001, 'srflx', 'srflx-v6')]);
const rebound = {...dual, credentialKeyId: 'B002'};
const expanded = structuredClone(dual);
expanded.candidates[1].address = '2606:4700:4700:0:0:0:0:1111';
const uppercase = {...dual, dtlsFingerprint: dual.dtlsFingerprint.toUpperCase().replace('SHA-', 'sha-')};
const sourceProfiles = [
  ['empty', profile([])],
  ['host-only', profile([candidate('1.1.1.1', 19132, 'host', 'host-v4')])],
  ['dual-srflx', dual], ['same-observations-new-key', rebound],
  ['exact-expanded-ipv6', expanded], ['exact-fingerprint-case', uppercase],
  ['exact-candidate-order', {...dual, candidates: [...dual.candidates].reverse()}],
  ['mixed-host-srflx', profile([candidate('1.1.1.1', 19132, 'host', 'host-v4'), dual.candidates[1]])]
];
const profiles = sourceProfiles.map(([name, p]) => ({name, profile: p,
  preimageUtf8: profileText(p), sha256: digest(profileText(p))}));
const observations = [['ipv4', v4], ['ipv6', v6], ['same-mapping-new-success', {...v4,
  observationSequence: 8, observedAt: v4.observedAt + 15000, expiresAt: v4.expiresAt + 15000}],
  ['safe-integer-counters', {...v6, monitorEpoch: Number.MAX_SAFE_INTEGER,
    mappingRevision: Number.MAX_SAFE_INTEGER, observationSequence: Number.MAX_SAFE_INTEGER}]]
  .map(([name, observation]) => ({name, nativeOwnerEpoch, nativeIncarnation: incarnation, observation,
    preimageUtf8: observationText(nativeOwnerEpoch, incarnation, observation), sha256: digest(observationText(nativeOwnerEpoch, incarnation, observation))}));
const leases = profiles.map(p => {
  const selected = p.profile.candidates.filter(c => c.type === 'srflx');
  const entries = [v4, v6].filter(o => selected.some(c => c.port === o.port));
  const candidateLeases = {version: 1, profileSha256: p.sha256, nativeOwnerEpoch, nativeIncarnation: incarnation, observations: entries};
  const preimageUtf8 = leaseText(candidateLeases), sha256 = digest(preimageUtf8);
  return {name: p.name, profile: p.name, candidateLeases, preimageUtf8, sha256,
    receipt: {version: 1, hostProfileRevision: 'hpr_fixture_' + p.name,
      profileSha256: p.sha256, acceptedSha256: sha256,
      expiresAt: entries.length ? Math.min(...entries.map(o => o.expiresAt)) : 0}};
});
const nativeOwners = [0, 4, Number.MAX_SAFE_INTEGER - 1].map(expectedEpoch => ({
  claim: {version: 1, expectedEpoch, claimId: 'candidate_owner_claim_01'},
  owner: {version: 1, epoch: expectedEpoch + 1, nativeIncarnation: incarnation, claimId: 'candidate_owner_claim_01'}
}));
const fixture = {format: 'nethernet-control-candidate-leases-fixtures-v1', profiles, observations, leases, nativeOwners};
if (process.argv.includes('--write')) writeFileSync(file, JSON.stringify(fixture, null, 2) + '\n');
else assert.deepEqual(JSON.parse(readFileSync(file, 'utf8')), fixture);
console.log(`Verified ${profiles.length} profiles, ${observations.length} observations and ${leases.length} candidate leases.`);
