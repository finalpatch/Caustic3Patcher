# Unofficial Caustic 3 Patcher

An offline Android app for applying the community MIDI crash repair, AAudio playback replacement and graphics pacing mod to the supported Caustic 3 APK. These fixes are shared with the author's permission as an **unofficial patcher**, not an official Caustic release. [Permission reference](https://www.reddit.com/r/Caustic3/comments/1n32r1u/comment/pcy12ts/).

The patcher requires Android 11/API 30 or newer and an ARM64 device. It does not need root, a computer, Termux, internet access, or external tools at runtime. It has no internet or broad storage permission. Files are selected through Android's document picker.

## Supported input

Only `Caustic_3.2.2_64b.apk`, version 3.2.2/versionCode 23, is accepted:

```text
Size:   49120477 bytes
SHA256: 7cf80508530e041821ab04693c6fc7bbd1fcd4c4b598cef825d3fd212b568ebf
```

The pin comes from the supplied `recaustic` investigation; the local original was checked against it. No APK is downloaded or bundled. Other official releases, repackaged APKs, split APK sets and already patched APKs are rejected.

## Use

1. Choose the official APK. The patcher copies it to private storage and verifies its full hash.
2. Select any combination of MIDI, AAudio and Graphics pacing. At least one must be selected.
3. Export your signing-key backup and keep its password safely. The installer action requires a recorded export/import for the output's signing identity.
4. Apply the selected patches. Processing continues in a foreground service when the activity is backgrounded or rotated. An interrupted run can be restarted; incomplete output is never offered for installation.
5. Save the APK, or offer it to Android's installer. Android may require you to allow installations from this patcher first.

**Before the first installation, back up important Caustic files.** Copy accessible files to a PC or use Caustic's built-in FTP server, available from the main menu's Tools page. Check that the backup opens. A PC connection does not expose all private app data.

The original Caustic must be uninstalled before our differently signed copy can be installed. Uninstalling can erase data. The patcher explains the conflict and never uninstalls Caustic automatically. When an installed patched copy uses another key, restore its key and patch again instead of uninstalling.

Subsequent compatible builds retain `com.singlecellsoftware.caustic` and use your same local key, allowing in-place updates. Changing selected fixes is supported. Signing continuity does not by itself guarantee against application bugs or every form of data loss.

## Fixes

**MIDI:** the Android MIDI input bridge, source picker, reconnect behavior and parser from `recaustic`. USB and exposed virtual MIDI 1.0 are the primary scope. Bluetooth discovery, MIDI 2.0 and general SysEx are not added.

**AAudio:** the latest recovery variant from that investigation. Caustic's audio options show **AAudio** in place of **OpenSL ES** when this patch is selected. AudioTrack and AudioRecord remain available. The engine/client remain at 44.1 kHz. Includes minimum-aware latency presets, stream lifecycle guards and route recovery. Selecting only AAudio leaves the original MIDI behavior, including its crash, intact.

**Graphics pacing:** skips the native busy-wait frame limiter and lets the existing display presentation path pace rendering, without an artificial 60 FPS cap. Independent of MIDI and AAudio. Removes the busy-wait that can saturate a CPU core. See [VALIDATION.md](VALIDATION.md) for device results and remaining coverage.

All three patches preserve the Android manifest, resources and assets. MIDI-only preserves the original ARM64 native engine; AAudio changes only its nine-byte options label, and Graphics pacing replaces one four-byte branch instruction. Library length and engine offsets are preserved. The patcher verifies the original APK and exact native patch inputs/outputs; the audio bridge retains native initialization guards without whole-library runtime hashing. Unsupported native ABI directories are removed from the output.

## Signing and backup

The patcher creates a 3072-bit RSA key and self-signed certificate locally, stores them in its private app directory, and reuses them. A normal patcher upgrade preserves this directory. There is no shared private key in the APK or repository.

Export/import uses password-protected PKCS#12 files. Export at least once and preserve both file and password outside the patcher's storage. Uninstalling the patcher, clearing its storage or losing the device can lose the local key. Restoring the backup restores the signing identity. Existing unreadable keys cause an error rather than silent replacement.

The patcher itself uses a separate signing identity. The local build creates a persistent development key under ignored `signing/`. Back up that directory to keep development builds updatable. A distribution release should use a separately retained private release key through the environment variables documented below. Neither identity is the Caustic author's original signing key.

