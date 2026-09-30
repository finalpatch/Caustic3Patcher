#!/bin/sh
# Tests that need no proprietary APK and can run on GitHub's Linux runner.
set -eu
cd "$(dirname "$0")/.."
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}
python scripts/check-vendor.py
python -m unittest discover -s tests -p 'test_release.py' -v
mkdir -p build/ci-test-classes
javac --release 8 -cp "$sdk/platforms/android-36/android.jar" -d build/ci-test-classes \
    patches/src/com/singlecellsoftware/caustic/midi/MidiStreamParser.java \
    patches/src/com/singlecellsoftware/caustic/audio/*.java tests/MidiStreamParserTest.java tests/AudioThreadFenceTest.java
java -cp build/ci-test-classes MidiStreamParserTest
java -cp "build/ci-test-classes:$sdk/platforms/android-36/android.jar" AudioThreadFenceTest
