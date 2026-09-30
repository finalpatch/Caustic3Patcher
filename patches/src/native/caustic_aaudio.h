// Included only by the AAudio proof variant, after the pinned loader definitions.
#include <aaudio/AAudio.h>
#include <time.h>
#include "audio_pcm.h"
#ifdef CAUSTIC_RECOVERY
#include "audio_policy.h"
#endif

namespace {
pthread_mutex_t audio_mutex = PTHREAD_MUTEX_INITIALIZER;
struct AudioLock {
    AudioLock() { pthread_mutex_lock(&audio_mutex); }
    ~AudioLock() { pthread_mutex_unlock(&audio_mutex); }
};
// Static lifetime also protects callback context if a platform close fails.
struct Output {
    AAudioStream *stream = nullptr; // control thread only
    PcmCarry carry;
    std::atomic<bool> stopping{true};
    std::atomic<int> error{0};
    std::atomic<unsigned> in_flight{0};
    std::atomic<uint64_t> callbacks{0}, max_ns{0}, late{0};
    bool poisoned = false;
    bool playing = false;
    int preset = 2;
#ifdef CAUSTIC_RECOVERY
    int rate = 44100, burst = 0, buffer = 0, capacity = 0;
    std::atomic<bool> guard_failed{false};
#endif
} output;
static_assert(std::atomic<uint64_t>::is_always_lock_free, "Callback counters must be lock-free");
using Render = void (*)(int32_t *, int32_t);
using ProcessSize = void (*)(int32_t);
Render render_frames;
ProcessSize set_process_size;
#ifdef CAUSTIC_RECOVERY
ProcessSize set_latency;
#endif

template<class T> T engine_value(uintptr_t offset) {
    T value;
    memcpy(&value, static_cast<const char *>(engine_base) + offset, sizeof(value));
    return value;
}

bool resolve_renderer(void *base) {
    uint32_t render, size;
    memcpy(&render, static_cast<char *>(base) + 0x2ef7e0, 4);
    memcpy(&size, static_cast<char *>(base) + 0x2ef638, 4);
    if (render != 0xfc190fe8 || size != 0xb00009c8) return false;
    render_frames = reinterpret_cast<Render>(static_cast<char *>(base) + 0x2ef7e0);
    set_process_size = reinterpret_cast<ProcessSize>(static_cast<char *>(base) + 0x2ef638);
#ifdef CAUSTIC_RECOVERY
    uint32_t latency;
    memcpy(&latency, static_cast<char *>(base) + 0x2ef670, 4);
    if (latency != 0x5286f8c8) return false;
    set_latency = reinterpret_cast<ProcessSize>(static_cast<char *>(base) + 0x2ef670);
#endif
    return true;
}

bool renderer_ready() {
    int leftover = engine_value<int32_t>(0x429fdc);
    return engine_value<int32_t>(0x424624) == PcmCarry::quantum &&
           engine_value<int32_t>(0x424634) == 256 &&
           engine_value<uintptr_t>(0x429fe0) != 0 &&
           engine_value<uintptr_t>(0x429948) != 0 &&
           leftover >= 0 && leftover < PcmCarry::quantum;
}

uint64_t audio_nanos() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<uint64_t>(now.tv_sec) * 1000000000 + now.tv_nsec;
}

