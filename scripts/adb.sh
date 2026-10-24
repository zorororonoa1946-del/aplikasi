#!/usr/bin/env bash
set -euo pipefail
sdk="${ANDROID_HOME:-/workspace/toolchains/android-sdk}"
adb="$sdk/platform-tools/adb"
# On this cloud host adb's built-in state directory is mounted read-only.
# Redirect only that directory to writable workspace storage; do not change HOME.
if [[ -x /workspace/toolchains/proot/usr/bin/proot && -d /workspace/toolchains/android-state/.android ]]; then
  export LD_LIBRARY_PATH="/workspace/toolchains/proot/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  exec /workspace/toolchains/proot/usr/bin/proot -b /workspace/toolchains/android-state/.android:/home/agent/.android "$adb" "$@"
fi
exec "$adb" "$@"
