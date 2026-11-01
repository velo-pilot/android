# Versions of the VeloPilot builds, sourced by the CI scripts.
#
# VeloPilot has its own version, independent of OsmAnd's, and three apps (OsmAnd/build.gradle):
# - releases (be.velopilot.app), tags vMAJOR.MINOR.PATCH, versionCode MAJOR * 10000 + MINOR * 100 + PATCH, so MINOR
#   and PATCH stay below 100. The first release takes the versionName of the velopilot flavour.
# - betas (be.velopilot.beta), GitHub pre-releases vX.Y.Z-beta.N of the next release, versionCode that release's
#   versionCode * 100 + N, so N stays below 100.
# - dev builds (be.velopilot.dev) of every push to main, GitHub pre-releases vX.Y.Z-dev.RUN, versionCode RUN (the
#   run number of the dev workflow).
RELEASE_TAG_PATTERN='v[0-9]*.[0-9]*.[0-9]*'
RELEASE_TAG_REGEX='^v([0-9]+)\.([0-9]+)\.([0-9]+)$'
BETA_TAG_REGEX='^(v[0-9]+\.[0-9]+\.[0-9]+)-beta\.([0-9]+)$'

first_version() {
	sed -n 's/^\t\t\tversionName System.getenv("APK_VERSION") ?: "\([^"]*\)"$/\1/p' OsmAnd/build.gradle
}

# Release tags, newest first
release_tags() {
	git tag --list "$RELEASE_TAG_PATTERN" --sort=-version:refname | grep -E "$RELEASE_TAG_REGEX" || true
}

# Prints the versionCode of release tag $1
release_version_code() {
	if ! [[ $1 =~ $RELEASE_TAG_REGEX ]] || [ "${BASH_REMATCH[2]}" -gt 99 ] || [ "${BASH_REMATCH[3]}" -gt 99 ] \
		|| [ "$1" = v0.0.0 ]; then
		echo "$1 is not a release tag vMAJOR.MINOR.PATCH with MINOR and PATCH below 100" >&2
		return 1
	fi
	echo $((10#${BASH_REMATCH[1]} * 10000 + 10#${BASH_REMATCH[2]} * 100 + 10#${BASH_REMATCH[3]}))
}

# Prints the versionCode of beta tag $1
beta_version_code() {
	if ! [[ $1 =~ $BETA_TAG_REGEX ]] || [ "${BASH_REMATCH[2]}" -lt 1 ] || [ "${BASH_REMATCH[2]}" -gt 99 ]; then
		echo "$1 is not a beta tag vX.Y.Z-beta.N with N from 1 to 99" >&2
		return 1
	fi
	local n=${BASH_REMATCH[2]} release
	release=$(release_version_code "${BASH_REMATCH[1]}") || return 1
	echo $((release * 100 + 10#$n))
}

# The version the next release gets: the last release with its patch number one up, or the first version
next_release_version() {
	local last
	last=$(release_tags | head -1)
	if [ -n "$last" ]; then
		echo "${last:1:${#last}-1}" | awk -F. '{ printf "%d.%d.%d\n", $1, $2, $3 + 1 }'
	else
		first_version
	fi
}
