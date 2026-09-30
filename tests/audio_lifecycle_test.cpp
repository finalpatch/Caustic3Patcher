// Fake AAudio device: exercises production lifecycle/callback logic, not a driver.
#define CAUSTIC_AAUDIO
#include "../patches/src/native/caustic_audio.cpp"
#include <stdlib.h>
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL %d: %s\n", __LINE__, #x); exit(1); } } while (0)

#include "audio_fake_aaudio.h"
int main() {
    CHECK(JNI_METHOD(nativeHasAAudio)(nullptr, nullptr) == JNI_TRUE);
    JNINativeInterface table{};
    table.FindClass = find_class; table.ThrowNew = throw_new; table.DeleteLocalRef = delete_local;
    JNIEnv env{&table};
    engine_base = calloc(1, 0x430000);
    CHECK(engine_base);
    ready.store(true);
    render_frames = mock_render;
    set_process_size = [](int32_t x) { process_frames = x; };
    CHECK(!open_output(&env) && errors == 1 && opens == 0);
    set_value<int32_t>(0x424624, 128); set_value<int32_t>(0x424634, 256);
    set_value<uintptr_t>(0x429fe0, 1); set_value<uintptr_t>(0x429948, 1);
    fail_open = 1;
    JNI_METHOD(StartLoop)(&env, nullptr);
    CHECK(opens == 2 && requested_sharing == AAUDIO_SHARING_MODE_SHARED && starts == 0 && process_frames == 256);
    JNI_METHOD(StartLoop)(&env, nullptr);
    CHECK(opens == 2);
    JNI_METHOD(PauseLoop)(&env, nullptr, 0);
    JNI_METHOD(PauseLoop)(&env, nullptr, 0);
    CHECK(starts == 1);
    int16_t data[288]{};
    CHECK(registered_data(&fake_stream, nullptr, data, 144) == AAUDIO_CALLBACK_RESULT_CONTINUE);
    CHECK(renders == 2);
    for (auto x : data) CHECK(x == 1);
    JNI_METHOD(PauseLoop)(&env, nullptr, 1);
    CHECK(closes == 1 && !output.stream && output.in_flight.load() == 0);
    int oldrenders = renders;
    CHECK(registered_data(&fake_stream, nullptr, data, 144) == AAUDIO_CALLBACK_RESULT_STOP);
    CHECK(renders == oldrenders);
    for (auto x : data) CHECK(x == 0);
    JNI_METHOD(PauseLoop)(&env, nullptr, 0);
    CHECK(opens == 3 && starts == 2);
    registered_error(&fake_stream, nullptr, AAUDIO_ERROR_DISCONNECTED);
    CHECK(closes == 1); // Error callback only publishes; never closes.
    JNI_METHOD(StartLoop)(&env, nullptr);
    CHECK(errors == 2 && opens == 3);
    JNI_METHOD(StopLoop)(&env, nullptr);
    CHECK(closes == 2);
    returned_rate = 48000;
    CHECK(!open_output(&env) && errors == 3 && !output.stream);
    returned_rate = 44100;
    fail_open = 2;
    CHECK(!open_output(&env) && errors == 4 && !output.stream);
    fail_start = 1;
    start_output(&env);
    CHECK(errors == 5 && !output.stream);
    fail_start = 0;
    start_output(&env);
    set_value<int32_t>(0x429fdc, 128);
    CHECK(registered_data(&fake_stream, nullptr, data, 144) == AAUDIO_CALLBACK_RESULT_STOP);
    CHECK(output.error.load() == AAUDIO_ERROR_INVALID_STATE);
    set_value<int32_t>(0x429fdc, 0);
    fail_close = 1;
    CHECK(!close_output(&env) && output.poisoned && errors == 6);
    int oldopens = opens;
    CHECK(!open_output(&env) && opens == oldopens && errors == 7);
    free(engine_base);
    puts("PASS: renderer guard; exclusive/shared retry; prepare/resume idempotence; pause quiescence; callback silence; error publication; rate/open/start failures; poisoned close blocks reopen.");
}
