#!/bin/sh
# 设备插好后运行：装 APK、启动、上传 TTS 自检页、打开书库
set -e
ADB=${ADB:-adb}
APP_NAME="${APP_NAME:-AI+自学}"
APK="$(dirname "$0")/$APP_NAME.apk"
PKG=com.local.htmlkiosk
BOX=/sdcard/Android/data/$PKG/files/library

echo "==> 等待设备"
$ADB wait-for-device
$ADB devices -l

echo "==> 安装（保留数据）"
$ADB install -r "$APK"

echo "==> 推送 TTS 自检页"
$ADB shell mkdir -p "$BOX" 
$ADB push "$(dirname "$0")/verify/tts-test.html" "$BOX/发音自检.html"

echo "==> 启动"
$ADB shell am force-stop $PKG
$ADB shell am start -n $PKG/.MainActivity

echo "==> 检查 TTS 引擎"
$ADB shell settings get secure tts_default_synth || true
$ADB shell pm list packages | grep -i tts || echo "（没查到 TTS 包）"
