# Architecture and extending the patcher

## Runtime

`MainActivity` presents the workflow and uses Android's document picker and installer. `PatchService` owns the background work and persists readiness metadata. `ApkProvider` grants read-only access to a single private output APK, without exposing the key, source APK or arbitrary paths.

`PatchEngine` rechecks the whole original APK before every patch operation. It also checks bundled patch asset digests, merges selected DEX overlays, copies untouched ZIP entries, drops original signatures and unsupported native ABIs, and adds the AAudio helper when requested. AAudio also makes one exact native UI label edit through `AudioLabelPatch`, with full-library input/output hash checks. AOSP apksig aligns stored native libraries to 16 KiB, signs with the user's key, and verifies the result before it is published atomically.

`SigningKeys` handles local identity persistence and PKCS#12 backup/import. Key generation and signing use platform cryptographic providers. The small DER encoder constructs a self-signed certificate; the resulting certificate is parsed and its signature verified before storage. No custom encryption or signature primitive is implemented.

## Modular DEX patches

`scripts/prepare-patches.py` rebuilds the authored fixes on the exact original using the source transformations inherited from `recaustic`. `OverlayBuilder` extracts only added classes and modified or added methods/fields into each overlay. Full original APKs and whole original DEX files are not shipped.

The MIDI overlay replaces the original MIDI package with the authored bridge, plus `CausticActivity.HandleReturnCode`. The AAudio overlay changes `CausticActivity.onCreate`, selected `AudioEngine` methods and `OutputAudioLoop` methods/field flags, and adds the authored audio bridge classes. The build-time audio transform includes MIDI historically, but the extraction deliberately excludes all MIDI changes from the AAudio overlay.

At runtime, dexlib2 merges members by descriptor and rejects selected overlays that touch the same member or conflict with a newly added class. Independent overlays compose without pre-signing every selection. Future patches sharing a method need an explicit composable transformation or conflict rule; they cannot silently override each other. Method deletion is currently unsupported and rejected by the generator. New selectable patches require extending the catalog/UI and engine selection logic; this is not a dynamic third-party patch plugin system.

## Native engine hash guard

The original `lib/arm64-v8a/libcaustic.so` input is pinned to:

```text
d74dc1a15178ff178d14a8d9b1fa1cf31a12cfe7af2db7d67814481eb9250b1d
```

The adapter uses known engine offsets, symbol addresses and lifecycle assumptions. The AAudio patch replaces `OpenSL ES\0` at file offset `0x44bed` with `AAudio\0\0\0\0`. Only nine label bytes change; the library length and all engine code offsets are preserved. Its exact output hash is `d815864d7bd041d29bcedf776ed7e5b0efd334d8522fb2d7a9734c5cd95ea60f`. The Java bridge uses `nativeHasAAudio()` to require that hash for the AAudio helper and the original hash for the forwarding control. Each mode accepts only one engine image. MIDI-only retains the original engine and label. The whole-input APK hash and loaded-engine hash have different purposes: the first identifies the source release; the second confirms the engine the adapter is actually about to use.

When adding further native patches, revisit this explicitly:

1. Establish each native patch's expected input bytes and changed ranges; reject overlaps or incompatible combinations.
2. Validate that the selected transformations preserve every offset, function signature and behavioral assumption used by AAudio.
3. Calculate and approve the final native-engine hashes for compatible selections, and make the audio guard recognize only those outputs through an explicit compatibility mechanism.
4. Add selection-combination tests and real device coverage before publishing.

Do not disable the guard, accept arbitrary engine hashes, or automatically bless a native library merely because the patcher produced it. The current label-only transformation is explicitly approved and pinned; broader native compatibility remains deferred until the actual patches are known.

## Provenance

The repair source snapshot was copied from the supplied local `recaustic` working tree on 2026-09-30. That repository's HEAD was `10565f0b87f852c45fc389579d2188679e3919a1`; its audio source and tests were untracked working-tree additions, so the commit alone does not identify the snapshot. The exact copied sources and transformations are tracked here, with a separate [source digest inventory](patches/SOURCE-SHA256SUMS). Native test include paths were adjusted to this repository's layout.

The original investigation recorded physical audio/MIDI testing on Samsung SM-F966B / Android 16. Those results are prior evidence for the fixes, not validation of this new patcher UI. Known outstanding coverage includes broader devices/routes, endurance, export regressions and runtime behavior on 16-KiB-page devices.