aaudio_data_callback_result_t audio_callback(AAudioStream *, void *, void *buffer, int32_t frames) {
    output.in_flight.fetch_add(1, std::memory_order_seq_cst);
    bool okay = !output.stopping.load(std::memory_order_seq_cst) && output.error.load() == 0;
    uint64_t start = audio_nanos();
    if (frames > 0) {
        memset(buffer, 0, static_cast<size_t>(frames) * 4);
        if (okay) {
            okay = output.carry.fill(buffer, frames, [](int32_t *data, int count) {
                if (output.stopping.load(std::memory_order_seq_cst)) return false;
                if (!renderer_ready()) {
#ifdef CAUSTIC_RECOVERY
                    output.guard_failed.store(true);
#endif
                    output.error.store(AAUDIO_ERROR_INVALID_STATE);
                    return false;
                }
                render_frames(data, count); // Includes original DSP lock and export work.
                return true;
            });
        }
    }
    uint64_t elapsed = audio_nanos() - start;
    output.callbacks.fetch_add(1, std::memory_order_relaxed);
    if (elapsed > output.max_ns.load(std::memory_order_relaxed))
        output.max_ns.store(elapsed, std::memory_order_relaxed);
    if (frames > 0 && elapsed * 44100 > static_cast<uint64_t>(frames) * 1000000000)
        output.late.fetch_add(1, std::memory_order_relaxed);
    output.in_flight.fetch_sub(1, std::memory_order_seq_cst);
    return okay ? AAUDIO_CALLBACK_RESULT_CONTINUE : AAUDIO_CALLBACK_RESULT_STOP;
}

void audio_error(AAudioStream *, void *, aaudio_result_t error) {
    // No stop/close/reopen from an AAudio callback. Recovery runs off-thread.
    output.error.store(error, std::memory_order_release);
}

bool audio_failure(JNIEnv *env, const char *operation, int result) {
    char message[256];
    snprintf(message, sizeof(message), "AAudio %s failed: %d (%s)", operation,
             result, AAudio_convertResultToText(result));
    fail(env, message);
    return false;
}

bool close_output(JNIEnv *env) {
    output.stopping.store(true, std::memory_order_seq_cst);
#ifdef CAUSTIC_RECOVERY
    if (output.poisoned) return audio_failure(env, "poisoned; restart app", AAUDIO_ERROR_INVALID_STATE);
#endif
    if (!output.stream) return !output.poisoned;
    int xruns = AAudioStream_getXRunCount(output.stream);
    AAudioStream_requestStop(output.stream);
    int result = AAudioStream_close(output.stream);
    // A successful close joins callback threads. On any uncertainty fail closed:
    // do not recycle carry state or allow Java to start another renderer.
    if (result != AAUDIO_OK || output.in_flight.load(std::memory_order_seq_cst)) {
        output.poisoned = true;
        return audio_failure(env, "close/quiesce", result == AAUDIO_OK ? AAUDIO_ERROR_TIMEOUT : result);
    }
    output.stream = nullptr;
    output.playing = false;
    __android_log_print(ANDROID_LOG_INFO, kTag,
        "AAudio closed: xruns=%d callbacks=%llu max_us=%llu late=%llu error=%d",
        xruns, static_cast<unsigned long long>(output.callbacks.load()),
        static_cast<unsigned long long>(output.max_ns.load()) / 1000,
        static_cast<unsigned long long>(output.late.load()), output.error.load());
    output.carry.reset();
    return true;
}

bool open_output(JNIEnv *env) {
    if (output.poisoned) return audio_failure(env, "poisoned; restart app", AAUDIO_ERROR_INVALID_STATE);
    if (output.stream) {
        int error = output.error.load();
        return error ? audio_failure(env, "stream; restart app", error) : true;
    }
    if (!renderer_ready()) {
#ifdef CAUSTIC_RECOVERY
        output.poisoned = true;
#endif
        fail(env, "AAudio renderer state guard failed before opening");
        return false;
    }
    set_process_size(256); // Preserve original export/process contract.
    output.carry.reset();
    output.error.store(0);
#ifdef CAUSTIC_RECOVERY
    output.guard_failed.store(false);
#endif
    output.callbacks.store(0); output.max_ns.store(0); output.late.store(0);
    AAudioStreamBuilder *builder = nullptr;
    int result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) return audio_failure(env, "builder", result);
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, 44100);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setDataCallback(builder, audio_callback, nullptr);
    AAudioStreamBuilder_setErrorCallback(builder, audio_error, nullptr);
    result = AAudioStreamBuilder_openStream(builder, &output.stream);
    if (result != AAUDIO_OK) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "AAudio exclusive open=%d; retry shared", result);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
        result = AAudioStreamBuilder_openStream(builder, &output.stream);
    }
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) return audio_failure(env, "open 44100/stereo/PCM16", result);
    if (AAudioStream_getSampleRate(output.stream) != 44100 ||
        AAudioStream_getChannelCount(output.stream) != 2 ||
        AAudioStream_getFormat(output.stream) != AAUDIO_FORMAT_PCM_I16) {
        close_output(env);
        return audio_failure(env, "returned format/rate", AAUDIO_ERROR_INVALID_FORMAT);
    }
    int burst = AAudioStream_getFramesPerBurst(output.stream);
    if (burst <= 0 || burst > 32768) {
        close_output(env);
        return audio_failure(env, "burst", AAUDIO_ERROR_OUT_OF_RANGE);
    }
