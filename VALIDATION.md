# Validation — 2026-09-30

## Passed

- Built `dist/caustic3-patcher.apk` with SDK 36/D8 and Java 21 using `scripts/build.sh`; manifest reports API 30 minimum and API 36 target. APK signature and ZIP alignment verify.
- **7,939 integration assertions** across all three selections: valid output signatures and expected certificate, unchanged manifest/resources/assets/ARM64 engine, correct patch selection, unsupported ABI removal, persistent keys, password-protected backup round-trip, rejection of wrong passwords/corrupt keys/incorrect APKs/tampered overlays/empty selection/conflicting overlays.
- **4,064 complete disassembled classes** match independently rebuilt expectations across MIDI-only, AAudio-only and both. AAudio-only preserves the original MIDI package and original MIDI call site.
- `apksigner verify` and `zipalign -c -P 16 4` pass for each output.
- **110 MIDI parser checks** pass.
- Java AudioTrack thread-exit fencing tests pass, including timeout, interruption and eventual exit.
- Native PCM conversion tests pass, including 100,000 random values and callback continuity.
- Native lifecycle and recovery tests pass, including presets, disconnects, route debounce, stopped/prepared intent, retry limits and failed-close exclusion.
- Actual native helper/engine loader and forwarding tests pass; these execute in a native process with a JNI shim, not as the patched Android app.

The tests generated separate test keys and patched APKs under ignored `build/`. No installed Caustic app was modified or uninstalled.

## Remaining device acceptance

No ADB device was connected. An attempted standalone ART run from this environment aborted before executing the test, without a diagnostic on stderr. Consequently there is no claim that the new patcher has passed Android runtime, UI or installer testing. The working tree includes an installable development APK for that next step.

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
