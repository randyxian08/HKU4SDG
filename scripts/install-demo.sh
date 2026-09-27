#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/dev-env.sh"
./gradlew :guide:assembleDebug :demo-target:assembleDebug
adb install -r demo-target/build/outputs/apk/debug/demo-target-debug.apk
adb install -r guide/build/outputs/apk/debug/guide-debug.apk
echo '已安装两个 APK。先开启数字扶手的无障碍服务，再打开数字扶手开始练习。'