#ifdef CAUSTIC_RECOVERY
    output.rate = AAudioStream_getSampleRate(output.stream);
    output.burst = burst;
    output.capacity = AAudioStream_getBufferCapacityInFrames(output.stream);
    int minimum = AAudioStream_setBufferSizeInFrames(output.stream, burst);
    int requested = preset_frames(burst, minimum, output.capacity, output.preset);
    if (requested < 0) {
        close_output(env);
        return audio_failure(env, "buffer policy", AAUDIO_ERROR_OUT_OF_RANGE);
    }
    int granted = AAudioStream_setBufferSizeInFrames(output.stream, requested);
#else
    // Keep the accepted stage-3 proof configuration available as a control.
    int granted = AAudioStream_setBufferSizeInFrames(output.stream, burst * 2);
#endif
    if (granted <= 0) {
        close_output(env);
        return audio_failure(env, "buffer", granted);
    }
#ifdef CAUSTIC_RECOVERY
    if (granted > output.capacity) {
        close_output(env);
        return audio_failure(env, "buffer grant exceeds capacity", AAUDIO_ERROR_OUT_OF_RANGE);
    }
    output.buffer = granted;
    int latency = engine_latency_frames(granted, output.rate);
    set_latency(latency); // Only while callbacks are stopped, in engine-rate frames.
    __android_log_print(ANDROID_LOG_INFO, kTag,
        "AAudio preset=%d minimum=%d requested=%d granted=%d burst=%d capacity=%d engine_latency=%d (+native process allowance)",
        output.preset, minimum, requested, granted, burst, output.capacity, latency);
#endif
    using MmapUsed = bool (*)(AAudioStream *);
    auto mmap_used = reinterpret_cast<MmapUsed>(dlsym(RTLD_DEFAULT, "AAudioStream_isMMapUsed"));
    __android_log_print(ANDROID_LOG_INFO, kTag,
        "AAudio opened: rate=%d channels=%d format=%d burst=%d buffer=%d capacity=%d sharing=%d performance=%d device=%d mmap=%d preset=%d",
        AAudioStream_getSampleRate(output.stream), AAudioStream_getChannelCount(output.stream),
        AAudioStream_getFormat(output.stream), burst, granted,
        AAudioStream_getBufferCapacityInFrames(output.stream), AAudioStream_getSharingMode(output.stream),
        AAudioStream_getPerformanceMode(output.stream), AAudioStream_getDeviceId(output.stream),
        mmap_used ? static_cast<int>(mmap_used(output.stream)) : -1, output.preset);
    return true;
}

void start_output(JNIEnv *env) {
    if (!open_output(env) || output.playing) return;
    output.stopping.store(false, std::memory_order_seq_cst);
    int result = AAudioStream_requestStart(output.stream);
    if (result != AAUDIO_OK) {
        close_output(env);
        audio_failure(env, "start", result);
        return;
    }
    output.playing = true;
    __android_log_print(ANDROID_LOG_INFO, kTag, "AAudio started");
}
} // namespace
