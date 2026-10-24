#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
node scripts/fetch-native.mjs
cd android
if [[ -d /workspace/toolchains/android-sdk ]]; then
 export ANDROID_HOME="${ANDROID_HOME:-/workspace/toolchains/android-sdk}"
 export ANDROID_USER_HOME="${ANDROID_USER_HOME:-/workspace/toolchains/android-user}"
 export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/toolchains/gradle-home}"
 if [[ ! -x "${JAVA_HOME:-/usr/lib/jvm/default-java}/bin/javac" && -x '/workspace/toolchains/jdk/jdk-21.0.9+10/bin/javac' ]]; then export JAVA_HOME='/workspace/toolchains/jdk/jdk-21.0.9+10'; fi
fi
args=()
# Java does not automatically use the HTTPS_PROXY supplied by the cloud runtime.
if [[ -n "${HTTPS_PROXY:-}" ]]; then
 read -r proxy_host proxy_port < <(python3 -c 'import os,urllib.parse; u=urllib.parse.urlsplit(os.environ["HTTPS_PROXY"]); print(u.hostname,u.port or 80)')
 args+=("-Dhttps.proxyHost=$proxy_host" "-Dhttps.proxyPort=$proxy_port" "-Dhttp.proxyHost=$proxy_host" "-Dhttp.proxyPort=$proxy_port")
 if [[ -f /etc/ssl/certs/java/cacerts ]]; then args+=("-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"); fi
fi
if [[ -x /workspace/toolchains/gradle/gradle-8.13/bin/gradle ]]; then gradle=/workspace/toolchains/gradle/gradle-8.13/bin/gradle; else gradle=./gradlew; fi
if [[ $# == 0 ]]; then set -- assembleDebug assembleAndroidTest lintDebug; fi
exec "$gradle" "${args[@]}" "$@"
