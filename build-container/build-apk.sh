#!/usr/bin/env bash
# Builds the OsmAnd APK in a podman container, without a local JDK or Android SDK.
#
#   build-container/build-apk.sh                                    # velopilot, OpenGL, arm64, debug
#   build-container/build-apk.sh assembleVelopilotOpenglFatDebug   # any Gradle task, extra args follow
#   build-container/build-apk.sh shell                              # shell in the build container
#
# Environment:
#   RESOURCES_DIR     OsmAnd-resources checkout; cloned if missing (default: next to this repository,
#                     where the Gradle build looks for it)
#   UPDATE_RESOURCES  1 = pull OsmAnd-resources before building (default 0)
#   CACHE_DIR         Gradle and Kotlin/Native caches (default ~/.cache/osmand-builder)
#   GRADLE_XMX        Gradle daemon heap (default 6g)
#   RUN_TESTS         1 = also run the OsmAnd-java tests that an APK build skips by default
#   REBUILD_IMAGE     1 = rebuild the container image
#   APK_NUMBER_VERSION, APK_VERSION, APP_EDITION
#                     versionCode, versionName and edition of the APK (see OsmAnd/build.gradle)
#   OSMAND_APK_KEYSTORE, OSMAND_APK_KEY_ALIAS, OSMAND_APK_PASSWORD
#                     release signing key; the keystore's folder is mounted read-only
set -euo pipefail

REPO_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
RESOURCES_DIR=${RESOURCES_DIR:-$(dirname "$REPO_DIR")/resources}
CACHE_DIR=${CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/osmand-builder}
GRADLE_XMX=${GRADLE_XMX:-6g}
TASK=${1:-assembleVelopilotOpenglArm64Debug}
shift || true

# Android SDK packages the build asks for
compile_sdk=$(sed -n 's/.*osmand_compileSdk *= *\([0-9]*\).*/\1/p' "$REPO_DIR/versions.gradle")
build_tools=$(sed -n 's/.*osmand_buildToolsVersion *= *"\([^"]*\)".*/\1/p' "$REPO_DIR/versions.gradle")
IMAGE=${IMAGE:-localhost/osmand-builder:sdk${compile_sdk}-bt${build_tools}}

if [ "${REBUILD_IMAGE:-0}" = 1 ] || ! podman image exists "$IMAGE"; then
	echo ">> Building image $IMAGE"
	podman build -t "$IMAGE" \
		--build-arg ANDROID_COMPILE_SDK="$compile_sdk" \
		--build-arg ANDROID_BUILD_TOOLS="$build_tools" \
		-f "$REPO_DIR/build-container/Containerfile" "$REPO_DIR/build-container"
fi

if [ ! -d "$RESOURCES_DIR/.git" ]; then
	echo ">> Cloning OsmAnd-resources into $RESOURCES_DIR"
	git clone --depth 1 https://github.com/osmandapp/OsmAnd-resources.git "$RESOURCES_DIR"
elif [ "${UPDATE_RESOURCES:-0}" = 1 ]; then
	echo ">> Updating OsmAnd-resources"
	git -C "$RESOURCES_DIR" pull --ff-only --depth 1
fi

mkdir -p "$CACHE_DIR/gradle" "$CACHE_DIR/konan"

# The build reads ../../resources from OsmAnd/ and OsmAnd-java/, so both checkouts sit side by side
label=""
if command -v selinuxenabled > /dev/null && selinuxenabled; then
	label=":z"
fi
run=(podman run --rm --init
	-v "$REPO_DIR:/work/OsmAnd$label"
	-v "$RESOURCES_DIR:/work/resources$label"
	-v "$CACHE_DIR/gradle:/cache/gradle$label"
	-v "$CACHE_DIR/konan:/cache/konan$label"
	-e GRADLE_USER_HOME=/cache/gradle
	-e KONAN_DATA_DIR=/cache/konan
	-w /work/OsmAnd)
if [ -t 0 ] && [ -t 1 ]; then
	run+=(-it)
fi
# -e NAME takes the value from this environment, so the password stays off the command line
for var in APK_NUMBER_VERSION APK_VERSION APP_EDITION OSMAND_APK_KEY_ALIAS OSMAND_APK_PASSWORD; do
	if [ -n "${!var:-}" ]; then
		run+=(-e "$var")
	fi
done
if [ -n "${OSMAND_APK_KEYSTORE:-}" ]; then
	keystore=$(realpath "$OSMAND_APK_KEYSTORE")
	run+=(-v "$(dirname "$keystore"):/keys:ro${label:+,z}" -e "OSMAND_APK_KEYSTORE=/keys/$(basename "$keystore")")
fi

if [ "$TASK" = shell ]; then
	exec "${run[@]}" "$IMAGE" bash
fi

# Packaging OsmAnd-java runs its whole test suite, and some upstream tests fail against the current
# OsmAnd-resources. Skip them for APK builds unless asked for.
skip_tests=()
case "$TASK" in
	assemble* | bundle*)
		if [ "${RUN_TESTS:-0}" != 1 ]; then
			skip_tests=(-x :OsmAnd-java:test)
		fi
		;;
esac

stamp=$(mktemp)
trap 'rm -f "$stamp"' EXIT
echo ">> ./gradlew $TASK ${skip_tests[*]} $*"
"${run[@]}" "$IMAGE" ./gradlew --no-daemon \
	"-Dorg.gradle.jvmargs=-Xmx$GRADLE_XMX -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8" \
	"-Pkotlin.daemon.jvmargs=-Xmx3g" \
	"$TASK" "${skip_tests[@]}" "$@"

apks=$(find "$REPO_DIR/OsmAnd/build/outputs/apk" -name '*.apk' -newer "$stamp" 2> /dev/null || true)
if [ -n "$apks" ]; then
	echo ">> APK:"
	echo "$apks"
fi
