# Bundled runtime dependencies

These Java libraries run inside the patcher; no command-line programs execute on the user's device.

* `dexlib2-2.5.2.jar`: org.smali:dexlib2:2.5.2 from Maven Central; BSD 3-Clause.
* `guava-27.1-android.jar`: com.google.guava:guava:27.1-android from Maven Central; Apache 2.0.
* `apksig-37.0.0.jar`: the installed Termux `apksigner` 37.0.0 JAR, including AOSP's apksig library; Apache 2.0. The patcher calls the library API, not its CLI. SHA-256 is pinned in SHA256SUMS.

Licenses are retained here and included in the app's notices. `scripts/check-vendor.py` verifies the JARs. Build scripts use SDK 36, independently of the apksig library version.

Sources: https://github.com/JesusFreke/smali, https://github.com/google/guava, https://android.googlesource.com/platform/tools/apksig/ and https://github.com/termux/termux-packages/tree/master/packages/apksigner.
