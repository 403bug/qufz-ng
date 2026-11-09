#!/bin/sh
# Build AntiTelemetryBlock (ATB) — libxposed API 102 modern module.
#
# Usage:
#   ./build.sh            -> out/ATB-1.3.apk
#   ./build.sh release    -> release/ATB-1.3.apk
#
# Override detection:
#   ANDROID_SDK=...  ANDROID_BUILD_TOOLS=...  ANDROID_JAR=...
#   ATB_KS=...       (otherwise build/atb-debug.jks is generated)
set -eu
cd "$(dirname "$0")"

VER=1.3
OUT_DIR=${1:-out}

SDK=${ANDROID_SDK:-$HOME/Library/Android/sdk}
[ -d "$SDK" ] || { echo "Android SDK not found: $SDK (set ANDROID_SDK)" >&2; exit 1; }

BT=${ANDROID_BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* 2>/dev/null | tail -1)}
AJAR=${ANDROID_JAR:-$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | tail -1)}
[ -n "$BT" ] && [ -d "$BT" ] || { echo "build-tools not found under $SDK" >&2; exit 1; }
[ -n "$AJAR" ] && [ -f "$AJAR" ] || { echo "android.jar not found under $SDK/platforms" >&2; exit 1; }

AAPT2="$BT/aapt2"
D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
for t in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER"; do
  [ -x "$t" ] || { echo "missing build tool: $t" >&2; exit 1; }
done

AAR=libs/libxposed-102.aar
XAPI=libs/aar/classes.jar
SVC_AAR=libs/service-102.0.0.aar
IFACE_AAR=libs/interface-102.0.0.aar
SVC=libs/service/classes.jar
IFACE=libs/interface/classes.jar

echo "[0/7] deps: build-tools=$(basename "$BT") android.jar=$AJAR"
if [ ! -f "$XAPI" ]; then
  mkdir -p libs/aar
  (cd libs/aar && unzip -o -q "../libxposed-102.aar")
fi
if [ ! -f "$SVC" ]; then
  mkdir -p libs/service
  (cd libs/service && unzip -o -q "../service-102.0.0.aar" classes.jar)
fi
if [ ! -f "$IFACE" ]; then
  mkdir -p libs/interface
  (cd libs/interface && unzip -o -q "../interface-102.0.0.aar" classes.jar)
fi

echo "[1/7] rules -> DefaultRules.java"
mkdir -p gen/com/eta/atb
python3 tools/gen_default_rules.py rules/default-rules.json gen/com/eta/atb/DefaultRules.java

echo "[2/7] javac"
rm -rf build/classes && mkdir -p build/classes
javac -encoding UTF-8 --release 11 -classpath "$AJAR:$XAPI:$SVC:$IFACE" \
  -d build/classes $(find gen module/src -name '*.java')

echo "[3/7] d8"
rm -rf build/dex build/libclasses && mkdir -p build/dex build/libclasses
(cd build/libclasses && unzip -o -q ../../libs/service/classes.jar && unzip -o -q ../../libs/interface/classes.jar)
"$D8" -JXmx1g --lib "$AJAR" --classpath "$XAPI" --min-api 33 \
  --output build/dex $(find build/classes build/libclasses -name '*.class')

echo "[4/7] aapt2"
"$AAPT2" compile --dir module/res -o build/res.zip
"$AAPT2" link -o build/base.apk -I "$AJAR" --manifest module/AndroidManifest.xml \
  -R build/res.zip --min-sdk-version 33 --target-sdk-version 34 --auto-add-overlay

echo "[5/7] package"
cp build/base.apk build/unsigned.apk
rm -rf build/stage && mkdir -p build/stage
cp build/dex/classes.dex build/stage/classes.dex
cp -r module/META-INF build/stage/
(cd build/stage && zip -q -r ../unsigned.apk classes.dex META-INF)

echo "[6/7] zipalign"
"$ZIPALIGN" -f -p 4 build/unsigned.apk build/aligned.apk

echo "[7/7] sign"
KS=${ATB_KS:-build/atb-debug.jks}
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias atb -keyalg RSA -keysize 4096 -validity 36500 \
    -dname "CN=AntiTelemetryBlock, O=ETA, C=CN" >/dev/null 2>&1
  echo "generated keystore: $KS (store/key pass: android)"
fi

mkdir -p "$OUT_DIR"
APK="$OUT_DIR/ATB-$VER.apk"
rm -f "$APK"
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias atb --out "$APK" build/aligned.apk

"$APKSIGNER" verify "$APK" >/dev/null
"$APKSIGNER" verify --print-certs "$APK" | head -3

if command -v md5sum >/dev/null 2>&1; then
  md5sum "$APK"
else
  echo "MD5 $(md5 -q "$APK")  $APK"
fi
ls -l "$APK"
echo "OK -> $APK"
