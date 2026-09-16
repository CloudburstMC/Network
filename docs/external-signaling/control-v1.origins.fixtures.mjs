// Independent WHATWG serialization checks plus explicit narrower draft-control profile exclusions.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const file = fileURLToPath(new URL('control-v1.origins.fixtures.json', import.meta.url));
let fixture;
if (process.argv.includes('--write')) {
  const vectors = [];
  const add = (origin, accepted, category) => vectors.push({origin, accepted, category});
  for (const origin of ['https://provider.example', 'https://provider.example:1', 'https://provider.example:65535',
    'https://provider.example:80', 'https://a', 'https://localhost', 'https://a-b.example', 'https://123.example',
    'https://a123', 'https://0xabc.example', 'https://ab--cd.example',
    'https://0.0.0.0', 'https://192.0.2.128', 'https://255.255.255.255', 'https://127.0.0.1:8443',
    'https://[::]', 'https://[::1]', 'https://[2001:db8::1]', 'https://[2001:db8::1]:8443',
    'https://[1:2:3:4:5:6:7:8]', 'https://[2001:db8:0:1:2:3:4:5]', 'https://[1::2:0:0:3:4]',
    'https://[1:0:0:2::3]', 'https://[1:2:3:4:5:6::]', 'https://[::ffff:c000:280]',
    'https://[::ffff:7f00:1]', 'http://localhost', 'http://localhost:443', 'http://127.0.0.1', 'http://127.0.0.1:8080',
    'http://[::1]', 'http://[::1]:65535']) add(origin, true, 'canonical');
  for (const origin of ['', 'provider.example', 'HTTPS://provider.example', 'https:/provider.example', 'https:provider.example',
    'wss://provider.example', 'ws://localhost', 'ftp://provider.example', 'https://',
    'https://Provider.example', 'https://provider.EXAMPLE', 'https://provider.example/', 'https://provider.example/path',
    'https://provider.example?', 'https://provider.example?query', 'https://provider.example#', 'https://provider.example#fragment',
    'https://user@provider.example', 'https://user:pass@provider.example', 'https://@provider.example',
    ' https://provider.example', 'https://provider.example ', 'https://provider.example\n', 'https://provider.example\r',
    'https://provider.example\t', 'https://provider.ex\nample', '\ufeffhttps://provider.example', 'https://provider.example\0',
    'https://provider.example\\path', 'https://provider.example%2f', 'https://provider%2eexample',
    'https://provider.example%3a443', 'https://provider.example%40evil.example', 'https://provider.example%23',
    'https://provider.example:', 'https://provider.example:0', 'https://provider.example:-0', 'https://provider.example:-1',
    'https://provider.example:+443', 'https://provider.example:443', 'https://provider.example:0443', 'https://provider.example:08443',
    'https://provider.example:65536', 'https://provider.example:999999999999999999', 'https://provider.example:1.0',
    'https://provider.example:1e3', 'https://provider.example:0x50', 'https://provider.example:80:81',
    'https://provider.example.', 'https://provider..example', 'https://.provider.example', 'https://-provider.example',
    'https://provider-.example', 'https://provider_example', 'https://provider.123', 'https://provider.123a', 'https://provider.0x10',
    'https://provider.0x', 'https://127.1', 'https://127.0.1', 'https://2130706433', 'https://0177.0.0.1',
    'https://127.00.0.1', 'https://127.0.0.01', 'https://0x7f000001', 'https://0x7f.0.0.1', 'https://4294967295',
    'https://4294967296', 'https://256.1.1.1', 'https://1.2.3.4.5', 'https://1.2.3.-0',
    'https://bücher.example', 'https://xn--bcher-kva.example', 'https://xn--fa-hia.example', 'https://xn--a.example',
    'https://xn--.example', 'https://xn--invalid-.example', 'https://XN--BCHER-KVA.example', 'https://provider。example',
    'https://[0:0:0:0:0:0:0:1]', 'https://[0000::1]', 'https://[2001:DB8::1]', 'https://[2001:0db8::1]',
    'https://[1:0:0:2::3:4]', 'https://[1::2:0:0:0:3]', 'https://[1:2:3:4:5:6::8]',
    'https://[1:2:3:4:5:6:0:0]', 'https://[::ffff:192.0.2.128]', 'https://[::127.0.0.1]',
    'https://[fe80::1%eth0]', 'https://[fe80::1%25eth0]', 'https://2001:db8::1', 'https://[]',
    'https://[:::]', 'https://[1::2::3]', 'https://[1:2:3:4:5:6:7]', 'https://[1:2:3:4:5:6:7:8:9]',
    'https://[12345::1]', 'https://[gggg::1]', 'https://[::1', 'https://::1]', 'https://[::1]]',
    'http://provider.example', 'http://127.0.0.2', 'http://127.1', 'http://localhost.', 'http://sub.localhost',
    'http://[::ffff:7f00:1]', 'http://[0:0:0:0:0:0:0:1]', 'http://[::1]:80']) add(origin, false, 'alias-invalid-or-excluded');
  add(`https://${'a'.repeat(63)}.example`, true, 'dns-label-boundary');
  add(`https://${'a'.repeat(64)}.example`, false, 'dns-label-boundary');
  add(`https://${['a'.repeat(63), 'b'.repeat(63), 'c'.repeat(63), 'd'.repeat(61)].join('.')}`, true, 'dns-total-boundary');
  add(`https://${['a'.repeat(63), 'b'.repeat(63), 'c'.repeat(63), 'd'.repeat(62)].join('.')}`, false, 'dns-total-boundary');
  add(`https://${'a'.repeat(2041)}`, false, 'origin-size');
  // Independently canonicalize deterministic IPv6 samples using WHATWG, then reject their expanded aliases.
  let state = 0x6d2b79f5;
  for (let sample = 0; sample < 64; sample++) {
    const pieces = [];
    for (let index = 0; index < 8; index++) {
      state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
      pieces.push((state % 3 === 0 ? 0 : state & 0xffff).toString(16).padStart(4, '0'));
    }
    const expanded = `https://[${pieces.join(':')}]`, canonical = new URL(expanded).origin;
    add(canonical, true, 'whatwg-generated-ipv6');
    if (expanded !== canonical) add(expanded, false, 'whatwg-generated-ipv6-alias');
  }
  fixture = {format:'nethernet-control-origin-fixtures-v1', profile:'ascii-dns-canonical-ip-literals-v1',
    note:'Intentional narrower profile: reject all IDN/xn-- labels, trailing dots, numeric-starting final DNS labels and noncanonical IPv4/IPv6 spellings. Never normalize signed input.', vectors};
  writeFileSync(file, JSON.stringify(fixture, null, 2) + '\n');
} else fixture = JSON.parse(readFileSync(file, 'utf8'));
for (const vector of fixture.vectors) if (vector.accepted) assert.equal(new URL(vector.origin).origin, vector.origin, vector.origin);
const javaOutput = process.argv.find(value => value.startsWith('--java-output='));
if (javaOutput) {
  const output = JSON.parse(readFileSync(javaOutput.slice('--java-output='.length), 'utf8'));
  assert.equal(output.length, fixture.vectors.length);
  output.forEach((accepted, index) => assert.equal(accepted, fixture.vectors[index].accepted, fixture.vectors[index].origin));
}
console.log(`Verified ${fixture.vectors.length} explicit control-origin cases; accepted origins retain exact WHATWG bytes${javaOutput ? '; Java agrees' : ''}.`);
