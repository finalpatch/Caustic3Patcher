// Android native-process test. Minimal JNI shim; no ART or physical audio claims.
// Include production source for dispatch-unit checks; separately load the actual
// shared helper for real ELF resolution and safe setter forwarding checks.
#include "../patches/src/native/caustic_audio.cpp"
#include <stdlib.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); exit(1); } } while (0)

static int errors;
static int calls;
static JNIEnv *expected_env;
static jclass expected_class = reinterpret_cast<jclass>(uintptr_t(0x1234));
static int argument_a, argument_b;
static bool global_ref_failure;

static jclass find_class(JNIEnv *, const char *) { return expected_class; }
static jint throw_new(JNIEnv *, jclass, const char *message) {
    fprintf(stderr, "Expected JNI failure: %s\n", message);
    ++errors;
    return 0;
}
static void delete_local(JNIEnv *, jobject) {}
static jobject global_ref(JNIEnv *, jobject value) {
    if (global_ref_failure) { ++errors; return nullptr; }
    return value;
}
static jboolean same(JNIEnv *, jobject a, jobject b) { return a == b; }
static const char *get_string(JNIEnv *, jstring value, jboolean *) {
    return reinterpret_cast<const char *>(value);
}
static void release_string(JNIEnv *, jstring, const char *) {}
static jstring new_string(JNIEnv *, const char *value) {
    return reinterpret_cast<jstring>(strdup(value));
}
static void capture(JNIEnv *env, jclass owner) {
    CHECK(env == expected_env && owner == expected_class);
    ++calls;
}
static void capture_int(JNIEnv *env, jclass owner, jint value) {
    capture(env, owner);
    argument_a = value;
}
static void capture_resample(JNIEnv *env, jclass owner, jboolean value, jint frames) {
    capture(env, owner);
    argument_a = value; argument_b = frames;
}

