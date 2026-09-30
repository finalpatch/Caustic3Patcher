#!/bin/sh
# Builds on Termux or a Unix host with Android SDK 36 and Java 17+.
set -eu
cd "$(dirname "$0")/.."
sdk=${ANDROID_SDK_ROOT:-$HOME/android-sdk}
bt="$sdk/build-tools/36.0.0"
android="$sdk/platforms/android-36/android.jar"
aapt_tool=${AAPT2:-aapt2}
mkdir -p build/app/classes build/app/dex dist signing
test -s app/src/main/assets/patches/midi.dex
test -s app/src/main/assets/patches/aaudio.dex
python scripts/check-vendor.py
python -c 'from pathlib import Path; p=Path("app/src/main/AndroidManifest.xml"); Path("build/app/AndroidManifest.xml").write_text(p.read_text().replace("<manifest ", "<manifest package=\"org.caustic.patcher\" ", 1))'
"$aapt_tool" compile --dir app/src/main/res -o build/app/resources.zip
"$aapt_tool" link -o build/app/resources.apk -I "$android" --manifest build/app/AndroidManifest.xml \
    --java build/app/generated --min-sdk-version 30 --target-sdk-version 36 --version-code 2 --version-name 0.1.1 \
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
apksigner sign --ks "$PATCHER_KEYSTORE" --ks-pass "file:$PATCHER_KEY_PASSWORD_FILE" \
    --v4-signing-enabled false --out dist/caustic3-patcher.apk build/app/unsigned.apk
apksigner verify --verbose dist/caustic3-patcher.apk
printf 'Built dist/caustic3-patcher.apk\n'
