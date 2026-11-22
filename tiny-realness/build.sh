#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"

# Auto-detect Android SDK on macOS / Linux
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
  SDK="$ANDROID_HOME"
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
  SDK="$ANDROID_SDK_ROOT"
elif [ -d "$HOME/Library/Android/sdk" ]; then
  SDK="$HOME/Library/Android/sdk"
elif [ -d "$HOME/Android/Sdk" ]; then
  SDK="$HOME/Android/Sdk"
else
  echo "Error: Android SDK not found. Set ANDROID_HOME." >&2
  exit 1
fi

BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -n 1)"
ANDROID_JAR="$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -n 1)"

if [ -z "$BT" ] || [ -z "$ANDROID_JAR" ]; then
  echo "Error: build-tools or android.jar not found in $SDK" >&2
  exit 1
fi

# Load local gitignored keystore.properties as fallback when env vars are not set
if [ -f "$ROOT/keystore.properties" ]; then
  while IFS='=' read -r k v; do
    case "$k" in
      storeFile)     REALNESS_KEYSTORE_PATH="${REALNESS_KEYSTORE_PATH:-$v}" ;;
      storePassword) REALNESS_KEYSTORE_PASSWORD="${REALNESS_KEYSTORE_PASSWORD:-$v}" ;;
      keyAlias)      REALNESS_KEY_ALIAS="${REALNESS_KEY_ALIAS:-$v}" ;;
      keyPassword)   REALNESS_KEY_PASSWORD="${REALNESS_KEY_PASSWORD:-$v}" ;;
    esac
  done < "$ROOT/keystore.properties"
fi

KS_PATH="${REALNESS_KEYSTORE_PATH:-realness-release.keystore}"
case "$KS_PATH" in
  /*) KEYSTORE="$KS_PATH" ;;
  *)  KEYSTORE="$ROOT/$KS_PATH" ;;
esac

if [ ! -f "$KEYSTORE" ] || [ -z "${REALNESS_KEYSTORE_PASSWORD:-}" ] || [ -z "${REALNESS_KEY_ALIAS:-}" ] || [ -z "${REALNESS_KEY_PASSWORD:-}" ]; then
  echo "Error: Signing credentials missing. Provide REALNESS_KEYSTORE_* env vars or local keystore.properties." >&2
  exit 1
fi

VER="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$DIR/AndroidManifest.xml" | head -n 1)"
APK_NAME="realness-tiny ${VER}.apk"
BUILD="$DIR/build"

rm -rf "$BUILD/compiled" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" \
  "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk" "$BUILD/$APK_NAME"
mkdir -p "$BUILD/compiled" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "Building $APK_NAME (SDK: $(basename "$(dirname "$ANDROID_JAR")"), build-tools: $(basename "$BT"))"

echo "[1/5] aapt2 compile & link..."
"$BT/aapt2" compile --dir "$DIR/res" -o "$BUILD/compiled/res.zip"
"$BT/aapt2" link \
  -o "$BUILD/app-unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$DIR/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  "$BUILD/compiled/res.zip"

echo "[2/5] javac (pure Java 8, zero AndroidX/Kotlin)..."
javac -source 8 -target 8 \
  -Xlint:-options \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  $(find "$DIR/src" "$BUILD/gen" -name "*.java")

echo "[3/5] R8 shrink + optimize + DEX..."
java -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 \
  --release \
  --min-api 26 \
  --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" \
  --pg-conf "$DIR/proguard.pro" \
  --pg-map-output "$BUILD/mapping.txt" \
  $(find "$BUILD/classes" -name "*.class")

echo "[4/5] pack classes.dex & zipalign..."
(cd "$BUILD/dex" && zip -q -9 "$BUILD/app-unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

echo "[5/5] apksigner (release keystore)..."
"$BT/apksigner" sign \
  --v1-signing-enabled false \
  --ks "$KEYSTORE" \
  --ks-key-alias "$REALNESS_KEY_ALIAS" \
  --ks-pass "pass:$REALNESS_KEYSTORE_PASSWORD" \
  --key-pass "pass:$REALNESS_KEY_PASSWORD" \
  --out "$BUILD/$APK_NAME" \
  "$BUILD/app-aligned.apk"

"$BT/apksigner" verify --verbose "$BUILD/$APK_NAME" >/dev/null
cp "$BUILD/$APK_NAME" "$ROOT/$APK_NAME"

echo "=== DONE ==="
ls -lh "$ROOT/$APK_NAME"
stat -f "Exact size: %z bytes" "$ROOT/$APK_NAME" 2>/dev/null || stat -c "Exact size: %s bytes" "$ROOT/$APK_NAME"
