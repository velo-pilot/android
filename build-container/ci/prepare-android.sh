#!/usr/bin/env bash
# Prepares a CI job for the Gradle build (.github/actions/velopilot-apk): installs the Android SDK packages from
# versions.gradle in $ANDROID_HOME and puts OsmAnd-resources (in $CACHE_ROOT) next to the checkout, where the build
# reads it.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

if ! command -v git > /dev/null || ! command -v unzip > /dev/null || ! command -v curl > /dev/null; then
	apt-get update -qq
	apt-get install -y -qq --no-install-recommends ca-certificates curl git unzip > /dev/null
fi

compile_sdk=$(sed -n 's/.*osmand_compileSdk *= *\([0-9]*\).*/\1/p' versions.gradle)
build_tools=$(sed -n 's/.*osmand_buildToolsVersion *= *"\([^"]*\)".*/\1/p' versions.gradle)
tools_zip=$(sed -n 's/^ARG CMDLINE_TOOLS_ZIP=//p' build-container/Containerfile)
tools_sha1=$(sed -n 's/^ARG CMDLINE_TOOLS_SHA1=//p' build-container/Containerfile)
sdkmanager=$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager

if [ ! -x "$sdkmanager" ]; then
	echo ">> Installing the Android command-line tools in $ANDROID_HOME"
	curl -fsSLo /tmp/cmdline-tools.zip "https://dl.google.com/android/repository/$tools_zip"
	echo "$tools_sha1  /tmp/cmdline-tools.zip" | sha1sum -c -
	rm -rf "$ANDROID_HOME/cmdline-tools"
	mkdir -p "$ANDROID_HOME/cmdline-tools"
	unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools"
	mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
	rm /tmp/cmdline-tools.zip
fi
if [ ! -d "$ANDROID_HOME/platforms/android-$compile_sdk" ] || [ ! -d "$ANDROID_HOME/build-tools/$build_tools" ]; then
	echo ">> Installing Android SDK $compile_sdk and build tools $build_tools"
	{ yes || true; } | "$sdkmanager" --licenses > /dev/null
	"$sdkmanager" --install "platforms;android-$compile_sdk" "build-tools;$build_tools"
fi

resources=$CACHE_ROOT/resources
if [ ! -d "$resources/.git" ]; then
	git clone --depth 1 https://github.com/osmandapp/OsmAnd-resources.git "$resources"
else
	git -C "$resources" fetch --quiet --depth 1 origin HEAD
	git -C "$resources" reset --quiet --hard FETCH_HEAD
fi
ln -sfn "$resources" ../resources
echo ">> OsmAnd-resources $(git -C "$resources" log -1 --format='%h %cs')"
