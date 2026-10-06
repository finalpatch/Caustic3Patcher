# Architecture and extending the patcher

## Runtime

`MainActivity` presents the workflow and uses Android's document picker and installer. `PatchService` owns the background work and persists readiness metadata. `ApkProvider` grants read-only access to a single private output APK, without exposing the key, source APK or arbitrary paths.

`PatchEngine` rechecks the whole original APK before every patch operation. It also checks bundled patch asset digests, merges selected DEX overlays, copies untouched ZIP entries, drops original signatures and unsupported native ABIs, and adds the AAudio helper when requested. AAudio also makes one exact native UI label edit through `AudioLabelPatch`, with full-library input/output hash checks. Graphics pacing then optionally applies `GraphicsPacingPatch` to the original or AAudio-labelled engine, verifying its exact input and output hashes. AOSP apksig aligns stored native libraries to 16 KiB, signs with the user's key, and verifies the result before it is published atomically.

`SigningKeys` handles local identity persistence and PKCS#12 backup/import. Key generation and signing use platform cryptographic providers. The small DER encoder constructs a self-signed certificate; the resulting certificate is parsed and its signature verified before storage. No custom encryption or signature primitive is implemented.

## Modular DEX patches

`scripts/prepare-patches.py` rebuilds the authored fixes on the exact original using the source transformations inherited from `recaustic`. `OverlayBuilder` extracts only added classes and modified or added methods/fields into each overlay. Full original APKs and whole original DEX files are not shipped. Graphics-only patching copies the selected original APK’s DEX unchanged.

The MIDI overlay replaces the original MIDI package with the authored bridge, plus `CausticActivity.HandleReturnCode`. The AAudio overlay changes `CausticActivity.onCreate`, selected `AudioEngine` methods and `OutputAudioLoop` methods/field flags, and adds the authored audio bridge classes. The build-time audio transform includes MIDI historically, but the extraction deliberately excludes all MIDI changes from the AAudio overlay.

At runtime, dexlib2 merges members by descriptor and rejects selected overlays that touch the same member or conflict with a newly added class. Independent overlays compose without pre-signing every selection. Future patches sharing a method need an explicit composable transformation or conflict rule; they cannot silently override each other. Method deletion is currently unsupported and rejected by the generator. New selectable patches require extending the catalog/UI and engine selection logic; this is not a dynamic third-party patch plugin system.

## Native engine hash guard

The original `lib/arm64-v8a/libcaustic.so` input is pinned to:

```text
d74dc1a15178ff178d14a8d9b1fa1cf31a12cfe7af2db7d67814481eb9250b1d
```

The adapter uses known engine offsets, symbol addresses and lifecycle assumptions. AAudio replaces `OpenSL ES\0` at file offset `0x44bed` with `AAudio\0\0\0\0`, changing nine bytes. Graphics pacing replaces `cd 01 00 54` at file offset `0x2eabd0` (VA `0x2eebd0`) with `0e 00 00 14`. The unconditional branch lands at VA `0x2eec08`, retaining the timestamp store and subsequent frame work while bypassing only the busy-wait loop. No frame cap or scheduling wrapper is added.

The patch order is AAudio label, then graphics pacing. Both transformations retain length and offsets, validate expected bytes and pin complete input/output hashes. The supported engine outputs are:

| Native selection | SHA-256 |
| --- | --- |
| AAudio | `d815864d7bd041d29bcedf776ed7e5b0efd334d8522fb2d7a9734c5cd95ea60f` |
| Graphics | `18f747fe7804450908e5ba52b993fc50b9941f3a6094a4a2e5e619059aba80e5` |
| AAudio + Graphics | `91812b8407a53c64c7b16be033818c15982c752cb3670e97dc4f385525c0af77` |

The audio bridge no longer hashes the loaded engine. It still locates the loaded image and invokes native initialization, retaining symbol ownership/offset, instruction and renderer-state guards. Full integrity and compatible composition are the patcher's responsibility. Additional native patches require explicit byte-range and compatibility review, pinned outputs and selection-combination tests; runtime sanity checks alone do not prove compatibility.

## Provenance

The repair source snapshot was copied from the supplied local `recaustic` working tree on 2026-09-30. That repository's HEAD was `10565f0b87f852c45fc389579d2188679e3919a1`; its audio source and tests were untracked working-tree additions, so the commit alone does not identify the snapshot. The exact copied sources and transformations are tracked here, with a separate [source digest inventory](patches/SOURCE-SHA256SUMS). Native test include paths were adjusted to this repository's layout.

The original investigation recorded physical audio/MIDI testing on Samsung SM-F966B / Android 16. Those results are prior evidence for the fixes, not validation of this new patcher UI. Known outstanding coverage includes broader devices/routes, endurance, export regressions and runtime behavior on 16-KiB-page devices.

On 2026-10-06, `AudioBackend.java` was synced from `recaustic` to remove runtime hashing. Graphics pacing follows `recaustic/analysis/audio-graphics/README.md` and its exact branch edit; the graphics-only output was derived from the pinned original. The source digest inventory and bundled audio overlay were regenerated.
