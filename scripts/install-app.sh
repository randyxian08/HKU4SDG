#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/dev-env.sh"
./gradlew :guide:assembleDebug
adb install -r guide/build/outputs/apk/debug/guide-debug.apk
echo '已安装数字扶手（一个 APK）。请在系统无障碍设置中手动开启“数字扶手 · 手机引导”。'
