#!/usr/bin/env bash
# Publishes the APK in apk/ as a GitHub release, after next-release.sh and build-apk.sh. Runs in GitHub Actions.
#
#   build-container/ci/publish-release.sh dev|beta|release
#
# Creates RELEASE_TAG at the built commit with release-notes.md; dev builds and betas are pre-releases. Keeps the
# newest KEEP_DEV_BUILDS dev builds (default 5) and deletes older ones with their tags. Then asks velo-pilot/fdroid
# to update its dev and beta repositories, when FDROID_DISPATCH_TOKEN is set; the release repository is published
# from a checkout of velo-pilot/fdroid with release.sh.
#
# Environment: GH_TOKEN (contents: write), GITHUB_REPOSITORY, GITHUB_SHA, RELEASE_TAG, APK_VERSION,
# FDROID_DISPATCH_TOKEN (optional)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
CHANNEL=${1:?dev, beta or release}

apks=(apk/*.apk)
if [ "${#apks[@]}" -ne 1 ] || [ ! -f "${apks[0]}" ]; then
	echo "expected one APK in apk/, found: ${apks[*]}" >&2
	exit 1
fi
case $CHANNEL in
	dev) app=velopilot-dev title="VeloPilot Dev $APK_VERSION" prerelease=(--prerelease) ;;
	beta) app=velopilot-beta title="VeloPilot Beta $APK_VERSION" prerelease=(--prerelease) ;;
	release) app=velopilot title="VeloPilot $APK_VERSION" prerelease=(--latest) ;;
	*) echo "unknown channel $CHANNEL" >&2; exit 1 ;;
esac
asset="apk/$app-$APK_VERSION.apk"
mv "${apks[0]}" "$asset"

echo ">> Creating the release $RELEASE_TAG"
gh release create "$RELEASE_TAG" "$asset" --repo "$GITHUB_REPOSITORY" --target "$GITHUB_SHA" \
	--title "$title" --notes-file release-notes.md "${prerelease[@]}"

if [ "$CHANNEL" = dev ]; then
	gh release list --repo "$GITHUB_REPOSITORY" --limit 200 --json tagName,createdAt \
		--jq 'map(select(.tagName | test("-dev\\.[0-9]+$"))) | sort_by(.createdAt) | reverse | .[].tagName' \
		| tail -n "+$(( ${KEEP_DEV_BUILDS:-5} + 1 ))" | while read -r old; do
		echo ">> Deleting the old dev build $old"
		gh release delete "$old" --repo "$GITHUB_REPOSITORY" --cleanup-tag --yes
	done
fi

if [ "$CHANNEL" = release ]; then
	echo ">> Publish it in the F-Droid release repository with release.sh in velo-pilot/fdroid"
elif [ -n "${FDROID_DISPATCH_TOKEN:-}" ]; then
	echo ">> Asking velo-pilot/fdroid to update its repositories"
	printf 'Authorization: Bearer %s\n' "$FDROID_DISPATCH_TOKEN" | curl --fail-with-body -sS -X POST -H @- \
		-H "Accept: application/vnd.github+json" \
		https://api.github.com/repos/velo-pilot/fdroid/dispatches -d '{"event_type":"android-release"}'
else
	echo ">> FDROID_DISPATCH_TOKEN is not set: velo-pilot/fdroid picks this up within 6 hours"
fi
