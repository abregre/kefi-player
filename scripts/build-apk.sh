#!/usr/bin/env bash
# Builds the Android APK: Vite bundle -> Capacitor sync -> Gradle.
#
# Usage:
#   scripts/build-apk.sh            # debug APK
#   scripts/build-apk.sh release    # release APK (needs android/keystore.properties)
#   ABIS=arm64-v8a,x86_64 scripts/build-apk.sh   # restrict native ABIs
set -euo pipefail

cd "$(dirname "$0")/.."

VARIANT="${1:-debug}"
# Empty by default: this app has no native payload of its own, so every ABI
# Android supports is served by the same bundle and filtering saves nothing.
# Set ABIS only once a native library with per-ABI .so files is added.
ABIS="${ABIS:-}"

# This machine's global JAVA_HOME points at a JDK that is no longer installed,
# so resolve a usable JDK 17+ ourselves instead of trusting the environment.
resolve_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/javac" ]]; then
    echo "$JAVA_HOME"
    return
  fi
  for candidate in \
    /usr/lib/jvm/java-21-amazon-corretto \
    /usr/lib/jvm/java-21-openjdk-amd64 \
    /usr/lib/jvm/java-17-openjdk-amd64 \
    /usr/lib/jvm/default-java; do
    if [[ -x "${candidate}/bin/javac" ]]; then
      echo "$candidate"
      return
    fi
  done
  echo "No JDK 17+ found. Set JAVA_HOME and re-run." >&2
  exit 1
}

resolve_android_home() {
  for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
    if [[ -n "$candidate" && -d "$candidate/platforms" ]]; then
      echo "$candidate"
      return
    fi
  done
  echo "Android SDK not found. Set ANDROID_HOME and re-run." >&2
  exit 1
}

export JAVA_HOME="$(resolve_java_home)"
export ANDROID_HOME="$(resolve_android_home)"

echo "JDK:         $JAVA_HOME"
echo "Android SDK: $ANDROID_HOME"
echo "ABIs:        ${ABIS:-all}"
echo "Variant:     $VARIANT"
echo

npm run build
# ytmp3's own build-apk.sh omits this and silently ships a stale potoken.js;
# rebuild it here so the WebView bundle always matches src/potoken/index.ts.
npm run build:potoken
npx cap sync android

case "$VARIANT" in
  debug)   GRADLE_TASK=":app:assembleDebug" ;;
  release) GRADLE_TASK=":app:assembleRelease" ;;
  *) echo "Unknown variant '$VARIANT' (expected debug or release)" >&2; exit 1 ;;
esac

GRADLE_ARGS=("$GRADLE_TASK")
[[ -n "$ABIS" ]] && GRADLE_ARGS+=("-PabiFilters=$ABIS")

(cd android && ./gradlew "${GRADLE_ARGS[@]}")

APK=$(find "android/app/build/outputs/apk/$VARIANT" -name "*.apk" -print -quit)
echo
echo "APK: $APK"
du -h "$APK" | cut -f1 | sed 's/^/Size: /'
echo
echo "Install on a connected device with:"
echo "  adb install -r \"$APK\""
