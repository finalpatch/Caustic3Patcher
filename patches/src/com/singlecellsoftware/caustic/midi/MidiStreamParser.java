package com.singlecellsoftware.caustic.midi;

/** MIDI 1.0 stream framing for Caustic's existing USB-packet JNI interface. */
public final class MidiStreamParser {
    public interface Sink { void packet(byte[] packet); }
    private final Sink sink;
    private final byte[] packet = new byte[4];
    private int running, status, needed, used, first;
    private boolean sysex;

    public MidiStreamParser(Sink sink) { this.sink = sink; }

    public void reset() {
        running = status = needed = used = first = 0;
        sysex = false;
    }

    public void accept(byte[] bytes, int offset, int count) {
        if (bytes == null || offset < 0 || count < 0 || offset > bytes.length - count)
            throw new IllegalArgumentException("Invalid MIDI byte range");
        for (int i = offset; i < offset + count; i++) consume(bytes[i] & 255);
    }

    private void consume(int value) {
        // Realtime can occur even inside SysEx or a partially received message.
        if (value >= 0xf8) {
            if (value == 0xfa || value == 0xfc) emit(15, value, 0, 0);
            return;
        }
        if (value >= 0x80) {
            used = 0;
            if (value < 0xf0) {
                sysex = false;
                running = status = value;
                needed = length(value);
            } else {
                running = 0;
                status = value;
                sysex = value == 0xf0;
                needed = value == 0xf2 ? 2 : (value == 0xf1 || value == 0xf3 ? 1 : 0);
            }
            return;
        }
        if (sysex || needed == 0) return;
        if (used++ == 0) first = value;
        if (used != needed) return;
        int type = status >> 4;
        if (type == 8 || type == 9 || type == 11 || type == 12 || type == 14)
            emit(type, status, first, needed == 2 ? value : 0);
        used = 0;
        status = running;
        needed = running == 0 ? 0 : length(running);
    }

    private static int length(int status) {
        int type = status >> 4;
        return type == 12 || type == 13 ? 1 : 2;
    }

    private void emit(int cin, int status, int a, int b) {
        packet[0] = (byte) cin; // Cable zero; engine routes by MIDI channel.
        packet[1] = (byte) status;
        packet[2] = (byte) a;
        packet[3] = (byte) b;
        sink.packet(packet); // Sink consumes synchronously; do not retain buffer.
    }
}
