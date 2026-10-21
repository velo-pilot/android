#!/usr/bin/env bash
# Releases OsmAnd Speed Pedelec to the F-Droid repository on cdn.osmpedelec.be.
#
#   build-container/release.sh init       # once: release key, F-Droid repository and release.env
#   build-container/release.sh            # build the next release, add it to the repository and publish
#   build-container/release.sh publish    # only upload the repository, e.g. after a failed upload
#   build-container/release.sh shell      # shell in the fdroidserver container, in the repository
#
# A release of OsmAnd X.Y.Z is tagged vX.Y.Z-spN (N counts up from 1 per OsmAnd version) and gets
# versionCode <OsmAnd versionCode> * 100 + N, so every release and every OsmAnd update upgrades the last one.
#
# Environment:
#   RELEASE_HOME   keys, release.env and the F-Droid repository (default ~/.local/share/osmpedelec-release)
#   CDN_URL        public URL of the Bunny pull zone (default https://cdn.osmpedelec.be)
#   REMOTE_PREFIX  folder of the repository in the storage zone (default fdroid)
#   TASK           Gradle task that builds the APK (default assembleSpeedPedelecOpenglArm64Release)
#   NOTES_FILE     release notes, up to 500 characters (default: subjects of the commits since the last
#                  release that are not on origin/master)
#   DRY_RUN        1 = show what publish would upload and delete, without changing the storage zone
#   REBUILD_IMAGE  1 = rebuild the fdroidserver image
# release.env holds the passwords and Bunny credentials; see the README.
set -euo pipefail

