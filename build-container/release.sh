#!/usr/bin/env bash
# Creates the signing keys for the VeloPilot releases that GitLab CI builds and publishes (.gitlab-ci.yml).
#
#   build-container/release.sh init          # once: APK signing key and F-Droid repository key
#   build-container/release.sh variables     # print the CI/CD variables for the keys
#   build-container/release.sh fingerprint   # print the repository fingerprint and the link to add it
#
# Environment:
#   RELEASE_HOME   where the keys live (default ~/.local/share/velopilot-release); back it up
#   CDN_URL        public URL of the Bunny pull zone (default https://cdn.osmpedelec.be)
set -euo pipefail

RELEASE_HOME=${RELEASE_HOME:-${XDG_DATA_HOME:-$HOME/.local/share}/velopilot-release}
CDN_URL=${CDN_URL:-https://cdn.osmpedelec.be}
KEYTOOL_IMAGE=${KEYTOOL_IMAGE:-docker.io/library/eclipse-temurin:17-jdk-noble}
KEY_ALIAS=velopilot

label=""
if command -v selinuxenabled > /dev/null && selinuxenabled; then
	label=":z"
fi

keytool() {
	podman run --rm -v "$RELEASE_HOME:/keys$label" -w /keys -e KEY_PASSWORD "$KEYTOOL_IMAGE" keytool "$@"
}

load_passwords() {
	if [ ! -f "$RELEASE_HOME/passwords" ]; then
		echo "$RELEASE_HOME/passwords is missing; run $0 init first" >&2
		exit 1
	fi
	# shellcheck source=/dev/null
	. "$RELEASE_HOME/passwords"
}

random_password() {
	tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 32 || true
}

new_key() {
	KEY_PASSWORD=$2 keytool -genkeypair -keystore "$1" -storetype PKCS12 -alias "$KEY_ALIAS" \
		-keyalg RSA -keysize 4096 -validity 10000 -dname "$3" \
		-storepass:env KEY_PASSWORD -keypass:env KEY_PASSWORD
	chmod 600 "$RELEASE_HOME/$1"
}

cmd_init() {
	if [ -e "$RELEASE_HOME/passwords" ]; then
		echo "$RELEASE_HOME is already set up; init never replaces keys" >&2
		exit 1
	fi
	mkdir -p "$RELEASE_HOME"
	chmod 700 "$RELEASE_HOME"
	local apk_password fdroid_password
	apk_password=$(random_password)
	fdroid_password=$(random_password)
	(
		umask 077
		cat > "$RELEASE_HOME/passwords" <<-EOF
		APK_KEY_PASSWORD=$apk_password
		FDROID_KEYSTORE_PASSWORD=$fdroid_password
		EOF
	)
	echo ">> Creating the APK signing key"
	new_key apk-signing.p12 "$apk_password" "CN=VeloPilot"
	echo ">> Creating the F-Droid repository key"
	new_key fdroid-repo.p12 "$fdroid_password" "CN=VeloPilot F-Droid repository"
	cat <<-EOF

	>> Keys in $RELEASE_HOME. Back up that folder: without apk-signing.p12, users have to uninstall the app
	   to install a newer release; without fdroid-repo.p12, they have to add the repository again.

	EOF
	cmd_variables
}

cmd_variables() {
	load_passwords
	cat <<-EOF
	Add these CI/CD variables (Settings > CI/CD > Variables), all Protected and Masked:

	  APK_KEYSTORE_BASE64        $(base64 -w0 < "$RELEASE_HOME/apk-signing.p12")
	  APK_KEY_PASSWORD           $APK_KEY_PASSWORD
	  FDROID_KEYSTORE_BASE64     $(base64 -w0 < "$RELEASE_HOME/fdroid-repo.p12")
	  FDROID_KEYSTORE_PASSWORD   $FDROID_KEYSTORE_PASSWORD

	EOF
	cmd_fingerprint
}

cmd_fingerprint() {
	load_passwords
	local fingerprint
	fingerprint=$(KEY_PASSWORD=$FDROID_KEYSTORE_PASSWORD keytool -exportcert -keystore fdroid-repo.p12 \
		-alias "$KEY_ALIAS" -storepass:env KEY_PASSWORD | sha256sum | cut -d' ' -f1 | tr a-f A-F)
	echo "Repository fingerprint: $fingerprint"
	echo "Add to F-Droid: $CDN_URL/fdroid/repo?fingerprint=$fingerprint"
}

case "${1:-}" in
	init) cmd_init ;;
	variables) cmd_variables ;;
	fingerprint) cmd_fingerprint ;;
	*)
		sed -n '2,/^set -e/p' "$0" | sed '$d; s/^# \{0,1\}//'
		exit 1
		;;
esac
