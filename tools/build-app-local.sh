#!/usr/bin/env bash
#
# build-app-local.sh — build the AmberControl APK outside a full Android
# tree, for quick iteration/CI on macOS or Linux. The authoritative build is
# still the ROM's Android.bp (vendor/dc1/AmberControl); this script mirrors
# that toolchain (aapt2 → javac → d8 → sign) so the app can be compiled and
# inspected without a 150 GB tree.
#
# Requirements: JDK 17, Android SDK cmdline-tools (sdkmanager) with
#   platforms;android-33 and build-tools.
#   Env ANDROID_HOME, else /opt/homebrew/share/android-commandlinetools.
#
# Usage: build-app-local.sh [out.apk]
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/AmberControl"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT33="$SDK/build-tools/33.0.2"
PLATFORM="$SDK/platforms/android-33/android.jar"
OUT="${1:-$ROOT/out-app}"
APK="${OUT}/AmberControl.apk"

: "${JAVA_HOME:=$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home)}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

[ -f "$PLATFORM" ] || { echo "missing android.jar ($PLATFORM); run: sdkmanager 'platforms;android-33'" >&2; exit 1; }
[ -x "$BT33/aapt2" ] || { echo "missing build-tools 33.0.2; run: sdkmanager 'build-tools;33.0.2'" >&2; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT"/{gen,classes,flat,dex}

echo "== 1/6 hidden-API stub (system_app needs platform APIs) =="
mkdir -p "$OUT/stubs" "$OUT/classes_stub"
cat > "$OUT/stubs/SystemProperties.java" <<'EOF'
package android.os;

/** compile-time stub of the hidden API used by AmberControl; real impl ships in the ROM. */
public final class SystemProperties {
    public static String get(String key) { return ""; }
    public static String get(String key, String def) { return def; }
}
EOF
javac --release 8 -d "$OUT/classes_stub" "$OUT/stubs/SystemProperties.java"
jar cf "$OUT/stub.jar" -C "$OUT/classes_stub" .

echo "== 2/6 resources =="
"$BT33/aapt2" compile --dir "$APP/res" -o "$OUT/flat/"
"$BT33/aapt2" link -o "$OUT/app-unsigned.apk" \
  -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  "$OUT"/flat/*.flat

echo "== 3/6 java =="
SOURCES=$(find "$APP/src" "$OUT/gen" -name '*.java')
javac --release 8 -proc:none \
  -classpath "$PLATFORM:$OUT/stub.jar" \
  -d "$OUT/classes" $SOURCES
echo "   compiled $(find "$OUT/classes" -name '*.class' | wc -l | tr -d ' ') classes"

echo "== 4/6 dex =="
"$BT33/d8" --release --lib "$PLATFORM" --min-api 33 \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "== 5/6 package =="
cp "$OUT/app-unsigned.apk" "$APK"
( cd "$OUT/dex" && zip -q "$APK" classes.dex )
"$BT33/zipalign" -f 4 "$APK" "$APK.aligned" && mv "$APK.aligned" "$APK"

echo "== 6/6 sign (debug key) =="
KS="$OUT/debug.keystore"
keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
  -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
  -keyalg RSA -keysize 2048 -validity 10950 2>/dev/null
"$BT33/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android "$APK"

echo
echo "Built: $APK"
"$BT33/aapt2" dump badging "$APK" | head -4
ls -lh "$APK"