int main(int argc, char **argv) {
    CHECK(JNI_METHOD(nativeHasAAudio)(nullptr, nullptr) == JNI_FALSE);
    CHECK(argc == 3);
    JNINativeInterface table{};
    table.FindClass = find_class;
    table.ThrowNew = throw_new;
    table.DeleteLocalRef = delete_local;
    table.NewGlobalRef = global_ref;
    table.IsSameObject = same;
    table.GetStringUTFChars = get_string;
    table.ReleaseStringUTFChars = release_string;
    table.NewStringUTF = new_string;
    JNIEnv env{&table};
    expected_env = &env;

    JNI_METHOD(StartLoop)(&env, nullptr);
    JNI_METHOD(PauseLoop)(&env, nullptr, 1);
    JNI_METHOD(StopLoop)(&env, nullptr);
    JNI_METHOD(SetWantResample)(&env, nullptr, JNI_TRUE, 192);
    JNI_METHOD(SetNumBuffers)(&env, nullptr, 4);
    CHECK(errors == 5 && calls == 0);
    dispatch = {capture, capture_int, capture, capture_resample, capture_int};
    original_class = expected_class;
    ready.store(true, std::memory_order_release);
    JNI_METHOD(StartLoop)(&env, nullptr);
    JNI_METHOD(PauseLoop)(&env, nullptr, -7);
    CHECK(argument_a == -7);
    JNI_METHOD(StopLoop)(&env, nullptr);
    JNI_METHOD(SetWantResample)(&env, nullptr, JNI_TRUE, 12345);
    CHECK(argument_a == JNI_TRUE && argument_b == 12345);
    JNI_METHOD(SetWantResample)(&env, nullptr, JNI_FALSE, -98);
    CHECK(argument_a == JNI_FALSE && argument_b == -98);
    JNI_METHOD(SetNumBuffers)(&env, nullptr, 31);
    CHECK(argument_a == 31 && calls == 6 && errors == 5);
    puts("PASS: all five controls reject pre-init; mock dispatch preserves JNIEnv/class/arguments.");

    void *helper = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!helper) fprintf(stderr, "%s\n", dlerror());
    CHECK(helper);
    auto init = reinterpret_cast<void (*)(JNIEnv *, jclass, jstring, jclass)>(
            dlsym(helper, "Java_com_singlecellsoftware_caustic_audio_AudioBackend_nativeInitialize"));
    auto path = reinterpret_cast<jstring (*)(JNIEnv *, jclass)>(
            dlsym(helper, "Java_com_singlecellsoftware_caustic_audio_AudioBackend_nativeLoadedLibraryPath"));
    auto buffers = reinterpret_cast<void (*)(JNIEnv *, jclass, jint)>(
            dlsym(helper, "Java_com_singlecellsoftware_caustic_audio_AudioBackend_SetNumBuffers"));
    auto resample = reinterpret_cast<void (*)(JNIEnv *, jclass, jboolean, jint)>(
            dlsym(helper, "Java_com_singlecellsoftware_caustic_audio_AudioBackend_SetWantResample"));
    CHECK(init && path && buffers && resample);
    errors = 0;
    CHECK(path(&env, nullptr) == nullptr && errors == 1);
    init(&env, nullptr, reinterpret_cast<jstring>(argv[2]), expected_class);
    CHECK(errors == 2); // Engine exists on disk but NOLOAD must reject it.
    init(&env, nullptr, reinterpret_cast<jstring>(argv[1]), expected_class);
    CHECK(errors == 3); // Loaded helper is not the original engine.
    init(&env, nullptr, nullptr, expected_class);
    CHECK(errors == 4);

    void *engine = dlopen(argv[2], RTLD_NOW | RTLD_LOCAL);
    if (!engine) fprintf(stderr, "%s\n", dlerror());
    CHECK(engine);
    jstring found = path(&env, nullptr);
    CHECK(found && strcmp(reinterpret_cast<char *>(found), argv[2]) == 0);
    free(found);
    global_ref_failure = true;
    init(&env, nullptr, reinterpret_cast<jstring>(argv[2]), expected_class);
    CHECK(errors == 5);
    buffers(&env, nullptr, 4);
    CHECK(errors == 6); // Failed initialization did not publish dispatch.
    global_ref_failure = false;
    init(&env, nullptr, reinterpret_cast<jstring>(argv[2]), expected_class);
    CHECK(errors == 6);
    init(&env, nullptr, reinterpret_cast<jstring>(argv[2]), expected_class);
    CHECK(errors == 6); // Idempotent.
    init(&env, nullptr, reinterpret_cast<jstring>(argv[2]),
         reinterpret_cast<jclass>(uintptr_t(0x5678)));
    CHECK(errors == 7);

    Dl_info info{};
    CHECK(dladdr(dlsym(engine, "Java_com_singlecellsoftware_OpenSLIO_StartLoop"), &info));
    auto base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    auto count = reinterpret_cast<int32_t *>(base + 0x424630);
    auto enabled = reinterpret_cast<int32_t *>(base + 0x54cc54);
    int32_t old_count = *count, old_enabled = *enabled;
    buffers(&env, nullptr, 4);
    CHECK(*count == 4 && errors == 7);
    resample(&env, nullptr, JNI_TRUE, 457);
    CHECK(*enabled == 1);
    resample(&env, nullptr, JNI_FALSE, 79);
    CHECK(*enabled == 0);
    buffers(&env, nullptr, old_count);
    resample(&env, nullptr, old_enabled != 0, 0);
    CHECK(*count == old_count && *enabled == old_enabled);
    // Negative address validation independent of missing symbols: resolved
    // handles must match the five exact offsets/instructions (production resolve).
    Dispatch checked{}; void *checked_base = nullptr;
    CHECK(resolve(engine, &checked, &checked_base) && checked_base == info.dli_fbase);
    puts("PASS: actual helper/engine loading, NOLOAD, discovery, pinned ABI, class identity and allocation-failure handling.");
    puts("PASS: actual original SetNumBuffers/SetWantResample effects; values restored; repeat initialization succeeds.");
    puts("NOT TESTED here: ART, real Start/Pause/Stop, playback, input, lifecycle and APK loader namespace.");
    return 0;
}
