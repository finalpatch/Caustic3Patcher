// Fake driver shared by proof and recovery tests; include after production source.
struct AAudioStreamStruct {} fake_stream;
struct AAudioStreamBuilderStruct {} fake_builder;
static int opens, starts, closes, renders, errors, process_frames, requested_sharing;
static int fail_open, fail_start, fail_close, returned_rate = 44100;
#ifdef CAUSTIC_RECOVERY
static int burst = 144, minimum = 144, capacity = 2048, requested_buffer, latency_frames;
static int returned_channels = 2;
static aaudio_format_t returned_format = AAUDIO_FORMAT_PCM_I16;
static aaudio_stream_state_t stream_state = AAUDIO_STREAM_STATE_OPEN;
#endif
static AAudioStream_dataCallback registered_data;
static AAudioStream_errorCallback registered_error;
extern "C" {
aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder **b) { *b = &fake_builder; return AAUDIO_OK; }
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder *) { return AAUDIO_OK; }
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder *, aaudio_direction_t x) { CHECK(x == AAUDIO_DIRECTION_OUTPUT); }
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder *, aaudio_format_t x) { CHECK(x == AAUDIO_FORMAT_PCM_I16); }
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder *, int32_t x) { CHECK(x == 2); }
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder *, int32_t x) { CHECK(x == 44100); }
void AAudioStreamBuilder_setUsage(AAudioStreamBuilder *, aaudio_usage_t x) { CHECK(x == AAUDIO_USAGE_MEDIA); }
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder *, aaudio_performance_mode_t x) { CHECK(x == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY); }
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder *, aaudio_sharing_mode_t x) { requested_sharing = x; }
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *, AAudioStream_dataCallback cb, void *) { registered_data = cb; }
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *, AAudioStream_errorCallback cb, void *) { registered_error = cb; }
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *, AAudioStream **s) {
    ++opens;
    if (fail_open > 0) { --fail_open; *s = nullptr; return AAUDIO_ERROR_UNAVAILABLE; }
    *s = &fake_stream; return AAUDIO_OK;
}
aaudio_result_t AAudioStream_requestStart(AAudioStream *) { ++starts; return fail_start ? AAUDIO_ERROR_UNAVAILABLE : AAUDIO_OK; }
aaudio_result_t AAudioStream_requestStop(AAudioStream *) { return AAUDIO_OK; }
aaudio_result_t AAudioStream_close(AAudioStream *) { ++closes; return fail_close ? AAUDIO_ERROR_INTERNAL : AAUDIO_OK; }
int32_t AAudioStream_getXRunCount(AAudioStream *) { return 0; }
int32_t AAudioStream_getSampleRate(AAudioStream *) { return returned_rate; }
int32_t AAudioStream_getChannelCount(AAudioStream *) {
#ifdef CAUSTIC_RECOVERY
    return returned_channels;
#else
    return 2;
#endif
}
aaudio_format_t AAudioStream_getFormat(AAudioStream *) {
#ifdef CAUSTIC_RECOVERY
    return returned_format;
#else
    return AAUDIO_FORMAT_PCM_I16;
#endif
}
#ifdef CAUSTIC_RECOVERY
int32_t AAudioStream_getFramesPerBurst(AAudioStream *) { return burst; }
int32_t AAudioStream_setBufferSizeInFrames(AAudioStream *, int32_t x) {
    requested_buffer = x;
    if (minimum < 0) return minimum;
    if (x < minimum) x = minimum;
    return x < capacity ? x : capacity;
}
int32_t AAudioStream_getBufferCapacityInFrames(AAudioStream *) { return capacity; }
aaudio_stream_state_t AAudioStream_getState(AAudioStream *) { return stream_state; }
#else
int32_t AAudioStream_getFramesPerBurst(AAudioStream *) { return 144; }
int32_t AAudioStream_setBufferSizeInFrames(AAudioStream *, int32_t x) { CHECK(x == 288); return x; }
int32_t AAudioStream_getBufferCapacityInFrames(AAudioStream *) { return 2048; }
#endif
aaudio_sharing_mode_t AAudioStream_getSharingMode(AAudioStream *) { return requested_sharing; }
aaudio_performance_mode_t AAudioStream_getPerformanceMode(AAudioStream *) { return AAUDIO_PERFORMANCE_MODE_LOW_LATENCY; }
int32_t AAudioStream_getDeviceId(AAudioStream *) { return 7; }
const char *AAudio_convertResultToText(aaudio_result_t) { return "fake AAudio result"; }
}
static jclass find_class(JNIEnv *, const char *) { return reinterpret_cast<jclass>(1); }
static jint throw_new(JNIEnv *, jclass, const char *) { ++errors; return 0; }
static void delete_local(JNIEnv *, jobject) {}
static void mock_render(int32_t *data, int32_t frames) {
    CHECK(frames == 128);
    for (int i = 0; i < frames * 2; ++i) { CHECK(data[i] == 0); data[i] = 512; }
    ++renders;
}
template<class T> void set_value(uintptr_t offset, T value) { memcpy(static_cast<char *>(engine_base) + offset, &value, sizeof(value)); }
