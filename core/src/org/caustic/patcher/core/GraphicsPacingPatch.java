package org.caustic.patcher.core;

import java.io.IOException;
import java.util.Arrays;

/** Bypass only the native frame spin; retain its timestamp store and frame work. */
public final class GraphicsPacingPatch {
    public static final int OFFSET = 0x2eabd0;
    public static final String PATCHED_SHA256 = "18f747fe7804450908e5ba52b993fc50b9941f3a6094a4a2e5e619059aba80e5";
    public static final String AUDIO_PATCHED_SHA256 = "91812b8407a53c64c7b16be033818c15982c752cb3670e97dc4f385525c0af77";

    public static byte[] apply(byte[] engine) throws Exception {
        String input = PatchEngine.sha256(engine);
        String expected;
        if (AudioLabelPatch.ORIGINAL_SHA256.equals(input)) expected = PATCHED_SHA256;
        else if (AudioLabelPatch.PATCHED_SHA256.equals(input)) expected = AUDIO_PATCHED_SHA256;
        else throw new IOException("Unsupported native engine for graphics pacing patch");
        byte[] before = {(byte) 0xcd, 0x01, 0x00, 0x54}; // b.le 0x2eec08
        byte[] after = {0x0e, 0x00, 0x00, 0x14}; // b 0x2eec08
        if (!Arrays.equals(Arrays.copyOfRange(engine, OFFSET, OFFSET + 4), before))
            throw new IOException("Unexpected graphics pacing instruction");
        byte[] result = engine.clone();
        System.arraycopy(after, 0, result, OFFSET, after.length);
        if (!expected.equals(PatchEngine.sha256(result)))
            throw new IOException("Graphics pacing patch verification failed");
        return result;
    }
    private GraphicsPacingPatch() {}
}