## Build

Prebuilt, hash-pinned patch assets and runtime Java dependencies are tracked, so an ordinary patcher build does not need the original Caustic APK or the reverse-engineering directory.

On Termux or a Unix build host with Java 17+, Python 3, Android SDK platform/build-tools 36.0.0, `aapt2`, `apksigner`, and `keytool`:

```sh
export ANDROID_SDK_ROOT="$HOME/android-sdk"
sh scripts/build.sh
```

Output: `dist/caustic3-patcher.apk`. The checked-in Gradle project is also provided for Android Studio; the validated build here is `scripts/build.sh`, not Gradle. Build tools may need downloading on a developer's machine; the installed patcher never downloads tools.

For your own patcher release key, set both `PATCHER_KEYSTORE` and `PATCHER_KEY_PASSWORD_FILE`. The keystore should contain a single private signing entry. Never commit these files.

## GitHub releases

Pushing a stable version tag such as `v0.1.1` runs `.github/workflows/release.yml` on Ubuntu 24.04 with Java 21 and Android SDK 36. It checks the bundled dependencies, runs portable regression tests, builds/signs the patcher, verifies its signature/alignment/package version, and publishes a GitHub Release with `Caustic3Patcher-v0.1.1.apk` and `SHA256SUMS`. The workflow builds the checked-in patch assets; it does not download or redistribute the original Caustic APK and cannot run the proprietary-input integration/device tests.

Configure these **repository Actions secrets** once:

| Secret | Value |
| --- | --- |
| `PATCHER_KEYSTORE_BASE64` | Base64-encoded private patcher release keystore |
| `PATCHER_KEYSTORE_PASSWORD` | The password entered when creating that keystore |

Signing secrets are restored only to private runner temporary files and removed after building. CI fails if either secret is missing; it never creates a fallback signing identity. Keep an independent secure backup of the keystore and password. Repository variables and Git history are not appropriate storage for these values.

The public release key is separate from the initial local development key. To migrate an installed development patcher with a different signature, first export its **Caustic signing key** through the app; replace the patcher and import that backup. Caustic itself need not be uninstalled. Future public patcher releases must reuse the same release key.

For a new release, commit the intended changes, normally update `VERSION` to the next version, and push a tag:

```sh
git push origin main
git tag -a v0.1.2 -m "Release 0.1.2"
git push origin v0.1.2
```

The tag determines CI's app version even if `VERSION` differs. Local builds use `VERSION` unless `RELEASE_TAG` is explicitly set. Android version codes use `major * 1000000 + minor * 1000 + patch`; `v0.1.1` is code 1001. Supported components are major 0–2099 and minor/patch 0–999; `0.0.0`, leading zeros and prerelease/build suffixes are rejected. This scheme keeps increasing stable versions updatable. Publish a new version rather than moving a published tag. Reruns can finish an incomplete draft release but will not overwrite an already published release.

Portable CI checks can also run locally:

```sh
sh scripts/ci-test.sh
```

To regenerate patch assets, supply the pinned original, Apktool 3.0.3, and Android NDK r29:

```sh
export APKTOOL_JAR=/path/to/apktool_3.0.3.jar
export ANDROID_NDK_HOME="$HOME/android-ndk-r29"
# On a standard Linux x86-64 NDK host: export NDK_HOST_TAG=linux-x86_64
python scripts/prepare-patches.py /path/to/Caustic_3.2.2_64b.apk
sh scripts/build.sh
```

This builds from the authored sources in `patches/`; it does not modify or require `~/code/recaustic`. Each preparation run retains its work directory for inspection. Preserve its path for the round-trip check below. SDK/NDK binaries must run on the build host.

## Validation

```sh
sh scripts/test.sh /path/to/Caustic_3.2.2_64b.apk
python scripts/verify-roundtrip.py /path/to/Caustic_3.2.2_64b.apk build/patches.RUN_DIRECTORY
```

The native-process tests run on an ARM64 Android host such as Termux; they are not desktop Linux executables. Java patcher integration tests also run independently on a desktop JVM. See [VALIDATION.md](VALIDATION.md) for actual results and remaining device checks.

See [ARCHITECTURE.md](ARCHITECTURE.md) for modular patch composition and native-engine compatibility, and [CHANGES.md](CHANGES.md) for the initial file-by-file inventory.
