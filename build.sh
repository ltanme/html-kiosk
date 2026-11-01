#!/bin/bash
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
JAVA8="$HOME/.sdkman/candidates/java/8.0.452-amzn/bin"
JAVA17="$HOME/.sdkman/candidates/java/17.0.17-amzn/bin"

if [ ! -x "$BT/aapt2" ]; then echo "缺少 build-tools: $BT"; exit 1; fi
if [ ! -f "$PLATFORM" ]; then echo "缺少 android.jar: $PLATFORM"; exit 1; fi

# 签名口令：优先取环境变量 KS_PASS，其次取本地 .keystore-pass（不入库），
# 都没有就随机生成一个并写进 .keystore-pass。这样脚本里不出现任何口令。
KS_PASS="${KS_PASS:-}"
if [ -z "$KS_PASS" ] && [ -f "$PROJ/.keystore-pass" ]; then
  KS_PASS="$(cat "$PROJ/.keystore-pass")"
fi
if [ -z "$KS_PASS" ]; then
  KS_PASS="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"
  printf '%s' "$KS_PASS" > "$PROJ/.keystore-pass"
  chmod 600 "$PROJ/.keystore-pass"
fi
export KS_PASS

if [ ! -f "$PROJ/keystore.jks" ]; then
  "$JAVA8/keytool" -genkeypair -keystore "$PROJ/keystore.jks" -alias kiosk \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=HTML Kiosk, OU=Local, O=Local, L=Local, ST=Local, C=CN"
fi

rm -rf "$PROJ/build"
mkdir -p "$PROJ/build/classes" "$PROJ/build/dex"

echo "==> aapt2 compile"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$PROJ/build/res.zip"

echo "==> aapt2 link"
"$BT/aapt2" link \
  -o "$PROJ/build/base.apk" \
  -I "$PLATFORM" \
  --manifest "$PROJ/AndroidManifest.xml" \
  -A "$PROJ/assets" \
  -R "$PROJ/build/res.zip" \
  --min-sdk-version 26 \
  --target-sdk-version 34

echo "==> javac"
"$JAVA8/javac" -source 8 -target 8 -bootclasspath "$PLATFORM" -encoding UTF-8 \
  -d "$PROJ/build/classes" $(find "$PROJ/src" -name '*.java')

echo "==> d8"
PATH="$JAVA17:$PATH" "$BT/d8" --lib "$PLATFORM" --min-api 26 \
  --output "$PROJ/build/dex" $(find "$PROJ/build/classes" -name '*.class')

cd "$PROJ/build"
zip -q -j base.apk dex/classes.dex

echo "==> zipalign"
"$BT/zipalign" -f 4 base.apk aligned.apk

echo "==> sign"
"$BT/apksigner" sign --ks "$PROJ/keystore.jks" \
  --ks-pass env:KS_PASS --key-pass env:KS_PASS \
  --out "$PROJ/AI+自学.apk" aligned.apk

"$BT/apksigner" verify --print-certs "$PROJ/AI+自学.apk" | head -5
ls -lh "$PROJ/AI+自学.apk"
echo "DONE"