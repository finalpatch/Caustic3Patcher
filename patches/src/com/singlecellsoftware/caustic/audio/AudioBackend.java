package com.singlecellsoftware.caustic.audio;

import android.content.Context;
/** Audio bridge; build/patch integration validates the complete engine image. */
public final class AudioBackend {
    private static boolean initialized;

    private AudioBackend() {}

    public static synchronized void initialize(Context context) {
        if (initialized) return;
        System.loadLibrary("caustic_audio");
        try {
            // extractNativeLibs=false: this may be an APK!/entry path.
            // Locate the loaded image instead of guessing from nativeLibraryDir.
            String path = nativeLoadedLibraryPath();
            // Optional native patches may change unrelated engine bytes. Runtime
            // initialization retains native image ownership and ABI/state guards;
            // supported patch composition is the build/patcher's responsibility.
            Class<?> original = Class.forName("com.singlecellsoftware.OpenSLIO", false,
                    context.getClassLoader());
            nativeInitialize(path, original);
            if (nativeHasRecovery()) AudioRouteMonitor.start(context.getApplicationContext());
            initialized = true;
        } catch (Exception error) {
            throw new IllegalStateException("Caustic audio bridge initialization failed", error);
        }
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
