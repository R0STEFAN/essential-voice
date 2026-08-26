#!/usr/bin/env bash
# Cut a release: build a signed APK and the manifest that tells installed copies
# about it. Both land in dist/, ready to upload side by side.
#
#   ./release.sh            build the current versionCode/versionName
#   ./release.sh 2 1.1      bump to versionCode 2, versionName 1.1, then build
set -euo pipefail
cd "$(dirname "$0")"

GRADLE=$(echo "$HOME"/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle)
BUILD=app/build.gradle.kts

if [ $# -eq 2 ]; then
  sed -i "s/^        versionCode = .*/        versionCode = $1/" "$BUILD"
  sed -i "s/^        versionName = .*/        versionName = \"$2\"/" "$BUILD"
  echo "bumped to versionCode $1, versionName $2"
fi

CODE=$(grep -oP '^\s*versionCode = \K[0-9]+' "$BUILD")
NAME=$(grep -oP '^\s*versionName = "\K[^"]+' "$BUILD")
# The URL is a buildConfigField spread over several lines, so pull it from the
# whole file rather than trying to anchor on one.
URL=$(grep -oP 'https://[^\\"]+update\.json' "$BUILD" | head -1 || true)

# The manifest sits on a raw path so its URL can stay put while its contents
# change; the APK is a release asset, which is a different URL shape entirely.
# The source and the manifest live in the same repository, so the remote of
# this directory is the one to ask.
REPO=$(git remote get-url origin 2>/dev/null \
  | sed -E 's#.*github\.com[:/]##; s#\.git$##' || true)
if [ -n "$REPO" ]; then
  BASE="https://github.com/$REPO/releases/download/v$NAME"
else
  BASE=$(dirname "${URL:-https://example.invalid/update.json}")
fi

if [ ! -f keystore.properties ]; then
  echo "!! keystore.properties is missing — the build would be signed with the"
  echo "!! debug key and could never update an existing install."
  exit 1
fi

"$GRADLE" --no-daemon :app:assembleRelease

mkdir -p dist
APK="dist/essential-voice-$NAME.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"

PAGE="https://github.com/${REPO:-owner/repo}/releases/tag/v$NAME"
cat > dist/update.json <<JSON
{
  "versionCode": $CODE,
  "versionName": "$NAME",
  "url": "$BASE/essential-voice-$NAME.apk",
  "page": "$PAGE",
  "notes": "Edit this line to say what changed."
}
JSON

echo
echo "dist/:"
ls -lh dist/
echo
echo "Next:"
echo "  gh release create v$NAME dist/essential-voice-$NAME.apk --title \"v$NAME\" --notes \"...\""
echo "  cp dist/update.json . && git add update.json && git commit -m \"v$NAME\" && git push"
echo
echo "Installed copies look for the manifest at:"
echo "  ${URL:-<UPDATE_MANIFEST_URL is unset in app/build.gradle.kts>}"
