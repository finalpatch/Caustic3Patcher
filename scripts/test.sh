#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
original=${1:?Usage: sh scripts/test.sh /path/to/official.apk}
sdk=${ANDROID_SDK_ROOT:-$HOME/android-sdk}
ndk=${ANDROID_NDK_HOME:-$HOME/android-ndk-r29}
host=${NDK_HOST_TAG:-linux-aarch64}
cxx="$ndk/toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android30-clang++"
mkdir -p build/test-classes build/native-tests
python scripts/check-vendor.py
javac --release 8 -cp 'vendor/*' -d build/test-classes core/src/org/caustic/patcher/core/*.java tests/PatcherIntegrationTest.java
java -cp 'build/test-classes:vendor/*' PatcherIntegrationTest "$original" build/integration
javac --release 8 -cp "$sdk/platforms/android-36/android.jar" -d build/test-classes \
    patches/src/com/singlecellsoftware/caustic/midi/MidiStreamParser.java \
    patches/src/com/singlecellsoftware/caustic/audio/*.java tests/MidiStreamParserTest.java tests/AudioThreadFenceTest.java
java -cp build/test-classes MidiStreamParserTest
java -cp "build/test-classes:$sdk/platforms/android-36/android.jar" AudioThreadFenceTest
for test in audio_pcm_test audio_lifecycle_test audio_recovery_test; do
    "$cxx" -std=c++17 -O2 -Wall -Wextra -Werror -fno-exceptions -fno-rtti -nostdlib++ \
        "tests/$test.cpp" -o "build/native-tests/$test" -llog -ldl
    "build/native-tests/$test"
done
"$cxx" -std=c++17 -O2 -Wall -Wextra -Werror -fno-exceptions -fno-rtti -nostdlib++ \
    -fPIC -fvisibility=hidden -shared patches/src/native/caustic_audio.cpp \
    -o build/native-tests/libforward.so -llog -ldl
"$cxx" -std=c++17 -O2 -Wall -Wextra -Werror -fno-exceptions -fno-rtti -nostdlib++ \
    tests/audio_forwarding_test.cpp -o build/native-tests/audio_forwarding_test -llog -ldl
python - "$original" <<'PY'
from pathlib import Path
from zipfile import ZipFile
import sys
with ZipFile(sys.argv[1]) as apk:
    Path('build/native-tests/libcaustic.so').write_bytes(apk.read('lib/arm64-v8a/libcaustic.so'))
PY
build/native-tests/audio_forwarding_test "$PWD/build/native-tests/libforward.so" "$PWD/build/native-tests/libcaustic.so"
for variant in 1 2 3; do
    apksigner verify --verbose "build/integration/variant-$variant.apk"
    zipalign -c -P 16 4 "build/integration/variant-$variant.apk"
done
echo 'All host and native-process tests passed. Android UI/install acceptance is separate.'
