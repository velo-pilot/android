#!/usr/bin/env bash
# Runs a Gradle APK task in GitLab CI, after prepare-android.sh.
#
#   build-container/ci/build-apk.sh TASK
#
# In a tag pipeline, the release tag sets versionName and versionCode (see version.sh), and the APK is signed
# with the key in APK_KEYSTORE_BASE64 (base64 of the PKCS12 keystore), APK_KEY_ALIAS and APK_KEY_PASSWORD.
# The APK is copied to apk/.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
. build-container/ci/version.sh
TASK=$1

if [ -n "${CI_COMMIT_TAG:-}" ]; then
	APK_NUMBER_VERSION=$(release_version_code "$CI_COMMIT_TAG")
	export APK_NUMBER_VERSION APK_VERSION=${CI_COMMIT_TAG#v}
	echo ">> $APK_VERSION, versionCode $APK_NUMBER_VERSION"
fi
export APP_EDITION=${CI_COMMIT_TIMESTAMP%%T*}
case "$TASK" in
	*Release)
		if [ -z "${APK_KEYSTORE_BASE64:-}" ] || [ -z "${APK_KEY_PASSWORD:-}" ]; then
			echo "APK_KEYSTORE_BASE64 and APK_KEY_PASSWORD are not set; they are protected variables, so the" \
				"tag must be protected too" >&2
			exit 1
		fi
		export OSMAND_APK_KEYSTORE=/tmp/apk-signing.p12
		export OSMAND_APK_KEY_ALIAS=${APK_KEY_ALIAS:-velopilot} OSMAND_APK_PASSWORD=$APK_KEY_PASSWORD
		(umask 077 && base64 -d <<< "$APK_KEYSTORE_BASE64" > "$OSMAND_APK_KEYSTORE")
		trap 'rm -f "$OSMAND_APK_KEYSTORE"' EXIT
		;;
esac

# Packaging OsmAnd-java runs its whole test suite, and some upstream tests fail against the current
# OsmAnd-resources (see build-container/README.md)
stamp=$(mktemp)
./gradlew --no-daemon \
	"-Dorg.gradle.jvmargs=-Xmx${GRADLE_XMX:-6g} -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8" \
	"-Pkotlin.daemon.jvmargs=-Xmx3g" \
	"$TASK" -x :OsmAnd-java:test

rm -rf apk
mkdir apk
find OsmAnd/build/outputs/apk -name '*.apk' -newer "$stamp" -exec cp {} apk/ \;
ls -l apk
