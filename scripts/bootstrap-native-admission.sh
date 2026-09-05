#!/usr/bin/env bash
set -euo pipefail
network_root=$(cd "$(dirname "$0")/.." && pwd)
read_pin() { sed -n "s/^$1=//p" "$network_root/native-dependencies.properties"; }
java_revision=$(read_pin 'java.commit')
datachannel_revision=$(read_pin 'datachannel.commit')
juice_revision=$(read_pin 'juice.commit')
output=${1:-"$network_root/.native-deps/maven"}
output=$(realpath -m "$output")
java_checkout=${NATIVE_JAVA_CHECKOUT:-"$network_root/.native-deps/libdatachannel-java-$java_revision"}
if [[ ! -d "$java_checkout/.git" ]]; then
    if [[ -n "${NATIVE_JAVA_CHECKOUT:-}" ]]; then
        echo 'Supplied native Java checkout is missing' >&2
        exit 1
    fi
    mkdir -p "$(dirname "$java_checkout")"
    git clone --no-checkout "https://github.com/$(read_pin 'java.repository').git" "$java_checkout"
    git -C "$java_checkout" checkout --detach "$java_revision"
    git -C "$java_checkout" submodule update --init --recursive
fi
[[ $(git -C "$java_checkout" rev-parse HEAD) == "$java_revision" ]] || { echo 'Native Java revision mismatch' >&2; exit 1; }
[[ $(git -C "$java_checkout/jni/libdatachannel" rev-parse HEAD) == "$datachannel_revision" ]] || { echo 'Native transport revision mismatch' >&2; exit 1; }
[[ $(git -C "$java_checkout/jni/libdatachannel/deps/libjuice" rev-parse HEAD) == "$juice_revision" ]] || { echo 'Native ICE revision mismatch' >&2; exit 1; }
bash "$java_checkout/scripts/package-development.sh" "$output"
python3 - "$output" "$network_root/native-dependencies.properties" <<'PY'
import hashlib, json, pathlib, sys
root, manifest = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
pins = dict(line.split('=', 1) for line in manifest.read_text().splitlines() if line and not line.startswith('#'))
folder = root / pins['nativeJavaGroup'].replace('.', '/') / 'libdatachannel-java' / pins['nativeJavaVersion']
provenance = json.loads((folder / 'provenance.json').read_text())
for field, pin in [('bindingRevision','java.commit'),('libdatachannelRevision','datachannel.commit'),('libjuiceRevision','juice.commit')]:
    if provenance[field] != pins[pin]: raise SystemExit('Native provenance mismatch: ' + field)
for name, expected in provenance['sha256'].items():
    if hashlib.sha256((folder / name).read_bytes()).hexdigest() != expected: raise SystemExit('Native artifact hash mismatch: ' + name)
print('Verified native artifacts: ' + str(folder))
PY
