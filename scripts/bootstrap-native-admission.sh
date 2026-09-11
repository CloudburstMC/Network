#!/usr/bin/env bash
set -euo pipefail
network_root=$(cd "$(dirname "$0")/.." && pwd)
read_pin() { sed -n "s/^$1=//p" "$network_root/native-dependencies.properties"; }
output=${1:-"$network_root/.native-deps/maven"}
output=$(realpath -m "$output")

# The published binding records the revisions it was built from, so this development build follows
# the artifact in use instead of a second set of pins that could drift away from it.
binding_jar=${NATIVE_BINDING_JAR:-$(cd "$network_root" && ./gradlew --console=plain -q :external-signalling:nativeBindingJar | tail -n 1)}
read_manifest() { unzip -p "$binding_jar" META-INF/MANIFEST.MF | tr -d '\r' | sed -n "s/^$1: //p"; }
java_revision=$(read_manifest 'Source-Revision')
datachannel_revision=$(read_manifest 'LibDataChannel-Revision')
juice_revision=$(read_manifest 'LibJuice-Revision')
for revision in "$java_revision" "$datachannel_revision" "$juice_revision"; do
    [[ $revision =~ ^[0-9a-f]{40}$ ]] || {
        echo "Published binding does not record its revision chain, republish it: $binding_jar" >&2
        exit 1
    }
done

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
folder=$(bash "$java_checkout/scripts/package-development.sh" "$output" | tail -n 1)
python3 - "$folder" "$java_revision" "$datachannel_revision" "$juice_revision" <<'PY'
import hashlib, json, pathlib, sys
folder = pathlib.Path(sys.argv[1])
expected = dict(zip(['bindingRevision', 'libdatachannelRevision', 'libjuiceRevision'], sys.argv[2:5]))
provenance = json.loads((folder / 'provenance.json').read_text())
for field, revision in expected.items():
    if provenance[field] != revision: raise SystemExit('Native provenance mismatch: ' + field)
for name, digest in provenance['sha256'].items():
    if hashlib.sha256((folder / name).read_bytes()).hexdigest() != digest: raise SystemExit('Native artifact hash mismatch: ' + name)
print('Verified native artifacts: ' + str(folder))
PY
# Machine readable, so CI can build against exactly what was just verified.
echo "nativeMavenRepository=file://$output"
echo "nativeJavaVersion=$(basename "$folder")"
