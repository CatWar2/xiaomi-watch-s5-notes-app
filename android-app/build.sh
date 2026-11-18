#!/usr/bin/env bash
#
# build.sh - Compile, package, and sign WatchNotesCompanion.apk using Android SDK CLI tools.
#
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
PLATFORM_JAR="$(ls -d "$ANDROID_HOME"/platforms/android-*/android.jar | sort -V | tail -1)"
BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"

OUT_DIR="$APP_DIR/build"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/res_compiled" "$OUT_DIR/gen" "$OUT_DIR/classes" "$OUT_DIR/dex"

# 1. Compile resources & link base APK
"$BUILD_TOOLS/aapt2" compile --dir "$APP_DIR/res" -o "$OUT_DIR/res_compiled/res.zip"
"$BUILD_TOOLS/aapt2" link \
    -o "$OUT_DIR/unaligned.apk" \
    -I "$PLATFORM_JAR" \
    --manifest "$APP_DIR/AndroidManifest.xml" \
    --java "$OUT_DIR/gen" \
    "$OUT_DIR/res_compiled/res.zip"

# 2. Compile Java sources
javac -source 8 -target 8 \
    -bootclasspath "$PLATFORM_JAR" \
    -d "$OUT_DIR/classes" \
    "$OUT_DIR"/gen/com/custom/watchnotes/companion/R.java \
    "$APP_DIR"/src/com/custom/watchnotes/companion/*.java

# 3. Convert class files to DEX
"$BUILD_TOOLS/d8" \
    --min-api 26 \
    --lib "$PLATFORM_JAR" \
    --output "$OUT_DIR/dex" \
    "$OUT_DIR"/classes/com/custom/watchnotes/companion/*.class

# 4. Add classes.dex into APK
python3 -c '
import zipfile
with zipfile.ZipFile("'"$OUT_DIR/unaligned.apk"'", "a") as zf:
    zf.write("'"$OUT_DIR/dex/classes.dex"'", "classes.dex")
'

# 5. Zipalign
"$BUILD_TOOLS/zipalign" -f 4 "$OUT_DIR/unaligned.apk" "$OUT_DIR/aligned.apk"

# 6. Sign with debug keystore (create if missing)
KEYSTORE="$APP_DIR/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storepass android \
        -alias androiddebugkey \
        -keypass android \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=Watch Notes Debug,O=Custom,C=US" >/dev/null 2>&1
fi

FINAL_APK="$OUT_DIR/WatchNotesCompanion.apk"
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --ks-key-alias androiddebugkey \
    --key-pass pass:android \
    --out "$FINAL_APK" \
    "$OUT_DIR/aligned.apk"

echo "Built: $FINAL_APK"
