#pragma once
#include <stdint.h>

// Presets start at the device's minimum grant, then add one burst each.
// Capacity can collapse adjacent presets; callers log the final grant.
inline int preset_frames(int burst, int minimum, int capacity, int preset) {
    if (burst < 1 || burst > 32768 || minimum < 1 || capacity < minimum ||
        capacity > 1048576 || preset < 1 || preset > 4) return -1;
    int64_t floor = ((static_cast<int64_t>(minimum) + burst - 1) / burst) * burst;
    int64_t target = floor + static_cast<int64_t>(preset - 1) * burst;
    return static_cast<int>(target < capacity ? target : capacity);
}

inline int engine_latency_frames(int buffer_frames, int client_rate) {
    if (buffer_frames < 1 || buffer_frames > 1048576 || client_rate < 8000) return -1;
    // 44100-Hz engine frames, rounded up, plus maximum adapter carry.
    return static_cast<int>((static_cast<int64_t>(buffer_frames) * 44100 + client_rate - 1) /
                            client_rate) + 127;
}
