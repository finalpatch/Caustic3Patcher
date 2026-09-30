#!/bin/sh
# Builds on Termux or a Unix host with Android SDK 36 and Java 17+.
set -eu
cd "$(dirname "$0")/.."
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}
bt="$sdk/build-tools/36.0.0"
android="$sdk/platforms/android-36/android.jar"
aapt_tool=${AAPT2:-$bt/aapt2}
signer_tool=${APKSIGNER:-$bt/apksigner}
align_tool=${ZIPALIGN:-$bt/zipalign}
version_info=$(python scripts/version.py)
version_name=${version_info% *}
version_code=${version_info##* }
# CI must never fall back to a newly generated development identity.
if [ "${REQUIRE_RELEASE_KEY:-0}" = 1 ] || [ "${CI:-false}" = true ]; then
    if [ -z "${PATCHER_KEYSTORE:-}" ] || [ -z "${PATCHER_KEY_PASSWORD_FILE:-}" ]; then
        echo 'Release signing requires PATCHER_KEYSTORE and PATCHER_KEY_PASSWORD_FILE.' >&2
        exit 1
    fi
fi
if [ -n "${PATCHER_KEYSTORE:-}" ] || [ -n "${PATCHER_KEY_PASSWORD_FILE:-}" ]; then
    if [ ! -s "${PATCHER_KEYSTORE:-}" ] || [ ! -s "${PATCHER_KEY_PASSWORD_FILE:-}" ]; then
        echo 'Both release signing files must exist and be nonempty.' >&2
        exit 1
    fi
fi
# Discard only generated app intermediates, avoiding stale classes on rebuilds.
python -c 'from pathlib import Path; import shutil; p=Path("build/app"); shutil.rmtree(p) if p.exists() else None'
mkdir -p build/app/classes build/app/dex dist signing
test -s app/src/main/assets/patches/midi.dex
test -s app/src/main/assets/patches/aaudio.dex
python scripts/check-vendor.py
python -c 'from pathlib import Path; p=Path("app/src/main/AndroidManifest.xml"); Path("build/app/AndroidManifest.xml").write_text(p.read_text().replace("<manifest ", "<manifest package=\"org.caustic.patcher\" ", 1))'
"$aapt_tool" compile --dir app/src/main/res -o build/app/resources.zip
"$aapt_tool" link -o build/app/resources.apk -I "$android" --manifest build/app/AndroidManifest.xml \
    --java build/app/generated --min-sdk-version 30 --target-sdk-version 36 --version-code "$version_code" --version-name "$version_name" \
    -A app/src/main/assets build/app/resources.zip
python scripts/compile-java.py "$android"
"$bt/d8" --release --min-api 30 --lib "$android" --output build/app/dex \
    build/app/classes.jar vendor/dexlib2-2.5.2.jar vendor/guava-27.1-android.jar vendor/apksig-37.0.0.jar
python scripts/package-app.py
# Stable local development identity for the patcher itself, separate from user-generated keys.
# Production builds must set PATCHER_KEYSTORE and PATCHER_KEY_PASSWORD_FILE and retain that key.
if [ -z "${PATCHER_KEYSTORE:-}" ]; then
    if [ ! -f signing/patcher-dev.p12 ]; then
        if [ -f signing/dev-password ]; then
            echo 'Development keystore is missing; restore it instead of silently replacing its identity.' >&2
            exit 1
        fi
        python -c 'import secrets,pathlib; p=pathlib.Path("signing/dev-password"); p.write_text(secrets.token_hex(32)); p.chmod(0o600)'
        keytool -genkeypair -keystore signing/patcher-dev.p12 -storetype PKCS12 -alias patcher \
            -storepass:file signing/dev-password -keyalg RSA -keysize 3072 -validity 36500 \
            -dname 'CN=Unofficial Caustic Patcher local development'
    fi
    PATCHER_KEYSTORE=signing/patcher-dev.p12
    PATCHER_KEY_PASSWORD_FILE=signing/dev-password
fi
"$align_tool" -P 16 -f 4 build/app/unsigned.apk build/app/aligned.apk
"$signer_tool" sign --ks "$PATCHER_KEYSTORE" --ks-pass "file:$PATCHER_KEY_PASSWORD_FILE" \
    --v4-signing-enabled false --out dist/caustic3-patcher.apk build/app/aligned.apk
"$signer_tool" verify --verbose --print-certs dist/caustic3-patcher.apk
"$align_tool" -c -P 16 4 dist/caustic3-patcher.apk
printf 'Built dist/caustic3-patcher.apk (version %s, code %s)\n' "$version_name" "$version_code"
