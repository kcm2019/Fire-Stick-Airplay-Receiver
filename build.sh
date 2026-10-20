#!/usr/bin/env bash
# Manual APK build for the AirPlay TV receiver.
# Gradle does not run in this sandbox (daemon fails), so the pipeline is:
#   aapt2 compile/link -> javac (core w/ Lombok, app w/ R.java)
#   -> strip MethodParameters (D8 bug workaround) -> jar-ify -> d8
#   -> stage -> zipalign -> apksigner
#
# Env you must have (or export before calling):
#   JAVA_HOME=~/toolchains/jdk-17.0.20.1+1
#   ANDROID_HOME=~/android-sdk            (build-tools 34.0.0, platform android-34)
set -euo pipefail

: "${JAVA_HOME:?set JAVA_HOME to the JDK 17 dir}"
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK dir}"
export PATH="$JAVA_HOME/bin:$PATH"

ROOT="$(cd "$(dirname "$0")" && pwd)"
BT="$ANDROID_HOME/build-tools/34.0.0"
AJ="$ANDROID_HOME/platforms/android-34/android.jar"
OUT="$ROOT/out"
KEYSTORE="$ROOT/debug.keystore"
APK_NAME="airplay-tv-receiver-1.0.19.apk"
W=/tmp/apbuild

rm -rf "$W"
mkdir -p "$OUT" "$W/compiled" "$W/gen" "$W/classes-core" "$W/classes-app" "$W/dex" "$W/stage" "$W/jars"

echo "== aapt2 compile =="
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$W/compiled"

echo "== aapt2 link =="
"$BT/aapt2" link -o "$W/resources.apk" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  -I "$AJ" --java "$W/gen" \
  "$W"/compiled/*

NETTY_JARS="$ROOT/libs/netty/netty-common.jar:$ROOT/libs/netty/netty-buffer.jar:$ROOT/libs/netty/netty-transport.jar:$ROOT/libs/netty/netty-resolver.jar:$ROOT/libs/netty/netty-codec.jar:$ROOT/libs/netty/netty-codec-http.jar:$ROOT/libs/netty/netty-handler.jar"
DEPS="$ROOT/libs/slf4j-api.jar:$ROOT/libs/jmdns.jar:$ROOT/libs/dd-plist.jar:$ROOT/libs/eddsa.jar:$ROOT/libs/curve25519.jar:$ROOT/libs/m3u8.jar:$NETTY_JARS"

echo "== javac core (Lombok) =="
find "$ROOT/airplay-core/src/main/java" -name '*.java' > "$W/core-sources.txt"
javac -encoding UTF-8 -source 17 -target 17 -nowarn \
  -cp "$AJ:$DEPS:$ROOT/libs/lombok.jar" \
  -processorpath "$ROOT/libs/lombok.jar" \
  -d "$W/classes-core" @"$W/core-sources.txt"

echo "== javac app =="
find "$ROOT/app/src/main/java" "$W/gen" -name '*.java' | grep -v '/alac/' > "$W/app-sources.txt"
javac -encoding UTF-8 -source 17 -target 17 -nowarn \
  -cp "$AJ:$W/classes-core:$DEPS" \
  -d "$W/classes-app" @"$W/app-sources.txt"

echo "== strip MethodParameters + jar-ify =="
# The build-tools 34 D8 crashes on the optional MethodParameters class
# attribute (null parameter names). Strip it everywhere; semantics unchanged.
python3 "$ROOT/tools/strip-methodparameters.py" dir "$W/classes-core" "$W/classes-core"
python3 "$ROOT/tools/strip-methodparameters.py" dir "$W/classes-app" "$W/classes-app"
for j in slf4j-api jmdns dd-plist eddsa curve25519 m3u8; do
  python3 "$ROOT/tools/strip-methodparameters.py" jar "$ROOT/libs/$j.jar" "$W/jars/$j.jar"
done
for j in $ROOT/libs/netty/*.jar; do
  python3 "$ROOT/tools/strip-methodparameters.py" jar "$j" "$W/jars/$(basename "$j")"
done
(cd "$W/classes-core" && zip -q -r "$W/jars/core-classes.jar" .)
(cd "$W/classes-app" && zip -q -r "$W/jars/app-classes.jar" .)

echo "== d8 =="
export JAVA_OPTS="-Xmx1280m"
"$BT/d8" --min-api 25 --lib "$AJ" --release \
  --output "$W/dex" \
  "$W/jars/core-classes.jar" "$W/jars/app-classes.jar" \
  "$W/jars/slf4j-api.jar" "$W/jars/jmdns.jar" "$W/jars/dd-plist.jar" \
  "$W/jars/eddsa.jar" "$W/jars/curve25519.jar" "$W/jars/m3u8.jar" \
  "$W/jars/netty-common.jar" "$W/jars/netty-buffer.jar" "$W/jars/netty-transport.jar" \
  "$W/jars/netty-resolver.jar" "$W/jars/netty-codec.jar" "$W/jars/netty-codec-http.jar" \
  "$W/jars/netty-handler.jar"

echo "dex files: $(ls "$W"/dex/*.dex | wc -l)"

echo "== stage =="
cd "$W/stage" && unzip -q "$W/resources.apk"
for d in "$W"/dex/*.dex; do cp "$d" ./; done
if [ -d "$ROOT/airplay-core/src/main/resources" ]; then
  cp -r "$ROOT/airplay-core/src/main/resources/"* ./
fi

echo "== zip unsigned =="
rm -f "$W/unsigned.apk"
(cd "$W/stage" && zip -q -r -9 "$W/unsigned.apk" .)

echo "== keystore =="
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias airplaytv \
    -keyalg RSA -keysize 2048 -validity 3650 \
    -storepass android -keypass android \
    -dname "CN=AirPlay TV Receiver, OU=dev, O=kurt, C=US" 2>/dev/null
fi

echo "== zipalign + apksigner =="
"$BT/zipalign" -f 4 "$W/unsigned.apk" "$W/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/$APK_NAME" "$W/aligned.apk"

echo "== verify =="
"$BT/apksigner" verify --print-certs "$OUT/$APK_NAME" | head -4
"$BT/aapt" dump badging "$OUT/$APK_NAME" | head -3

echo "APK: $OUT/$APK_NAME"
