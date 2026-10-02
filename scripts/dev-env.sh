#!/usr/bin/env bash
# Source this file: source scripts/dev-env.sh
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null)" || {
    echo '需要 JDK 17。请先安装并设置 JAVA_HOME。' >&2
    return 1 2>/dev/null || exit 1
  }
fi
export JAVA_HOME
export PATH="$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"
