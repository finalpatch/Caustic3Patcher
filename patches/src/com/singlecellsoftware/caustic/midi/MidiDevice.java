package com.singlecellsoftware.caustic.midi;

/** Preserve the class and native method name exported by libcaustic.so. */
public final class MidiDevice {
    private MidiDevice() { }
    private static native void nativeOnMIDIMessage(byte[] bytes, int count);

    static void sendPacket(byte[] packet) {
        if (packet == null || packet.length != 4)
            throw new IllegalArgumentException("Native MIDI requires a four-byte packet");
        nativeOnMIDIMessage(packet, 4);
    }
}
