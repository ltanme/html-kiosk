#!/bin/bash
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
JAVA8="$HOME/.sdkman/candidates/java/8.0.452-amzn/bin"
JAVA17="$HOME/.sdkman/candidates/java/17.0.17-amzn/bin"

# App 名字的唯一来源：桌面图标名、应用内标题、快捷方式、APK 文件名都取这里。
# 改名字只需 APP_NAME="新名字" ./build.sh，不用动 Java/清单/资源。
APP_NAME="${APP_NAME:-AI+自学}"
APP_NAME_XML="$(printf '%s' "$APP_NAME" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g')"

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
mkdir -p "$PROJ/build/classes" "$PROJ/build/dex" "$PROJ/build/gen" \
         "$PROJ/build/res-gen/values"

# 把名字写成字符串资源，清单用 @string/app_name 引用
cat > "$PROJ/build/res-gen/values/strings.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">$APP_NAME_XML</string>
</resources>
EOF

echo "==> aapt2 compile"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$PROJ/build/res.zip"
"$BT/aapt2" compile --dir "$PROJ/build/res-gen" -o "$PROJ/build/res-gen.zip"

echo "==> aapt2 link"
"$BT/aapt2" link \
  -o "$PROJ/build/base.apk" \
  -I "$PLATFORM" \
  --manifest "$PROJ/AndroidManifest.xml" \
  -A "$PROJ/assets" \
  -R "$PROJ/build/res.zip" \
  -R "$PROJ/build/res-gen.zip" \
  --auto-add-overlay \
  --java "$PROJ/build/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34

echo "==> javac"
"$JAVA8/javac" -source 8 -target 8 -bootclasspath "$PLATFORM" -encoding UTF-8 \
  -d "$PROJ/build/classes" \
  $(find "$PROJ/src" "$PROJ/build/gen" -name '*.java')

echo "==> d8"
PATH="$JAVA17:$PATH" "$BT/d8" --lib "$PLATFORM" --min-api 26 \
  --output "$PROJ/build/dex" $(find "$PROJ/build/classes" -name '*.class')

cd "$PROJ/build"
zip -q -j base.apk dex/classes.dex

echo "==> zipalign"
"$BT/zipalign" -f 4 base.apk aligned.apk

echo "==> sign"
APP_APK="$PROJ/$APP_NAME.apk"
"$BT/apksigner" sign --ks "$PROJ/keystore.jks" \
  --ks-pass env:KS_PASS --key-pass env:KS_PASS \
  --out "$APP_APK" aligned.apk

"$BT/apksigner" verify --print-certs "$APP_APK" | head -5
ls -lh "$APP_APK"
echo "DONE: $APP_NAME"