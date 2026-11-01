#!/usr/bin/env bash
# Works out the version of a VeloPilot build in GitHub Actions (see version.sh) and writes its release notes.
#
#   build-container/ci/next-release.sh dev|beta|release >> "$GITHUB_ENV"
#
# Prints APK_VERSION (versionName), APK_NUMBER_VERSION (versionCode), RELEASE_TAG and PREVIOUS_TAG, and writes the
# release notes to release-notes.md. F-Droid shows up to 500 characters of them.
#
# Environment:
#   RELEASE_VERSION   beta and release: the version, e.g. 0.2.0, instead of the next one
#   RELEASE_NOTES     beta and release: the notes, instead of the commit subjects since the previous release or beta
#   GITHUB_RUN_NUMBER, GITHUB_EVENT_PATH   dev: set by GitHub Actions
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
. build-container/ci/version.sh
CHANNEL=${1:?dev, beta or release}
rm -f release-notes.md

# The release tags, without their history (git fetch fails when there are none yet)
if [ -n "$(git ls-remote --tags --refs origin 'v*')" ]; then
	git fetch --quiet --force --depth=1 origin '+refs/tags/v*:refs/tags/v*'
fi
version=${RELEASE_VERSION:-$(next_release_version)}
version=${version#v}
previous=""
case $CHANNEL in
	dev)
		name=$version-dev.$GITHUB_RUN_NUMBER
		code=$GITHUB_RUN_NUMBER
		# The commits of the push, or the last commit
		{ jq -r '.commits[]?.message | split("\n")[0] | "- " + .' "$GITHUB_EVENT_PATH" 2> /dev/null || true; } \
			> release-notes.md
		[ -s release-notes.md ] || git log -1 --format='- %s' > release-notes.md
		;;
	beta)
		previous_beta=$(git tag --list "v$version-beta.*" --sort=-version:refname | grep -E "$BETA_TAG_REGEX" | head -1 || true)
		n=$(( ${previous_beta##*.} + 1 ))
		name=$version-beta.$n
		code=$(beta_version_code "v$name")
		previous=${previous_beta:-$(release_tags | head -1)}
		;;
	release)
		name=$version
		code=$(release_version_code "v$name")
		previous=$(release_tags | head -1)
		if [ -n "$previous" ] && [ "$code" -le "$(release_version_code "$previous")" ]; then
			echo "v$name is not newer than the last release $previous" >&2
			exit 1
		fi
		;;
	*)
		echo "unknown channel $CHANNEL" >&2
		exit 1
		;;
esac
tag=v$name
if git rev-parse --quiet --verify "refs/tags/$tag" > /dev/null; then
	echo "$tag exists already" >&2
	exit 1
fi

if [ "$CHANNEL" != dev ]; then
	if [ -n "${RELEASE_NOTES:-}" ]; then
		printf '%s\n' "$RELEASE_NOTES" > release-notes.md
	elif [ -n "$previous" ]; then
		# The commits on main since the previous tag, without those that merges brought in from OsmAnd
		since=$(git log -1 --format=%cI "$previous")
		git fetch --quiet --shallow-since="$(date -u -d "$since - 1 day" +%Y-%m-%d)" origin "$GITHUB_SHA" 2> /dev/null \
			|| git fetch --quiet --deepen=500 origin "$GITHUB_SHA"
		git log --first-parent --no-merges --format='- %s' "$previous..HEAD" | head -c 500 > release-notes.md
	fi
	[ -s release-notes.md ] || echo "VeloPilot $name" > release-notes.md
fi

echo ">> $tag, versionCode $code${previous:+, previous $previous}" >&2
cat release-notes.md >&2
echo "APK_VERSION=$name"
echo "APK_NUMBER_VERSION=$code"
echo "RELEASE_TAG=$tag"
echo "PREVIOUS_TAG=$previous"
