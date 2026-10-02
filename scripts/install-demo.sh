#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/dev-env.sh"
./gradlew :guide:assembleDebug :demo-target:assembleDebug
adb install -r demo-target/build/outputs/apk/debug/demo-target-debug.apk
adb install -r guide/build/outputs/apk/debug/guide-debug.apk
echo '内部测试环境：已安装数字扶手与模拟目标两个 APK。用户产品只需安装 guide。'
