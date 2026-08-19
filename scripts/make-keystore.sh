#!/usr/bin/env bash
# Creates the release signing key and wires it into the Gradle build.
#
#   bash scripts/make-keystore.sh
#
# Run this once, ever. Android identifies an app by its signing key: every future
# update must be signed with the same one, or people have to uninstall before
# they can install your next build. Back up the keystore file AND the password.
#
# Override defaults with environment variables:
#   KEYSTORE=~/keys/kefi.jks ALIAS=kefi VALIDITY=10000 bash scripts/make-keystore.sh
set -euo pipefail

cd "$(dirname "$0")/.."

KEYSTORE="${KEYSTORE:-$HOME/kefi-player-release.jks}"
ALIAS="${ALIAS:-kefi-player}"
VALIDITY="${VALIDITY:-10000}"   # ~27 years
PROPS="android/keystore.properties"

if [[ -e "$KEYSTORE" ]]; then
  echo "A keystore already exists at: $KEYSTORE" >&2
  echo >&2
  echo "Refusing to overwrite it. Replacing a signing key means every already-" >&2
  echo "installed copy of the app must be uninstalled before it can be updated." >&2
  echo "If you are sure, move the old file aside first." >&2
  exit 1
fi

if ! command -v keytool >/dev/null; then
  echo "keytool not found. Install a JDK, or add its bin/ to PATH." >&2
  exit 1
fi

read -r -p "Your name or organisation (goes in the certificate) [Kefi Player]: " OWNER
OWNER="${OWNER:-Kefi Player}"

read -r -s -p "Choose a keystore password (at least 6 characters): " PASS; echo
read -r -s -p "Repeat it: " PASS_CONFIRM; echo

if [[ "$PASS" != "$PASS_CONFIRM" ]]; then
  echo "Those do not match. Nothing was created." >&2
  exit 1
fi
if (( ${#PASS} < 6 )); then
  echo "Too short: keytool requires at least 6 characters. Nothing was created." >&2
  exit 1
fi

# Handed to keytool through the environment rather than argv, so the password
# never appears in `ps` output for other users on the machine.
export KEFI_KS_PASS="$PASS"

# keytool defaults to PKCS12, which expects the key and store passwords to
# match -- so both are set to the same value.
keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 \
  -validity "$VALIDITY" \
  -storepass:env KEFI_KS_PASS \
  -keypass:env KEFI_KS_PASS \
  -dname "CN=$OWNER"

chmod 600 "$KEYSTORE"

# Read by android/app/build.gradle. Git-ignored; created with tight permissions
# because it holds the password in the clear.
umask 077
cat > "$PROPS" <<PROPS_EOF
storeFile=$KEYSTORE
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
PROPS_EOF
chmod 600 "$PROPS"

echo
echo "Keystore:    $KEYSTORE"
echo "Gradle conf: $PROPS (git-ignored)"
keytool -list -v -keystore "$KEYSTORE" -alias "$ALIAS" \
  -storepass:env KEFI_KS_PASS 2>/dev/null |
  grep -E "Valid from|SHA256:" | head -2
echo
echo "=============================================================="
echo " BACK UP $KEYSTORE"
echo " AND THE PASSWORD, somewhere outside this project."
echo " Lose either one and you can never update the app for anyone"
echo " who already installed it."
echo "=============================================================="
echo
echo "Now build a shareable APK:   npm run apk:release"
