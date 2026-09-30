#include "../patches/src/native/audio_pcm.h"
#include <stdio.h>
#include <stdlib.h>
#include <limits.h>
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL %d: %s\n", __LINE__, #x); exit(1); } } while (0)

uint16_t reference(int32_t sample) {
    int64_t wide = sample;
    if (wide < -16777215) wide = -16777215;
    if (wide > 16777215) wide = 16777215;
    // Floor division matches the low halfword of the original logical shift.
    int64_t scaled = wide < 0 ? -((-wide + 511) / 512) : wide / 512;
    return static_cast<uint16_t>(scaled & 65535);
}
int main() {
    int32_t edges[] = {INT_MIN,-16777216,-16777215,-513,-512,-511,-1,0,1,511,512,513,16777215,16777216,INT_MAX};
    for (int32_t x : edges) CHECK(caustic_pcm16(x) == reference(x));
    uint32_t random = 71;
    for (int i = 0; i < 100000; ++i) {
        random = random * 1664525 + 1013904223;
        int32_t value;
        memcpy(&value, &random, 4);
        CHECK(caustic_pcm16(value) == reference(value));
    }
    PcmCarry carry;
    int generated = 0, consumed = 0, calls = 0;
    auto render = [&](int32_t *buffer, int frames) {
        CHECK(frames == 128);
        for (int i = 0; i < frames * 2; ++i) CHECK(buffer[i] == 0);
        for (int i = 0; i < frames; ++i) {
            int value = (generated++ % 50000) - 25000;
            buffer[i * 2] = value * 512;
            buffer[i * 2 + 1] = -value * 512;
        }
        ++calls;
        return true;
    };
    const int lengths[] = {0,1,127,128,129,144,192,256,511,1024,4097};
    for (int repeat = 0; repeat < 50; ++repeat) for (int frames : lengths) {
        uint16_t data[8200];
        for (auto &x : data) x = 0xa55a;
        CHECK(carry.fill(data + 2, frames, render));
        CHECK(data[0] == 0xa55a && data[1] == 0xa55a);
        CHECK(data[frames * 2 + 2] == 0xa55a && data[frames * 2 + 3] == 0xa55a);
        for (int i = 0; i < frames; ++i) {
            int value = (consumed++ % 50000) - 25000;
            CHECK(data[i*2+2] == static_cast<uint16_t>(value));
            CHECK(data[i*2+3] == static_cast<uint16_t>(-value));
        }
        CHECK(generated - consumed >= 0 && generated - consumed < 128);
    }
    CHECK(calls == (consumed + 127) / 128);
    carry.reset();
    uint16_t silence[512]{};
    CHECK(carry.fill(silence, 256, [](int32_t *, int n) { CHECK(n == 128); return true; }));
    for (auto x : silence) CHECK(x == 0); // Export-style no-output renderer.
    carry.reset();
    CHECK(!carry.fill(silence, 1, [](int32_t *, int) { return false; }));
    CHECK(carry.offset == 128);
    puts("PASS: PCM clamp/shift edges and 100000 random values; stereo continuity across variable callbacks; carry bounds; preclear/export silence; guard failure.");
}
