#pragma once
#include <stdint.h>
#include <string.h>

// Original ARM64 clamp, logical shift and halfword-store semantics.
inline uint16_t caustic_pcm16(int32_t sample) {
    if (sample < -0xffffff) sample = -0xffffff;
    if (sample > 0xffffff) sample = 0xffffff;
    return static_cast<uint16_t>(static_cast<uint32_t>(sample) >> 9);
}

struct PcmCarry {
    static constexpr int quantum = 128;
    int32_t intermediate[quantum * 2]{};
    uint16_t pcm[quantum * 2]{};
    int offset = quantum;

    void reset() { offset = quantum; }

    // render returns false on a guard failure. Caller pre-clears destination.
    template<class Render> bool fill(void *output, int frames, Render render) {
        auto *out = static_cast<uint8_t *>(output);
        while (frames > 0) {
            if (offset == quantum) {
                memset(intermediate, 0, sizeof(intermediate));
                if (!render(intermediate, quantum)) return false;
                for (int i = 0; i < quantum * 2; ++i)
                    pcm[i] = caustic_pcm16(intermediate[i]);
                offset = 0;
            }
            int count = quantum - offset;
            if (count > frames) count = frames;
            memcpy(out, pcm + offset * 2, static_cast<size_t>(count) * 4);
            out += count * 4;
            frames -= count;
            offset += count;
        }
        return true;
    }
};
