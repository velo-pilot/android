#!/usr/bin/env bash
# Adds a release APK to the F-Droid repository on cdn.osmpedelec.be and publishes the repository. Runs in the
# GitLab CI job fdroid:publish, on Debian with fdroidserver and git.
#
#   build-container/fdroid/update-repo.sh APK
#
# The storage zone holds the published APKs and the release tags hold their release notes, so the job keeps no
# state of its own: it downloads the published APKs, adds the new one, keeps the newest KEEP_RELEASES, writes and
# signs a new index and uploads the repository.
#
# Environment (see build-container/README.md):
#   FDROID_KEYSTORE_BASE64, FDROID_KEYSTORE_PASSWORD, FDROID_KEY_ALIAS   repository signing key
#   BUNNY_STORAGE_ZONE, BUNNY_STORAGE_PASSWORD, BUNNY_STORAGE_HOST, BUNNY_API_KEY, CDN_URL   see bunny_sync.py
#   REMOTE_PREFIX   folder of the repository in the storage zone (default fdroid)
#   KEEP_RELEASES   number of releases in the repository (default 3)
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR=$(cd "$SCRIPT_DIR/../.." && pwd)
APP_ID=be.velopilot.app
REMOTE_PREFIX=${REMOTE_PREFIX:-fdroid}
KEEP_RELEASES=${KEEP_RELEASES:-3}
apk=$(realpath "$1")

if [ -z "${FDROID_KEYSTORE_BASE64:-}" ] || [ -z "${FDROID_KEYSTORE_PASSWORD:-}" ]; then
	echo "FDROID_KEYSTORE_BASE64 and FDROID_KEYSTORE_PASSWORD are not set; they are protected variables, so" \
		"the tag must be protected too" >&2
	exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cd "$work"
umask 077
base64 -d <<< "$FDROID_KEYSTORE_BASE64" > keystore.p12
cat > config.yml <<-EOF
repo_url: $CDN_URL/$REMOTE_PREFIX/repo
repo_name: VeloPilot
repo_description: >-
  OsmAnd with routing for 45 km/h speed pedelecs.
archive_older: 0
keystore: keystore.p12
repo_keyalias: ${FDROID_KEY_ALIAS:-velopilot}
keystorepass: {env: FDROID_KEYSTORE_PASSWORD}
keypass: {env: FDROID_KEYSTORE_PASSWORD}
EOF
umask 022

echo ">> Downloading the published APKs"
python3 "$SCRIPT_DIR/bunny_sync.py" --pull . "$REMOTE_PREFIX" repo
mkdir -p repo

apk_id() {
	python3 -c 'import sys; from fdroidserver import common; print(*common.get_apk_id(sys.argv[1]))' "$1"
}
read -r app_id code name < <(apk_id "$apk")
if [ "$app_id" != "$APP_ID" ]; then
	echo "$apk is $app_id, not $APP_ID" >&2
	exit 1
fi
echo ">> Adding $name (versionCode $code)"
cp "$apk" "repo/${APP_ID}_$code.apk"
ls repo/"${APP_ID}"_*.apk | sort -t_ -k2 -n | head -n "-$KEEP_RELEASES" | while read -r old; do
	echo ">> Removing $old"
	rm "$old"
done

# Metadata: the template, a build entry with the tag's release notes for each APK (the index only carries a
# version's release notes when the version has a build entry), and the newest APK as current version.
# Never mark a build entry "disable": fdroid update deletes the APK of a disabled build.
# fdroid update reads the repository icon from this folder and the app icon from the metadata: R8 shortens the
# resource paths of the release APK, so fdroidserver cannot find the icon in it.
git -C "$REPO_DIR" fetch --quiet --force --depth 1 origin '+refs/tags/v*:refs/tags/v*'
mkdir -p "metadata/$APP_ID/en-US/changelogs"
cp "$SCRIPT_DIR/icon.png" icon.png
cp "$SCRIPT_DIR/icon.png" "metadata/$APP_ID/en-US/icon.png"
metadata=metadata/$APP_ID.yml
cp "$SCRIPT_DIR/metadata/$APP_ID.yml" "$metadata"
printf '\nBuilds:\n' >> "$metadata"
for file in $(ls repo/"${APP_ID}"_*.apk | sort -t_ -k2 -n); do
	read -r app_id code name < <(apk_id "$file")
	printf '  - versionName: %s\n    versionCode: %s\n    commit: v%s\n' "$name" "$code" "$name" >> "$metadata"
	printf '%s\n' "$(git -C "$REPO_DIR" tag --list --format='%(contents)' "v$name")" | head -c 500 \
		> "metadata/$APP_ID/en-US/changelogs/$code.txt"
done
printf '\nCurrentVersion: %s\nCurrentVersionCode: %s\n' "$name" "$code" >> "$metadata"

fdroid update

echo ">> Uploading the repository"
python3 "$SCRIPT_DIR/bunny_sync.py" . "$REMOTE_PREFIX" repo

fingerprint=$(keytool -exportcert -keystore keystore.p12 -alias "${FDROID_KEY_ALIAS:-velopilot}" \
	-storepass:env FDROID_KEYSTORE_PASSWORD | sha256sum | cut -d' ' -f1 | tr a-f A-F)
echo ">> Published $name: $CDN_URL/$REMOTE_PREFIX/repo?fingerprint=$fingerprint"
