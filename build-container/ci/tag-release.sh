#!/usr/bin/env bash
# Tags the pipeline's commit as the next release, which starts the release pipeline. Runs in the manual
# GitLab CI job release:tag, with the full history.
#
# The next release is RELEASE_VERSION when set (a variable of the manual job, e.g. 0.2.0), else the last
# release with its patch number one up, or for the first release the versionName in OsmAnd/build.gradle.
# The tag message holds the release notes that F-Droid shows: RELEASE_NOTES when set (a variable of the
# manual job), else the subjects of the commits since the last release that are not on UPSTREAM_BRANCH
# (OsmAnd's master). F-Droid shows up to 500 characters.
# RELEASE_TOKEN is a project access token (Maintainer, api scope) that may create protected tags;
# CI_JOB_TOKEN cannot create tags.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
. build-container/ci/version.sh

if [ -z "${RELEASE_TOKEN:-}" ]; then
	echo "RELEASE_TOKEN is not set; it is a protected variable, so run this job on a protected branch" >&2
	exit 1
fi
git fetch --quiet --force --tags origin
released=$(git tag --points-at HEAD --list "$RELEASE_TAG_PATTERN" | grep -E "$RELEASE_TAG_REGEX" || true)
if [ -n "$released" ]; then
	echo "This commit is already released as $released" >&2
	exit 1
fi

last=$(release_tags | head -1)
if [ -n "${RELEASE_VERSION:-}" ]; then
	tag=v${RELEASE_VERSION#v}
elif [ -n "$last" ]; then
	tag=${last%.*}.$((10#${last##*.} + 1))
else
	tag=v$(first_version)
fi
code=$(release_version_code "$tag")
if [ -n "$last" ] && [ "$code" -le "$(release_version_code "$last")" ]; then
	echo "$tag is not newer than the last release $last" >&2
	exit 1
fi
previous=$(git describe --tags --abbrev=0 --match "$RELEASE_TAG_PATTERN" 2> /dev/null || true)

notes=${RELEASE_NOTES:-}
if [ -z "$notes" ] && [ -n "$previous" ]; then
	upstream=()
	if git fetch --quiet origin "+refs/heads/${UPSTREAM_BRANCH:-master}:refs/remotes/origin/upstream" 2> /dev/null; then
		upstream=(--not origin/upstream)
	fi
	notes=$(git log --no-merges --format='- %s' "$previous..HEAD" "${upstream[@]}" | head -c 500)
fi
notes=${notes:-VeloPilot ${tag#v}}

echo ">> Tagging $CI_COMMIT_SHORT_SHA as $tag (versionCode $code)${previous:+, previous release $previous}"
echo "$notes"
curl --fail-with-body --silent --show-error --request POST \
	--header "PRIVATE-TOKEN: $RELEASE_TOKEN" \
	--data-urlencode "tag_name=$tag" \
	--data-urlencode "ref=$CI_COMMIT_SHA" \
	--data-urlencode "message=$notes" \
	"$CI_API_V4_URL/projects/$CI_PROJECT_ID/repository/tags" > /dev/null
echo
echo ">> The release pipeline: $CI_PROJECT_URL/-/pipelines?ref=$tag"
