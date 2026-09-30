package org.caustic.patcher.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Exact in-place native UI string edit; engine code and offsets are unchanged. */
public final class AudioLabelPatch {
    public static final int OFFSET = 0x44bed;
    public static final String ORIGINAL_SHA256 = "d74dc1a15178ff178d14a8d9b1fa1cf31a12cfe7af2db7d67814481eb9250b1d";
    public static final String PATCHED_SHA256 = "d815864d7bd041d29bcedf776ed7e5b0efd334d8522fb2d7a9734c5cd95ea60f";
    public static byte[] apply(byte[] engine) throws Exception {
        byte[] before = "OpenSL ES\0".getBytes(StandardCharsets.US_ASCII);
        byte[] after = "AAudio\0\0\0\0".getBytes(StandardCharsets.US_ASCII);
        if (!ORIGINAL_SHA256.equals(PatchEngine.sha256(engine))
                || !Arrays.equals(Arrays.copyOfRange(engine, OFFSET, OFFSET + before.length), before))
            throw new IOException("Unsupported native engine for AAudio label patch");
        byte[] result = engine.clone();
        System.arraycopy(after, 0, result, OFFSET, after.length);
        if (!PATCHED_SHA256.equals(PatchEngine.sha256(result)))
            throw new IOException("AAudio native label patch verification failed");
        return result;
    }
    private AudioLabelPatch() {}
}
