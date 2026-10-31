# Release versions, sourced by the CI scripts.
#
# VeloPilot has its own version, independent of OsmAnd's. Releases are tagged vMAJOR.MINOR.PATCH and get
# versionCode MAJOR * 10000 + MINOR * 100 + PATCH, so MINOR and PATCH stay below 100. The first release takes
# the versionName of the velopilot flavour in OsmAnd/build.gradle.
RELEASE_TAG_PATTERN='v[0-9]*.[0-9]*.[0-9]*'
RELEASE_TAG_REGEX='^v([0-9]+)\.([0-9]+)\.([0-9]+)$'

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
