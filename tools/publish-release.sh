#!/bin/zsh
# Publishes a release build to the home server, where TVs pick it up:
#   - new installs download http://<veed>:8787/veed-tv.apk (Downloader app, see the install guide)
#   - installed copies read veed-tv.json, offer the update at the end of the channel list,
#     and install it when someone presses OK.
#
# Usage: tools/publish-release.sh "One plain sentence for the family about what changed."
#
# Bump versionCode in app/build.gradle.kts first: TVs only offer a higher versionCode.
# Refuses an APK not signed with the release key (README, "Release key"), because Android
# would refuse it as an update on every TV that already has the app.
set -euo pipefail
cd "$(dirname "$0")/.."

NOTES=${1:?usage: tools/publish-release.sh "what changed, in one sentence"}

# Where to publish, from local.properties (never in git; see README, "Configuration").
prop() { sed -n "s/^$1=//p" local.properties 2>/dev/null | tail -1; }
HOST=$(prop veedtv.publishHost)                       # ssh destination, user@server
WWW=$(prop veedtv.publishDir)                         # the directory the server serves
SERVER=$(prop veedtv.serverUrls | cut -d, -f1)        # the first server URL the app uses
CERT=$(prop veedtv.releaseCertSha256)                 # SHA-256 of your release certificate
for key in publishHost publishDir serverUrls releaseCertSha256; do
  [[ -n $(prop veedtv.$key) ]] || { print -u2 "refusing: set veedtv.$key in local.properties"; exit 1; }
done

export JAVA_HOME=${JAVA_HOME:-"/Applications/Android Studio.app/Contents/jbr/Contents/Home"}
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
APKSIGNER=$(ls -d "$SDK"/build-tools/*/apksigner | sort -V | tail -1)
APK=app/build/outputs/apk/release/app-release.apk

./gradlew -q :app:assembleRelease
got=$("$APKSIGNER" verify --print-certs "$APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[[ "$got" == "$CERT" ]] || { print -u2 "refusing: $APK is signed with $got, not the release key"; exit 1; }

meta=app/build/outputs/apk/release/output-metadata.json
code=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionCode"])' "$meta")
name=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionName"])' "$meta")
live=$(curl -fsS --max-time 5 "$SERVER/veed-tv.json" 2>/dev/null \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["versionCode"])' 2>/dev/null || echo 0)
(( code > live )) || { print -u2 "refusing: versionCode $code is not above the published $live"; exit 1; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
python3 - "$APK" "$code" "$name" "$NOTES" > "$tmp/veed-tv.json" <<'EOF'
import hashlib, json, os, sys
apk, code, name, notes = sys.argv[1:]
digest = hashlib.sha256(open(apk, "rb").read()).hexdigest()
print(json.dumps({"versionCode": int(code), "versionName": name, "sha256": digest,
                  "size": os.path.getsize(apk), "notes": notes}, indent=1))
EOF

# The APK first and the announcement last, each renamed into place, so a TV never reads a
# veed-tv.json that points at a missing or half-copied APK.
scp -q "$APK" "$HOST:$WWW/.veed-tv.apk.part"
scp -q "$tmp/veed-tv.json" "$HOST:$WWW/.veed-tv.json.part"
ssh "$HOST" "mv $WWW/.veed-tv.apk.part $WWW/veed-tv.apk && mv $WWW/.veed-tv.json.part $WWW/veed-tv.json"

curl -fsS --max-time 5 "$SERVER/veed-tv.json"
echo "published $name ($code)"
