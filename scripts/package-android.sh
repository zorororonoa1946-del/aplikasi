#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/android-build.sh assembleRelease lintRelease
sdk="${ANDROID_HOME:-/workspace/toolchains/android-sdk}"
unsigned=android/app/build/outputs/apk/release/app-release-unsigned.apk
apk_files="$("$sdk/build-tools/35.0.0/aapt" list "$unsigned")"
apk_badging="$("$sdk/build-tools/35.0.0/aapt" dump badging "$unsigned")"
if [[ "$apk_files" == *test_ca* || "$apk_files" == *test_network_security* || "$apk_badging" == *application-debuggable* ]]; then
 echo 'Refusing to distribute a debuggable APK or test-only TLS trust.' >&2; exit 1
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
 if [[ -x '/workspace/toolchains/jdk/jdk-21.0.9+10/bin/keytool' ]]; then export JAVA_HOME='/workspace/toolchains/jdk/jdk-21.0.9+10';
 else echo 'Set JAVA_HOME to a full JDK before signing.' >&2; exit 1; fi
fi
signing_dir="${NUSA_SIGNING_DIR:-$PWD/.local/signing}"
mkdir -p "$signing_dir" artifacts
chmod 700 "$signing_dir"
if [[ ! -f "$signing_dir/password" ]]; then
  if [[ -e "$signing_dir/release.p12" ]]; then echo 'Signing password missing; preserve the existing keystore.' >&2; exit 1; fi
  node --input-type=module -e 'import {writeFileSync} from "node:fs";import {randomBytes} from "node:crypto";writeFileSync(process.argv[1],randomBytes(32).toString("base64url"),{flag:"wx",mode:0o600})' "$signing_dir/password"
fi
export NUSA_KEYSTORE_PASSWORD
NUSA_KEYSTORE_PASSWORD="$(cat "$signing_dir/password")"
if [[ ! -f "$signing_dir/release.p12" ]]; then
 "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$signing_dir/release.p12" -storetype PKCS12 -storepass:env NUSA_KEYSTORE_PASSWORD -keypass:env NUSA_KEYSTORE_PASSWORD -alias nusa -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Nusa Personal Vault' > /dev/null 2>&1
 chmod 600 "$signing_dir/release.p12"
fi
"$sdk/build-tools/35.0.0/zipalign" -f -p 4 android/app/build/outputs/apk/release/app-release-unsigned.apk artifacts/nusa-unsigned-aligned.apk
"$sdk/build-tools/35.0.0/apksigner" sign --ks "$signing_dir/release.p12" --ks-key-alias nusa --ks-pass env:NUSA_KEYSTORE_PASSWORD --key-pass env:NUSA_KEYSTORE_PASSWORD --out artifacts/nusa-vault-1.0.0.apk artifacts/nusa-unsigned-aligned.apk
"$sdk/build-tools/35.0.0/apksigner" verify --verbose --print-certs artifacts/nusa-vault-1.0.0.apk > artifacts/apk-signature.txt
rm artifacts/nusa-unsigned-aligned.apk
sha256sum artifacts/nusa-vault-1.0.0.apk > artifacts/nusa-vault-1.0.0.apk.sha256
unset NUSA_KEYSTORE_PASSWORD
printf 'Signed APK: artifacts/nusa-vault-1.0.0.apk\nBack up your private signing directory securely: %s\n' "$signing_dir"
