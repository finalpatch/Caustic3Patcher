// Deterministic production-worker tests. No real AAudio or physical latency claim.
#define CAUSTIC_AAUDIO
#define CAUSTIC_RECOVERY
#include "../patches/src/native/caustic_audio.cpp"
#include <stdlib.h>
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL %d: %s\n", __LINE__, #x); exit(1); } } while (0)
#include "audio_fake_aaudio.h"

static uint64_t now = 10000000000ULL;
static void advance(uint64_t delta = 1000000000ULL) { now += delta; recovery_tick(now); }

int main() {
    JNINativeInterface table{};
    table.FindClass = find_class; table.ThrowNew = throw_new; table.DeleteLocalRef = delete_local;
    JNIEnv env{&table};
    engine_base = calloc(1, 0x430000);
    CHECK(engine_base);
    ready.store(true);
    worker_started = true; // Drive the same tick deterministically, without a background thread.
    render_frames = mock_render;
    set_process_size = [](int32_t x) { process_frames = x; };
    set_latency = [](int32_t x) { CHECK(!output.playing); latency_frames = x; };
    set_value<int32_t>(0x424624, 128); set_value<int32_t>(0x424634, 256);
    set_value<uintptr_t>(0x429fe0, 1); set_value<uintptr_t>(0x429948, 1);

    CHECK(preset_frames(89, 267, 2822, 1) == 267);
    CHECK(preset_frames(89, 267, 2822, 4) == 534);
    CHECK(preset_frames(144, 200, 1000, 2) == 432);
    CHECK(preset_frames(144, 200, 250, 4) == 250);
    CHECK(preset_frames(0, 1, 10, 1) < 0);
    CHECK(preset_frames(144, -1, 2048, 1) < 0);
    CHECK(preset_frames(144, 300, 200, 1) < 0);
    CHECK(engine_latency_frames(480, 48000) == 568);
    CHECK(engine_latency_frames(267, 44100) == 394);
    CHECK(engine_latency_frames(-1, 44100) < 0);

    minimum = 267; burst = 89; capacity = 2822;
    for (int preset = 1; preset <= 4; ++preset) {
        output.preset = preset;
        desired = Desired::playing;
        recovery_tick(now);
        CHECK(output.playing && requested_buffer == 267 + (preset - 1) * 89);
        CHECK(latency_frames == requested_buffer + 127 && process_frames == 256);
        int count = opens;
        recovery_tick(now);
        CHECK(opens == count); // Idempotent tick does not reopen healthy output.
        suspend_output(&env);
    }
    CHECK(errors == 0);

    // Reopen on a new route: recompute burst/grant and retain saved high preset.
    desired = Desired::playing;
    recovery_tick(now);
    int count = opens, closed = closes;
    ++route_generation; route_changed_at = now;
    advance(299000000);
    CHECK(opens == count && closes == closed);
    advance(1000000);
    CHECK(!output.stream && closes == closed + 1);
    burst = 192; minimum = 384; capacity = 4096;
    advance(150000000);
    CHECK(output.playing && output.preset == 4 && output.buffer == 960 && latency_frames == 1087);

    // Disconnect callback only publishes. Worker closes/reopens off callback thread.
    count = opens; closed = closes;
    registered_error(&fake_stream, nullptr, AAUDIO_ERROR_DISCONNECTED);
    CHECK(closes == closed);
    advance();
    CHECK(closes == closed + 1 && opens == count);
    advance();
    CHECK(output.playing && opens == count + 1 && output.error.load() == 0);

    // Stream-state fallback when an error callback is absent.
    stream_state = AAUDIO_STREAM_STATE_DISCONNECTED;
    advance();
    CHECK(!output.stream);
    stream_state = AAUDIO_STREAM_STATE_OPEN;
    advance();
    CHECK(output.playing);

    // A pending recovery cannot restart after Pause/Stop, even after route events.
    registered_error(&fake_stream, nullptr, AAUDIO_ERROR_DISCONNECTED);
    suspend_output(&env);
    count = opens;
    ++route_generation; route_changed_at = now;
    advance(); advance();
    CHECK(opens == count && !output.stream && desired == Desired::stopped);

    // Prepared streams recover without becoming audible.
    desired = Desired::prepared;
    handled_route = route_generation; // prepare_output consumes topology already current.
    recovery_tick(now);
    CHECK(output.stream && !output.playing);
    registered_error(&fake_stream, nullptr, AAUDIO_ERROR_DISCONNECTED);
    advance(); advance();
    CHECK(output.stream && !output.playing);
    suspend_output(&env);

    // Revalidate returned sample rate, channel count and format on every reopen.
    desired = Desired::playing; returned_rate = 48000;
    recovery_tick(now);
    CHECK(!output.stream && failures == 1);
    returned_rate = 44100; returned_channels = 1;
    advance(); CHECK(!output.stream && failures == 2);
    returned_channels = 2; returned_format = AAUDIO_FORMAT_PCM_FLOAT;
    advance(); CHECK(!output.stream && failures == 3);
    returned_format = AAUDIO_FORMAT_PCM_I16;
    advance(); CHECK(output.playing && failures == 0);
    suspend_output(&env);

    // Bounded exponential retry; route change revives an exhausted attempt budget.
    desired = Desired::playing; fail_open = 100;
    reset_retries();
    recovery_tick(now);
    count = opens;
    advance(100000000); CHECK(opens == count); // Backoff is respected.
    for (int i = 0; i < 10; ++i) advance(5000000000ULL);
    CHECK(failures == kMaxFailures && !output.stream);
    count = opens; advance(5000000000ULL); CHECK(opens == count);
    fail_open = 0;
    ++route_generation; route_changed_at = now;
    advance(); advance();
    CHECK(output.playing && failures == 0);

    // Direct preset calls quiesce first and publish latency only before restarting.
    change_preset(&env, 1);
    CHECK(output.playing && output.buffer == 384 && latency_frames == 511);
    int16_t pcm[288]{};
    CHECK(registered_data(&fake_stream, nullptr, pcm, 144) == AAUDIO_CALLBACK_RESULT_CONTINUE);
    CHECK(renders == 2);
    suspend_output(&env);
    fail_start = 1; desired = Desired::playing; reset_retries();
    recovery_tick(now); CHECK(!output.stream && failures == 1);
    fail_start = 0; advance(); CHECK(output.playing);

    // A failed close prevents all future opens and a Java switch to another renderer.
    fail_close = 1;
    suspend_output(&env);
    CHECK(output.poisoned && errors == 1);
    count = opens; desired = Desired::playing; advance(); CHECK(opens == count);
    suspend_output(&env); CHECK(errors == 2); // Repeated Stop must not hide unsafe close.
    free(engine_base);
    puts("PASS: four minimum-aware presets; engine-frame latency; route debounce/reconfigure; disconnect and state recovery; stopped/prepared intent; format/rate validation; bounded retry/revival; safe direct preset change; failed-close exclusion.");
}
