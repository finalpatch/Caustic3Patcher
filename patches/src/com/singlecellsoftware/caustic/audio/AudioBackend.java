package com.singlecellsoftware.caustic.audio;

import android.content.Context;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Pinned engine bridge for forwarding and AAudio experimental builds. */
public final class AudioBackend {
    private static final String ENGINE_SHA256 =
            "d74dc1a15178ff178d14a8d9b1fa1cf31a12cfe7af2db7d67814481eb9250b1d";
    private static boolean initialized;

    private AudioBackend() {}

    public static synchronized void initialize(Context context) {
        if (initialized) return;
        System.loadLibrary("caustic_audio");
        try {
            // extractNativeLibs=false: this may be an APK!/entry path.
            // Locate the loaded image instead of guessing from nativeLibraryDir.
            String path = nativeLoadedLibraryPath();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int split = path.indexOf("!/");
            if (split >= 0) {
                try (ZipFile apk = new ZipFile(path.substring(0, split))) {
                    ZipEntry entry = apk.getEntry(path.substring(split + 2));
                    if (entry == null) throw new IllegalStateException("Loaded engine entry missing");
                    try (InputStream input = apk.getInputStream(entry)) {
                        hash(input, digest);
                    }
                }
            } else {
                try (InputStream input = new FileInputStream(path)) {
                    hash(input, digest);
                }
            }
            StringBuilder actual = new StringBuilder(64);
            for (byte value : digest.digest()) {
                actual.append(Character.forDigit((value >>> 4) & 15, 16));
                actual.append(Character.forDigit(value & 15, 16));
            }
            if (!ENGINE_SHA256.contentEquals(actual)) {
                throw new IllegalStateException("Unsupported Caustic audio engine hash");
            }
            Class<?> original = Class.forName("com.singlecellsoftware.OpenSLIO", false,
                    context.getClassLoader());
            nativeInitialize(path, original);
            if (nativeHasRecovery()) AudioRouteMonitor.start(context.getApplicationContext());
            initialized = true;
        } catch (Exception error) {
            throw new IllegalStateException("Caustic audio bridge initialization failed", error);
        }
    }

    private static void hash(InputStream input, MessageDigest digest) throws Exception {
        byte[] buffer = new byte[32768];
        int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }

    /** Never permit another renderer until the old AudioTrack worker has exited. */
    public static void awaitOutputThread(Thread thread) {
        try {
            thread.join(2000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping AudioTrack", error);
        }
        if (thread.isAlive()) {
            throw new IllegalStateException("AudioTrack did not stop; refusing overlapping renderers");
        }
    }

    private static native boolean nativeHasRecovery();
    static native void nativeRouteChanged();
    private static native String nativeLoadedLibraryPath();
    private static native void nativeInitialize(String path, Class<?> originalClass);
    public static native void StartLoop();
    public static native void PauseLoop(int paused);
    public static native void StopLoop();
    public static native void SetWantResample(boolean enabled, int deviceFrames);
    public static native void SetNumBuffers(int count);
}
