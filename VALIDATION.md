# Validation — 2026-09-30

## Passed

- Built `dist/caustic3-patcher.apk` with SDK 36/D8 and Java 21 using `scripts/build.sh`; manifest reports API 30 minimum and API 36 target. APK signature and ZIP alignment verify.
- **7,945 integration assertions** across all three selections: valid output signatures and expected certificate, unchanged manifest/resources/assets and exact nine-byte AAudio native-label edit (MIDI-only engine unchanged), correct patch selection, unsupported ABI removal, persistent keys, password-protected backup round-trip, rejection of wrong passwords/corrupt keys/incorrect APKs/tampered overlays/empty selection/conflicting overlays.
- **4,064 complete disassembled classes** match independently rebuilt expectations across MIDI-only, AAudio-only and both. AAudio-only preserves the original MIDI package and original MIDI call site.
- `apksigner verify` and `zipalign -c -P 16 4` pass for each output.
- **110 MIDI parser checks** pass.
- Java AudioTrack thread-exit fencing tests pass, including timeout, interruption and eventual exit.
- Native PCM conversion tests pass, including 100,000 random values and callback continuity.
- Native lifecycle and recovery tests pass, including presets, disconnects, route debounce, stopped/prepared intent, retry limits and failed-close exclusion.
- Actual native helper/engine loader and forwarding tests pass; these execute in a native process with a JNI shim, not as the patched Android app.

The tests generated separate test keys and patched APKs under ignored `build/`. No installed Caustic app was modified or uninstalled.

## Remaining device acceptance

At the initial build, no ADB device was connected. An attempted standalone ART run from this environment aborted before executing the test, without a diagnostic on stderr. That standalone run did not validate Android runtime behavior. Subsequently the user installed the patcher-generated APK, reported that enabling MIDI no longer crashed, and fresh logs from PID 1982 showed AAudio running with callbacks increasing from 1,620 to 28,916 and zero xruns/deadline overruns. Those observations apply to version 0.1.0 before the label change. The new 0.1.1 options label still needs an on-device visual check; no installed app was replaced during the label edit.

Check on an ordinary ARM64 Android 11+ device:

- Launch, layout/insets, rotation and accessibility; key creation and backup export/import using Android's providers.
- Select a valid APK and an invalid APK, including cloud document providers and picker cancellation.
- Patch each selection; background/rotate the activity while processing; recover after force-stop.
- Save through the system picker and install through the system installer after enabling this source.
- See the backup warning and signature mismatch guidance for an existing original or differently signed copy; do not uninstall important data without a checked backup.
- With a backed-up local key, install one variant and update to another without uninstalling; verify Caustic data persists.
- Restore the same key after reinstalling the patcher and repeat the update. Verify a different key is detected.
- Exercise MIDI connection/reconnection and audio playback, presets, route switching, background/resume, recording and export as applicable.

The Gradle/Android Studio build path is provided but was not executed here. Device runtime compatibility of the bundled libraries and platform PKCS#12 implementation remains part of acceptance. Host test success does not establish physical audio latency or broad device support.

## Version 0.1.1 label change

- All 7,945 integration assertions and 4,064 class comparisons pass after rebuilding the overlays.
- Native label transformation changes exactly nine bytes at `0x44bed`, preserves binary length and all other bytes, and rejects already patched or corrupted engines.
- AAudio-only and combined outputs use the new native hash; MIDI-only retains the original library/label.
- Native backend detection tests distinguish AAudio from forwarding; all existing MIDI/audio tests pass.
- Recaustic recovery and forwarding build/package verification passes with strict mode-specific native hash checks.
- Patcher version advanced to 0.1.1 / versionCode 2, using the same local patcher signing key.
- Android installation and visual inspection of the renamed options entry are still pending. Repatch the original official APK through the updated patcher, preserving its local signing key, then install the output as an update.

## Tag-triggered public releases

The user subsequently confirmed that the renamed AAudio option works. Public release signing uses the new dedicated project key stored in repository Actions secrets, independently of the locally generated development key.

Release checks cover stable-tag parsing, monotonic Android version codes, rejection of invalid/out-of-range tags, signing-secret decoding without logging values, and refusal to generate a development key in CI. Portable MIDI/parser and thread-fence regression checks run in Actions. Full APK patching and native-process/device tests still require the original input and an Android host and are not claimed as CI coverage.

The workflow is statically checked with actionlint. Local build validation uses the existing development key (the new release password remains in GitHub secrets). The first public tag run verifies the actual secret-backed Linux release build before publishing its APK and checksum.
