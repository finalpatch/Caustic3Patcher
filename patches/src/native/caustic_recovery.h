// Stage 4. All functions except the worker entry require audio_mutex.
namespace {
enum class Desired { stopped, prepared, playing };
Desired desired = Desired::stopped;
bool worker_started;
unsigned route_generation, handled_route, failures;
uint64_t route_changed_at, retry_at, last_metrics;
constexpr unsigned kMaxFailures = 6;

void reset_retries() { failures = 0; retry_at = 0; }

void recovery_attempt(uint64_t now) {
    if (desired == Desired::stopped || output.poisoned || failures >= kMaxFailures) return;
    if (!output.stream && now < retry_at) return;
    if (!open_output(nullptr)) {
        ++failures;
        retry_at = now + (250000000ULL << (failures > 4 ? 4 : failures - 1));
        __android_log_print(ANDROID_LOG_WARN, kTag, "AAudio open retry %u/%u", failures, kMaxFailures);
        return;
    }
    if (desired == Desired::playing && !output.playing) {
        start_output(nullptr);
        if (!output.playing) {
            ++failures;
            retry_at = now + (250000000ULL << (failures > 4 ? 4 : failures - 1));
            return;
        }
    }
    failures = 0;
}

// This deterministic tick is tested with fake streams, then used by the worker.
void recovery_tick(uint64_t now) {
    if (desired == Desired::stopped || output.poisoned) return;
    bool route = route_generation != handled_route && now - route_changed_at >= 300000000;
    bool broken = output.stream && (output.error.load(std::memory_order_acquire) != 0 ||
                  AAudioStream_getState(output.stream) == AAUDIO_STREAM_STATE_DISCONNECTED);
    if (route || broken) {
        bool invalid_engine = output.guard_failed.load();
        __android_log_print(ANDROID_LOG_INFO, kTag,
            "AAudio recovery: route=%d error=%d desired=%d", route, output.error.load(), static_cast<int>(desired));
        if (!close_output(nullptr)) return;
        if (invalid_engine) {
            output.poisoned = true;
            fail(nullptr, "Audio engine state mismatch. Restart Caustic.");
            return;
        }
        if (route) handled_route = route_generation;
        reset_retries();
        retry_at = now + 150000000; // Allow the route transaction to settle.
    }
    recovery_attempt(now);
    if (output.stream && output.playing && now - last_metrics >= 5000000000ULL) {
        last_metrics = now;
        __android_log_print(ANDROID_LOG_INFO, kTag,
            "AAudio metrics: device=%d preset=%d buffer=%d xruns=%d callbacks=%llu max_us=%llu late=%llu",
            AAudioStream_getDeviceId(output.stream), output.preset, output.buffer,
            AAudioStream_getXRunCount(output.stream),
            static_cast<unsigned long long>(output.callbacks.load()),
            static_cast<unsigned long long>(output.max_ns.load()) / 1000,
            static_cast<unsigned long long>(output.late.load()));
    }
}

void *recovery_worker(void *) {
    pthread_setname_np(pthread_self(), "CausticAudioCtl");
    for (;;) {
        {
            AudioLock lock;
            recovery_tick(audio_nanos());
        }
        timespec delay{0, 50000000};
        nanosleep(&delay, nullptr);
    }
    return nullptr;
}

bool ensure_worker(JNIEnv *env) {
    if (worker_started) return true;
    pthread_attr_t attrs;
    if (pthread_attr_init(&attrs)) { fail(env, "Cannot initialize audio worker attributes"); return false; }
    int result = pthread_attr_setdetachstate(&attrs, PTHREAD_CREATE_DETACHED);
    pthread_t thread;
    if (!result) result = pthread_create(&thread, &attrs, recovery_worker, nullptr);
    pthread_attr_destroy(&attrs);
    if (result) { fail(env, "Cannot start audio recovery worker"); return false; }
    worker_started = true;
    return true;
}

void prepare_output(JNIEnv *env) {
    if (!ensure_worker(env)) return;
    if (output.poisoned) { audio_failure(env, "poisoned; restart app", AAUDIO_ERROR_INVALID_STATE); return; }
    if (desired == Desired::stopped) {
        desired = Desired::prepared;
        handled_route = route_generation; // Opening uses the current default route.
        reset_retries();
    }
    recovery_attempt(audio_nanos());
}

void resume_output(JNIEnv *env) {
    if (!ensure_worker(env)) return;
    if (output.poisoned) { audio_failure(env, "poisoned; restart app", AAUDIO_ERROR_INVALID_STATE); return; }
    if (desired == Desired::stopped) handled_route = route_generation;
    desired = Desired::playing;
    reset_retries();
    recovery_tick(audio_nanos());
}

void suspend_output(JNIEnv *env) {
    // Clearing intent before close prevents the worker from resurrecting output.
    desired = Desired::stopped;
    reset_retries();
    close_output(env);
}

void change_preset(JNIEnv *env, int preset) {
    if (output.preset == preset) return;
    output.preset = preset;
    // Java normally stops first. Also handle direct calls safely without mutating
    // engine latency while a callback is rendering/exporting.
    if (!close_output(env)) return;
    reset_retries();
    recovery_attempt(audio_nanos());
}
} // namespace