REPO_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SCRIPT_DIR=$REPO_DIR/build-container
RELEASE_HOME=${RELEASE_HOME:-${XDG_DATA_HOME:-$HOME/.local/share}/osmpedelec-release}
FDROID_DIR=$RELEASE_HOME/fdroid
ENV_FILE=$RELEASE_HOME/release.env
export CDN_URL=${CDN_URL:-https://cdn.osmpedelec.be}
REMOTE_PREFIX=${REMOTE_PREFIX:-fdroid}
TASK=${TASK:-assembleSpeedPedelecOpenglArm64Release}
FDROID_IMAGE=${FDROID_IMAGE:-localhost/osmpedelec-fdroid}
APP_ID=be.spedelec.osmand.app
KEY_ALIAS=speedpedelec
COMMAND=${1:-release}

label=""
if command -v selinuxenabled > /dev/null && selinuxenabled; then
	label=":z"
fi

fdroid_container() {
	if [ "${REBUILD_IMAGE:-0}" = 1 ] || ! podman image exists "$FDROID_IMAGE"; then
		echo ">> Building image $FDROID_IMAGE"
		podman build -t "$FDROID_IMAGE" -f "$SCRIPT_DIR/fdroid/Containerfile" "$SCRIPT_DIR/fdroid"
		REBUILD_IMAGE=0
	fi
	local tty=()
	if [ -t 0 ] && [ -t 1 ]; then
		tty=(-it)
	fi
	podman run --rm --init "${tty[@]}" \
		-v "$FDROID_DIR:/repo$label" \
		-v "$SCRIPT_DIR/fdroid:/scripts:ro${label:+,z}" \
		-w /repo "$@"
}

load_env() {
	if [ ! -f "$ENV_FILE" ]; then
		echo "$ENV_FILE is missing; run $0 init first" >&2
		exit 1
	fi
	set -a
	# shellcheck source=/dev/null
	. "$ENV_FILE"
	set +a
}

random_password() {
	tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 32 || true
}

cmd_init() {
	if [ -e "$ENV_FILE" ] || [ -e "$FDROID_DIR/config.yml" ]; then
		echo "$RELEASE_HOME is already set up; init never replaces keys" >&2
		exit 1
	fi
	mkdir -p "$FDROID_DIR"
	chmod 700 "$RELEASE_HOME"
	local apk_password
	apk_password=$(random_password)

	echo ">> Creating the APK signing key"
	OSMAND_APK_PASSWORD=$apk_password fdroid_container -v "$RELEASE_HOME:/out$label" -e OSMAND_APK_PASSWORD \
		"$FDROID_IMAGE" keytool -genkeypair -keystore /out/apk-signing.p12 -storetype PKCS12 \
		-alias "$KEY_ALIAS" -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=OsmAnd Speed Pedelec" \
		-storepass:env OSMAND_APK_PASSWORD -keypass:env OSMAND_APK_PASSWORD
	chmod 600 "$RELEASE_HOME/apk-signing.p12"

	echo ">> Creating the F-Droid repository and its index signing key"
	fdroid_container "$FDROID_IMAGE" fdroid init --no-prompt --repo-keyalias osmpedelec --keystore keystore.p12
	cat >> "$FDROID_DIR/config.yml" <<-EOF

	repo_url: $CDN_URL/$REMOTE_PREFIX/repo
	repo_name: OsmAnd Speed Pedelec
	repo_description: >-
	  OsmAnd with routing for 45 km/h speed pedelecs, built from the speed-pedelec branch of OsmAnd.
	archive_older: 3
	archive_url: $CDN_URL/$REMOTE_PREFIX/archive
	archive_name: OsmAnd Speed Pedelec archive
	archive_description: Older releases of OsmAnd Speed Pedelec.
	EOF
	chmod 600 "$FDROID_DIR/config.yml"

	(
		umask 077
		cat > "$ENV_FILE" <<-EOF
		# Secrets for build-container/release.sh. Back up this folder: without apk-signing.p12, users have to
		# uninstall the app to install a newer release; without fdroid/keystore.p12 they have to add the
		# repository again.
		OSMAND_APK_KEYSTORE=$RELEASE_HOME/apk-signing.p12
		OSMAND_APK_KEY_ALIAS=$KEY_ALIAS
		OSMAND_APK_PASSWORD=$apk_password

		# Bunny Storage zone behind $CDN_URL: Storage > zone > FTP & API Access
		BUNNY_STORAGE_ZONE=
		BUNNY_STORAGE_PASSWORD=
		# Endpoint of the zone's main region, e.g. uk.storage.bunnycdn.com; Falkenstein is storage.bunnycdn.com
		BUNNY_STORAGE_HOST=storage.bunnycdn.com
		# Account > API key; optional, purges the cached index after an upload
		BUNNY_API_KEY=
		EOF
	)
	cat <<-EOF

	>> Done. Fill in the Bunny credentials in $ENV_FILE and back up $RELEASE_HOME.
	   Repository: $CDN_URL/$REMOTE_PREFIX/repo
	EOF
	fingerprint
}

fingerprint() {
	fdroid_container "$FDROID_IMAGE" python3 -c '
import argparse
from fdroidserver import common, index
common.options = argparse.Namespace(verbose=False, quiet=True)
common.read_config()
fingerprint = index.extract_pubkey()[1].replace(" ", "").upper()
print("   Fingerprint:", fingerprint)
print("   Add to F-Droid:", common.config["repo_url"] + "?fingerprint=" + fingerprint)'
}

cmd_release() {
	load_env
	check_bunny_env
	cd "$REPO_DIR"
	# The build overwrites the generated map icons in OsmAnd/res with those of OsmAnd-resources
	if ! git diff --quiet HEAD -- . ':!OsmAnd/res/drawable*/h_*' ':!OsmAnd/res/drawable*/mm_*' \
		':!OsmAnd/res/drawable*/mx_*'; then
		echo "The working tree has uncommitted changes; commit them first" >&2
		exit 1
	fi
	local released
	released=$(git tag --points-at HEAD --list 'v*-sp*')
	if [ -n "$released" ]; then
		echo "HEAD is already released as $released; run $0 publish to upload it again" >&2
		exit 1
	fi

	local base_name base_code n version code previous
	base_name=$(sed -n 's/^\t\tversionName "\([^"]*\)"$/\1/p' OsmAnd/build.gradle)
	base_code=$(sed -n 's/^\t\tversionCode \([0-9]*\)$/\1/p' OsmAnd/build.gradle)
	n=$(git tag --list "v$base_name-sp*" | sed 's/.*-sp//' | sort -n | tail -1)
	n=$((${n:-0} + 1))
	if [ "$n" -gt 99 ]; then
		echo "Release number $n does not fit in the versionCode" >&2
		exit 1
	fi
	version=$base_name-sp$n
	code=$((base_code * 100 + n))
	previous=$(git describe --tags --abbrev=0 --match 'v*-sp*' 2> /dev/null || true)
	echo ">> Releasing $version (versionCode $code)${previous:+, previous release $previous}"

	APK_NUMBER_VERSION=$code APK_VERSION=$version APP_EDITION=$(date +%F) \
		"$SCRIPT_DIR/build-apk.sh" "$TASK"
	local apk
	apk=$(ls -t OsmAnd/build/outputs/apk/*/release/*.apk | head -1)

	echo ">> Adding $apk to the repository"
	mkdir -p "$FDROID_DIR/metadata/$APP_ID/en-US/changelogs"
	# fdroid update reads the repository icon from the repository folder and the app icon from the metadata:
	# R8 shortens the resource paths of the release APK, so fdroidserver cannot find the icon in it
	cp OsmAnd/res/mipmap-xxxhdpi/icon_speed_pedelec.png "$FDROID_DIR/icon.png"
	cp OsmAnd/res/mipmap-xxxhdpi/icon_speed_pedelec.png "$FDROID_DIR/metadata/$APP_ID/en-US/icon.png"
	local notes=$FDROID_DIR/metadata/$APP_ID/en-US/changelogs/$code.txt
	if [ -n "${NOTES_FILE:-}" ]; then
		cp "$NOTES_FILE" "$notes"
	elif [ -n "$previous" ]; then
		# Only the fork's own commits, not the OsmAnd commits merged in since; F-Droid shows up to 500 characters
		local upstream=()
		if git rev-parse --verify -q origin/master > /dev/null; then
			upstream=(--not origin/master)
		fi
		git log --no-merges --format='- %s' "$previous..HEAD" "${upstream[@]}" | head -c 500 > "$notes"
	fi
	touch "$FDROID_DIR/releases"
	sed -i "/^$code /d" "$FDROID_DIR/releases"
	echo "$code $version" >> "$FDROID_DIR/releases"
	write_metadata
	cp "$apk" "$FDROID_DIR/repo/${APP_ID}_$code.apk"
	fdroid_container "$FDROID_IMAGE" fdroid update

	git tag -a "v$version" -m "OsmAnd Speed Pedelec $version (versionCode $code)"
	echo ">> Tagged v$version"
	cmd_publish
}

# The app's metadata: the template in build-container/fdroid/metadata, a build entry per release in
# $FDROID_DIR/releases ("versionCode versionName" per line) and the latest release as current version.
# The index only carries a version's release notes when the version has a build entry. Never mark a build
# entry "disable": fdroid update deletes the APK of a disabled build.
write_metadata() {
	local metadata=$FDROID_DIR/metadata/$APP_ID.yml code name
	cp "$SCRIPT_DIR/fdroid/metadata/$APP_ID.yml" "$metadata"
	printf '\nBuilds:\n' >> "$metadata"
	while read -r code name; do
		printf '  - versionName: %s\n    versionCode: %s\n    commit: v%s\n' "$name" "$code" "$name" >> "$metadata"
	done < <(sort -n "$FDROID_DIR/releases")
	read -r code name < <(sort -n "$FDROID_DIR/releases" | tail -1)
	printf '\nCurrentVersion: %s\nCurrentVersionCode: %s\n' "$name" "$code" >> "$metadata"
}

check_bunny_env() {
	if [ -z "${BUNNY_STORAGE_ZONE:-}" ] || [ -z "${BUNNY_STORAGE_PASSWORD:-}" ]; then
		echo "Set BUNNY_STORAGE_ZONE and BUNNY_STORAGE_PASSWORD in $ENV_FILE" >&2
		exit 1
	fi
}

cmd_publish() {
	load_env
	check_bunny_env
	local dry_run=()
	if [ "${DRY_RUN:-0}" = 1 ]; then
		dry_run=(--dry-run)
	fi
	echo ">> Uploading the repository to $CDN_URL/$REMOTE_PREFIX"
	local folders=(repo)
	if [ -d "$FDROID_DIR/archive" ]; then
		folders+=(archive)
	fi
	fdroid_container -e BUNNY_STORAGE_ZONE -e BUNNY_STORAGE_PASSWORD -e BUNNY_STORAGE_HOST -e BUNNY_API_KEY -e CDN_URL \
		"$FDROID_IMAGE" python3 /scripts/bunny_sync.py "${dry_run[@]}" /repo "$REMOTE_PREFIX" "${folders[@]}"
	echo ">> Repository: $CDN_URL/$REMOTE_PREFIX/repo"
	fingerprint
}

case "$COMMAND" in
	init) cmd_init ;;
	release) cmd_release ;;
	publish) cmd_publish ;;
	fingerprint) fingerprint ;;
	shell) fdroid_container "$FDROID_IMAGE" bash ;;
	*)
		sed -n '2,/^set -e/p' "$0" | sed '$d; s/^# \{0,1\}//'
		exit 1
		;;
esac
